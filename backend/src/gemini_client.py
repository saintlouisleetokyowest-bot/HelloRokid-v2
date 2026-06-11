"""Gemini API helpers."""

from __future__ import annotations

import base64
import json
import logging
import re
from typing import Any

import requests

logger = logging.getLogger(__name__)


def _is_inside_unclosed_string(text: str) -> bool:
    in_string = False
    escape = False
    for ch in text:
        if escape:
            escape = False
            continue
        if ch == "\\":
            escape = True
            continue
        if ch == '"':
            in_string = not in_string
    return in_string


def _repair_truncated_json_object(text: str) -> dict[str, Any] | None:
    """Best-effort recovery when Gemini output is cut off mid-JSON."""
    start = text.find("{")
    if start < 0:
        return None

    candidate = text[start:].rstrip().rstrip(",")
    if _is_inside_unclosed_string(candidate):
        candidate += '"'

    open_braces = candidate.count("{") - candidate.count("}")
    if open_braces > 0:
        candidate += "}" * open_braces

    try:
        result = json.loads(candidate)
        if isinstance(result, dict):
            logger.warning("Recovered truncated JSON by auto-closing braces/quotes")
            return result
    except json.JSONDecodeError:
        pass
    return None


def parse_json_response(text: str) -> dict[str, Any]:
    cleaned = text.strip()
    cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*```$", "", cleaned)

    try:
        result = json.loads(cleaned)
        if isinstance(result, dict):
            return result
    except json.JSONDecodeError:
        pass

    match = re.search(r"\{[\s\S]*\}", cleaned)
    if match:
        try:
            result = json.loads(match.group(0))
            if isinstance(result, dict):
                return result
        except json.JSONDecodeError as exc:
            repaired = _repair_truncated_json_object(cleaned)
            if repaired is not None:
                return repaired
            logger.error("JSON parse failed, raw response: %s", cleaned[:800])
            raise ValueError(f"Gemini 返回的 JSON 无法解析: {exc}") from exc

    repaired = _repair_truncated_json_object(cleaned)
    if repaired is not None:
        return repaired

    logger.error("No JSON object in response: %s", cleaned[:800])
    raise ValueError("Gemini 未返回有效 JSON")


def extract_response_text(result: dict[str, Any]) -> str:
    candidates = result.get("candidates") or []
    if not candidates:
        raise ValueError("No response from Gemini")

    parts = candidates[0].get("content", {}).get("parts") or []
    texts = [part["text"] for part in parts if isinstance(part, dict) and "text" in part]
    if not texts:
        raise ValueError("Gemini response has no text parts")
    return "\n".join(texts)


def extract_finish_reason(result: dict[str, Any]) -> str | None:
    candidates = result.get("candidates") or []
    if not candidates:
        return None
    reason = candidates[0].get("finishReason")
    return str(reason) if reason else None


class GeminiClient:
    def __init__(self, api_key: str, model: str) -> None:
        self.api_key = api_key.strip()
        self.model = model.strip()
        self.api_url = (
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"{self.model}:generateContent?key={self.api_key}"
        )

    @property
    def configured(self) -> bool:
        return bool(self.api_key and self.api_key != "your_gemini_api_key_here")

    def generate_json(
        self,
        prompt: str,
        schema: dict[str, Any],
        *,
        image_bytes: bytes | None = None,
        temperature: float = 0.2,
        max_output_tokens: int = 2048,
    ) -> dict[str, Any]:
        if not self.configured:
            raise ValueError(
                "未配置 GEMINI_API_KEY。请到 https://aistudio.google.com/apikey 获取，"
                "格式通常以 AIza 开头"
            )

        parts: list[dict[str, Any]] = [{"text": prompt}]
        if image_bytes is not None:
            parts.append(
                {
                    "inline_data": {
                        "mime_type": "image/jpeg",
                        "data": base64.b64encode(image_bytes).decode("utf-8"),
                    }
                }
            )

        request_data = {
            "contents": [{"parts": parts}],
            "generationConfig": {
                "temperature": temperature,
                "maxOutputTokens": max_output_tokens,
                "responseMimeType": "application/json",
                "responseSchema": schema,
            },
        }

        result = self._post_generate(request_data)
        text = extract_response_text(result)
        finish_reason = extract_finish_reason(result)

        if finish_reason == "MAX_TOKENS" and max_output_tokens < 8192:
            retry_tokens = min(max_output_tokens * 2, 8192)
            logger.warning(
                "Gemini output truncated (MAX_TOKENS, limit=%d); retrying with %d tokens",
                max_output_tokens,
                retry_tokens,
            )
            request_data["generationConfig"]["maxOutputTokens"] = retry_tokens
            result = self._post_generate(request_data)
            text = extract_response_text(result)
            finish_reason = extract_finish_reason(result)
            if finish_reason == "MAX_TOKENS":
                logger.warning("Gemini output still truncated after retry; attempting JSON repair")

        return parse_json_response(text)

    def _post_generate(self, request_data: dict[str, Any]) -> dict[str, Any]:
        response = requests.post(
            self.api_url,
            json=request_data,
            headers={"Content-Type": "application/json"},
            timeout=90,
        )
        if not response.ok:
            detail = response.text[:500]
            if response.status_code in (401, 403):
                raise ValueError(
                    f"Gemini API Key 无效或无权访问 (HTTP {response.status_code})。"
                    f"请确认 .env 中的 Key 来自 Google AI Studio (AIza... 开头)。详情: {detail}"
                )
            raise ValueError(f"Gemini 请求失败 HTTP {response.status_code}: {detail}")
        return response.json()
