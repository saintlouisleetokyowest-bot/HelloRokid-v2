import os
import base64
import json
from io import BytesIO
from typing import Dict, Any
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import requests
from PIL import Image

load_dotenv()

app = FastAPI(title="Rokid Card Backend")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
GEMINI_API_URL = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={GEMINI_API_KEY}"


class ImageAnalysisRequest(BaseModel):
    image: str  # Base64 encoded image


def analyze_with_gemini(image_data: bytes) -> Dict[str, Any]:
    base64_image = base64.b64encode(image_data).decode('utf-8')

    prompt = """
Analyze this business card, extract and infer the following information. Return only valid JSON without any other text:
{
    "name": "Name (if any)",
    "title": "Title (if any)",
    "company": "Company name",
    "phone": "Phone number (if any)",
    "email": "Email address (if any)",
    "address": "Address (if any)",
    "website": "Website (if any)",
    "industry": "Industry (infer from company name)",
    "companySize": "Company size (estimate, e.g., 50-200 people)",
    "revenue": "Annual revenue estimate",
    "coreBusiness": "Core business description",
    "markets": "Market reach",
    "partners": "Partners (if on card)",
    "opportunities": "Potential partnership opportunities",
    "investmentReadiness": "Investment/partnership interest assessment",
    "timing": "Time urgency"
}
If some information is not directly available, make reasonable inferences.
    """.strip()

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
            "temperature": 0.4,
            "topK": 32,
            "topP": 1,
            "maxOutputTokens": 1024
        }
    }

    headers = {"Content-Type": "application/json"}
    response = requests.post(GEMINI_API_URL, json=request_data, headers=headers, timeout=60)
    response.raise_for_status()
    result = response.json()

    if "candidates" not in result or not result["candidates"]:
        raise ValueError("No response from Gemini")

    text = result["candidates"][0]["content"]["parts"][0]["text"]
    text = text.replace("```json", "").replace("```", "").strip()

    return json.loads(text)


@app.get("/")
async def root():
    return {"status": "ok", "message": "Rokid Card Backend API"}


@app.post("/api/analyze")
async def analyze_card(request: ImageAnalysisRequest):
    try:
        image_data = base64.b64decode(request.image)
        result = analyze_with_gemini(image_data)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
