import logging
import os
import base64
import json
import re
from typing import Dict, Any
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import requests

load_dotenv()

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Rokid Card Backend")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
# gemini-2.0-flash 已下线，默认改用 gemini-2.5-flash
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
GEMINI_API_URL = (
    f"https://generativelanguage.googleapis.com/v1beta/models/"
    f"{GEMINI_MODEL}:generateContent?key={GEMINI_API_KEY}"
)

_API_KEY_CONFIGURED = bool(
    GEMINI_API_KEY and GEMINI_API_KEY != "your_gemini_api_key_here"
)


@app.on_event("startup")
async def startup() -> None:
    logger.info(
        "Backend ready (model=%s, api_key_configured=%s)",
        GEMINI_MODEL,
        _API_KEY_CONFIGURED,
    )


class ImageAnalysisRequest(BaseModel):
    image: str  # Base64 encoded image


CARD_RESPONSE_SCHEMA = {
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


def parse_json_response(text: str) -> Dict[str, Any]:
    cleaned = text.strip()
    cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*```$", "", cleaned)

    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        pass

    match = re.search(r"\{[\s\S]*\}", cleaned)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError as e:
            logger.error("JSON parse failed, raw response: %s", cleaned[:800])
            raise ValueError(f"Gemini 返回的 JSON 无法解析: {e}") from e

    logger.error("No JSON object in response: %s", cleaned[:800])
    raise ValueError("Gemini 未返回有效 JSON")


def extract_response_text(result: Dict[str, Any]) -> str:
    candidates = result.get("candidates") or []
    if not candidates:
        raise ValueError("No response from Gemini")

    parts = candidates[0].get("content", {}).get("parts") or []
    texts = [p["text"] for p in parts if isinstance(p, dict) and "text" in p]
    if not texts:
        raise ValueError("Gemini response has no text parts")
    return "\n".join(texts)


def analyze_with_gemini(image_data: bytes) -> Dict[str, Any]:
    if not GEMINI_API_KEY or GEMINI_API_KEY == "your_gemini_api_key_here":
        raise ValueError(
            "未配置 GEMINI_API_KEY。请到 https://aistudio.google.com/apikey 获取，"
            "格式通常以 AIza 开头"
        )

    base64_image = base64.b64encode(image_data).decode('utf-8')

    prompt = (
        "Analyze this business card image. Extract visible text and infer missing fields. "
        "Use empty string for unknown fields. Escape quotes inside string values properly. "
        "Field semantics: title=job title; department=organizational unit; "
        "phone=landline or main office number; mobile=cell phone; fax=fax number."
    )

    request_data = {
        "contents": [{
            "parts": [
                {"text": prompt},
                {
                    "inline_data": {
                        "mime_type": "image/jpeg",
                        "data": base64_image
                    }
                }
            ]
        }],
        "generationConfig": {
            "temperature": 0.2,
            "maxOutputTokens": 2048,
            "responseMimeType": "application/json",
            "responseSchema": CARD_RESPONSE_SCHEMA,
        }
    }

    headers = {"Content-Type": "application/json"}
    response = requests.post(GEMINI_API_URL, json=request_data, headers=headers, timeout=60)
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


@app.get("/")
async def root():
    return {"status": "ok", "message": "Rokid Card Backend API"}


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/api/analyze")
async def analyze_card(request: ImageAnalysisRequest):
    try:
        image_data = base64.b64decode(request.image)
        logger.info("Analyzing image: %d bytes, model=%s", len(image_data), GEMINI_MODEL)
        result = analyze_with_gemini(image_data)
        return result
    except Exception as e:
        logger.error("Analyze failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn

    port = int(os.getenv("PORT", "8000"))
    uvicorn.run(app, host="0.0.0.0", port=port)
