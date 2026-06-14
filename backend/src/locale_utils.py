"""Locale helpers for multilingual card analysis."""

from __future__ import annotations

OUTPUT_LANGUAGE_NAMES: dict[str, str] = {
    "zh": "Simplified Chinese",
    "zh-cn": "Simplified Chinese",
    "zh-tw": "Traditional Chinese",
    "en": "English",
    "ja": "Japanese",
}

UI_LANGUAGE_NAMES: dict[str, str] = {
    "zh": "Simplified Chinese",
    "zh-cn": "Simplified Chinese",
    "zh-tw": "Traditional Chinese",
    "en": "English",
    "ja": "Japanese",
}


def normalize_locale(locale: str) -> str:
    value = (locale or "").strip().lower().replace("_", "-")
    if not value:
        return "zh-cn"
    if value.startswith("zh"):
        return "zh-cn"
    if value.startswith("en"):
        return "en"
    if value.startswith("ja"):
        return "ja"
    return value.split("-", 1)[0]


def language_family(locale: str) -> str:
    normalized = normalize_locale(locale)
    if normalized.startswith("zh"):
        return "zh"
    return normalized.split("-", 1)[0]


def locales_differ(source_locale: str, ui_locale: str) -> bool:
    return language_family(source_locale) != language_family(ui_locale)


def output_language_name(locale: str) -> str:
    key = normalize_locale(locale)
    return OUTPUT_LANGUAGE_NAMES.get(key, OUTPUT_LANGUAGE_NAMES.get(language_family(key), "English"))


def ui_language_name(locale: str) -> str:
    key = normalize_locale(locale)
    return UI_LANGUAGE_NAMES.get(key, UI_LANGUAGE_NAMES.get(language_family(key), "English"))
