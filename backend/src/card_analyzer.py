"""Business card analysis pipeline: OCR -> localization -> Cloudsway search -> Gemini enrichment."""

from __future__ import annotations

import json
import logging
from typing import Any

try:
    from src.cloudsway_client import (
        CloudswayClient,
        build_search_queries,
        format_search_results_for_prompt,
    )
    from src.gemini_client import GeminiClient
    from src.locale_utils import (
        locales_differ,
        normalize_locale,
        output_language_name,
        ui_language_name,
    )
except ModuleNotFoundError:
    from cloudsway_client import (
        CloudswayClient,
        build_search_queries,
        format_search_results_for_prompt,
    )
    from gemini_client import GeminiClient
    from locale_utils import (
        locales_differ,
        normalize_locale,
        output_language_name,
        ui_language_name,
    )

logger = logging.getLogger(__name__)

CONTACT_SCHEMA: dict[str, Any] = {
    "type": "OBJECT",
    "properties": {
        "name": {"type": "STRING"},
        "title": {"type": "STRING"},
        "department": {"type": "STRING"},
        "company": {"type": "STRING"},
        "phone": {"type": "STRING"},
        "mobile": {"type": "STRING"},
        "fax": {"type": "STRING"},
        "email": {"type": "STRING"},
        "address": {"type": "STRING"},
        "website": {"type": "STRING"},
        "sourceLanguage": {"type": "STRING"},
    },
}

LOCALIZATION_SCHEMA: dict[str, Any] = {
    "type": "OBJECT",
    "properties": {
        "nameReading": {"type": "STRING"},
        "companyNameEn": {"type": "STRING"},
        "titleLocalized": {"type": "STRING"},
        "departmentLocalized": {"type": "STRING"},
        "addressEn": {"type": "STRING"},
    },
}

INTEL_SCHEMA: dict[str, Any] = {
    "type": "OBJECT",
    "properties": {
        "industry": {"type": "STRING"},
        "companySize": {"type": "STRING"},
        "revenue": {"type": "STRING"},
        "coreBusiness": {"type": "STRING"},
        "markets": {"type": "STRING"},
        "partners": {"type": "STRING"},
        "opportunities": {"type": "STRING"},
        "investmentReadiness": {"type": "STRING"},
        "timing": {"type": "STRING"},
    },
}

CONTACT_FIELDS = [
    "name",
    "title",
    "department",
    "company",
    "phone",
    "mobile",
    "fax",
    "email",
    "address",
    "website",
]
LOCALIZATION_FIELDS = list(LOCALIZATION_SCHEMA["properties"].keys())
INTEL_FIELDS = list(INTEL_SCHEMA["properties"].keys())
EXTRACT_FIELDS = CONTACT_FIELDS + ["sourceLanguage"]
FULL_CONTACT_FIELDS = EXTRACT_FIELDS + LOCALIZATION_FIELDS

EXTRACT_CONTACT_PROMPT = (
    "Extract only the contact information visible on this business card image. "
    "Copy text exactly as printed on the card; do not translate. "
    "Also set sourceLanguage to the primary language on the card using ISO 639-1 "
    "(e.g. zh, en, ja, ko). "
    "Do not infer company intelligence such as revenue, industry trends, or partnership opportunities. "
    "Use empty string for fields that are not visible or cannot be read confidently. "
    "Field semantics: title=job title; department=organizational unit; "
    "phone=landline or main office number; mobile=cell phone; fax=fax number."
)

LOCALIZE_PROMPT_TEMPLATE = """You localize business card contact fields for cross-language display.

Card source language: {source_language}
User UI language: {ui_language}

Contact fields (original text from the card — do not modify these values in your output):
{contact_json}

Return ONLY the localization helper fields below. Use empty string when a field is not applicable or unknown.

Rules:
- nameReading: Romanized reading of the person's name (Pinyin for Chinese, Hepburn romaji for Japanese, standard romanization for Korean). Leave empty if the name is already in Latin script.
- companyNameEn: Official or commonly used English company name. If the company name is already in English, repeat it. Leave empty if unknown.
- titleLocalized: Job title translated into {ui_language}.
- departmentLocalized: Department translated into {ui_language}.
- addressEn: Mailing-style English address. Leave empty if the address is already in English or unknown.
- Do not invent facts. Prefer well-known official English company names when available.
"""

ENRICH_INTEL_PROMPT_TEMPLATE = """You are a B2B sales intelligence assistant.

Based on the business card contact information and the web search results below, fill in the company intelligence fields.

Rules:
- Write each field in concise {output_language} (1 short sentence, at most {max_chars} characters per field).
- Prefer facts supported by the search results.
- If evidence is weak or missing, return an empty string for that field. Do not fabricate.
- opportunities, investmentReadiness, and timing may include careful business judgment, but must still be grounded in the provided evidence.
- Keep company and person names in their original form when citing them inside a field.
- Keep outputs practical for a salesperson meeting this contact.

Contact information (from business card OCR):
{contact_json}

Web search results:
{search_text}
"""


