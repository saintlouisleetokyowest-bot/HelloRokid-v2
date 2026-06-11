"""Gemini API helpers."""

from __future__ import annotations

import base64
import json
import logging
import re
from typing import Any

import requests

logger = logging.getLogger(__name__)


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
            logger.error("JSON parse failed, raw response: %s", cleaned[:800])
            raise ValueError(f"Gemini 返回的 JSON 无法解析: {exc}") from exc

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

        result = response.json()
        text = extract_response_text(result)
        return parse_json_response(text)
