"""Business card analysis pipeline: OCR -> Cloudsway search -> Gemini enrichment."""

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
except ModuleNotFoundError:
    from cloudsway_client import (
        CloudswayClient,
        build_search_queries,
        format_search_results_for_prompt,
    )
    from gemini_client import GeminiClient

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

CONTACT_FIELDS = list(CONTACT_SCHEMA["properties"].keys())
INTEL_FIELDS = list(INTEL_SCHEMA["properties"].keys())

EXTRACT_CONTACT_PROMPT = (
    "Extract only the contact information visible on this business card image. "
    "Do not infer company intelligence such as revenue, industry trends, or partnership opportunities. "
    "Use empty string for fields that are not visible or cannot be read confidently. "
    "Field semantics: title=job title; department=organizational unit; "
    "phone=landline or main office number; mobile=cell phone; fax=fax number."
)

ENRICH_INTEL_PROMPT_TEMPLATE = """You are a B2B sales intelligence assistant.

Based on the business card contact information and the web search results below, fill in the company intelligence fields.

Rules:
- Write each field in concise Chinese (1 short sentence, at most 80 Chinese characters per field).
- Prefer facts supported by the search results.
- If evidence is weak or missing, return an empty string for that field. Do not fabricate.
- opportunities, investmentReadiness, and timing may include careful business judgment, but must still be grounded in the provided evidence.
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


class CardAnalyzer:
    def __init__(self, gemini: GeminiClient, cloudsway: CloudswayClient | None) -> None:
        self.gemini = gemini
        self.cloudsway = cloudsway

    def analyze(self, image_data: bytes) -> dict[str, str]:
        contact = self.extract(image_data)
        intel = self.enrich(contact)
        return {**contact, **intel}

    def extract(self, image_data: bytes) -> dict[str, str]:
        logger.info("Extract: OCR contact info from business card image")
        raw = self.gemini.generate_json(
            EXTRACT_CONTACT_PROMPT,
            CONTACT_SCHEMA,
            image_bytes=image_data,
            temperature=0.1,
            max_output_tokens=1024,
        )
        contact = _normalize_string_map(raw, CONTACT_FIELDS)
        logger.info(
            "Extract complete: company=%r, name=%r, website=%r",
            contact.get("company"),
            contact.get("name"),
            contact.get("website"),
        )
        return contact

    def enrich(self, contact: dict[str, str]) -> dict[str, str]:
        normalized_contact = _normalize_string_map(contact, CONTACT_FIELDS)
        search_pages = self._search_company_intel(normalized_contact)
        try:
            return self._enrich_intel(normalized_contact, search_pages)
        except Exception as exc:
            logger.warning("Intelligence enrichment failed, returning empty intel: %s", exc)
            return _empty_intel()

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
    ) -> dict[str, str]:
        logger.info("Enrich: generating intelligence fields with Gemini")
        prompt = ENRICH_INTEL_PROMPT_TEMPLATE.format(
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