def _normalize_string_map(data: dict[str, Any], fields: list[str]) -> dict[str, str]:
    return {field: str(data.get(field, "") or "").strip() for field in fields}


def _empty_intel() -> dict[str, str]:
    return {field: "" for field in INTEL_FIELDS}


def _empty_localization() -> dict[str, str]:
    return {field: "" for field in LOCALIZATION_FIELDS}


def _max_field_chars(output_locale: str) -> int:
    family = normalize_locale(output_locale).split("-", 1)[0]
    return 80 if family in {"zh", "ja", "ko"} else 120


class CardAnalyzer:
    def __init__(self, gemini: GeminiClient, cloudsway: CloudswayClient | None) -> None:
        self.gemini = gemini
        self.cloudsway = cloudsway

    def analyze(self, image_data: bytes, ui_locale: str = "zh-CN") -> dict[str, str]:
        contact = self.extract(image_data, ui_locale=ui_locale)
        intel = self.enrich(contact, output_locale=ui_locale)
        return {**contact, **intel}

    def extract(self, image_data: bytes, ui_locale: str = "zh-CN") -> dict[str, str]:
        logger.info("Extract: OCR contact info from business card image")
        raw = self.gemini.generate_json(
            EXTRACT_CONTACT_PROMPT,
            CONTACT_SCHEMA,
            image_bytes=image_data,
            temperature=0.1,
            max_output_tokens=1024,
        )
        contact = _normalize_string_map(raw, EXTRACT_FIELDS)
        source_language = contact.get("sourceLanguage") or "und"
        normalized_ui = normalize_locale(ui_locale)

        if locales_differ(source_language, normalized_ui):
            logger.info(
                "Localizing contact: source=%s ui=%s",
                source_language,
                normalized_ui,
            )
            localized = self._localize_contact(contact, source_language, normalized_ui)
            contact.update(localized)
        else:
            contact.update(_empty_localization())

        logger.info(
            "Extract complete: company=%r, name=%r, sourceLanguage=%r",
            contact.get("company"),
            contact.get("name"),
            contact.get("sourceLanguage"),
        )
        return contact

    def enrich(
        self,
        contact: dict[str, str],
        output_locale: str = "zh-CN",
    ) -> dict[str, str]:
        normalized_contact = _normalize_string_map(contact, FULL_CONTACT_FIELDS)
        search_pages = self._search_company_intel(normalized_contact)
        try:
            return self._enrich_intel(
                normalized_contact,
                search_pages,
                output_locale=output_locale,
            )
        except Exception as exc:
            logger.warning("Intelligence enrichment failed, returning empty intel: %s", exc)
            return _empty_intel()

    def _localize_contact(
        self,
        contact: dict[str, str],
        source_language: str,
        ui_locale: str,
    ) -> dict[str, str]:
        prompt = LOCALIZE_PROMPT_TEMPLATE.format(
            source_language=source_language,
            ui_language=ui_language_name(ui_locale),
            contact_json=json.dumps(
                _normalize_string_map(contact, CONTACT_FIELDS),
                ensure_ascii=False,
                indent=2,
            ),
        )
        raw = self.gemini.generate_json(
            prompt,
            LOCALIZATION_SCHEMA,
            temperature=0.1,
            max_output_tokens=1024,
        )
        return _normalize_string_map(raw, LOCALIZATION_FIELDS)

    def _search_company_intel(self, contact: dict[str, str]) -> list[dict[str, Any]]:
        if not self.cloudsway or not self.cloudsway.configured:
            logger.warning("Cloudsway not configured; skipping web search")
            return []

        queries = build_search_queries(contact)
        if not queries:
            logger.info("No usable search query from contact info; skipping web search")
            return []

        logger.info("Enrich: Cloudsway parallel search with %d queries", len(queries))
        pages = self.cloudsway.search_parallel(queries, fast=True, count=5)
        logger.info("Cloudsway merged results: %d unique pages", len(pages))
        return pages

    def _enrich_intel(
        self,
        contact: dict[str, str],
        search_pages: list[dict[str, Any]],
        output_locale: str,
    ) -> dict[str, str]:
        language = output_language_name(output_locale)
        max_chars = _max_field_chars(output_locale)
        logger.info("Enrich: generating intelligence fields with Gemini (%s)", language)
        prompt = ENRICH_INTEL_PROMPT_TEMPLATE.format(
            output_language=language,
            max_chars=max_chars,
            contact_json=json.dumps(contact, ensure_ascii=False, indent=2),
            search_text=format_search_results_for_prompt(search_pages),
        )
        raw = self.gemini.generate_json(
            prompt,
            INTEL_SCHEMA,
            temperature=0.3,
            max_output_tokens=2048,
        )
        intel = _normalize_string_map(raw, INTEL_FIELDS)
        filled = sum(1 for value in intel.values() if value)
        logger.info("Enrich complete: %d/%d intel fields filled", filled, len(INTEL_FIELDS))
        return intel
