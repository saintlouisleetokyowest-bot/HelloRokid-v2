import logging
import os
import base64
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

try:
    from src.card_analyzer import CardAnalyzer
    from src.cloudsway_client import CloudswayClient
    from src.gemini_client import GeminiClient
except ModuleNotFoundError:
    from card_analyzer import CardAnalyzer
    from cloudsway_client import CloudswayClient
    from gemini_client import GeminiClient

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
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")

CLOUDSWAY_BASE_PATH = os.getenv("CLOUDSWAY_BASE_PATH", "")
CLOUDSWAY_ENDPOINT = os.getenv("CLOUDSWAY_ENDPOINT", "")
CLOUDSWAY_AK = os.getenv("CLOUDSWAY_AK", "")

gemini_client = GeminiClient(GEMINI_API_KEY, GEMINI_MODEL)
cloudsway_client = CloudswayClient(CLOUDSWAY_BASE_PATH, CLOUDSWAY_ENDPOINT, CLOUDSWAY_AK)
card_analyzer = CardAnalyzer(gemini_client, cloudsway_client)

_API_KEY_CONFIGURED = gemini_client.configured
_CLOUDSWAY_CONFIGURED = cloudsway_client.configured


@app.on_event("startup")
async def startup() -> None:
    logger.info(
        "Backend ready (model=%s, gemini=%s, cloudsway=%s)",
        GEMINI_MODEL,
        _API_KEY_CONFIGURED,
        _CLOUDSWAY_CONFIGURED,
    )


class ImageAnalysisRequest(BaseModel):
    image: str  # Base64 encoded image


@app.get("/")
async def root():
    return {
        "status": "ok",
        "message": "Rokid Card Backend API",
        "pipeline": "ocr -> cloudsway -> gemini-enrich",
        "cloudsway_configured": _CLOUDSWAY_CONFIGURED,
    }


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "gemini_configured": _API_KEY_CONFIGURED,
        "cloudsway_configured": _CLOUDSWAY_CONFIGURED,
    }


@app.post("/api/analyze")
async def analyze_card(request: ImageAnalysisRequest):
    try:
        image_data = base64.b64decode(request.image)
        logger.info(
            "Analyzing image: %d bytes, model=%s, cloudsway=%s",
            len(image_data),
            GEMINI_MODEL,
            _CLOUDSWAY_CONFIGURED,
        )
        result = card_analyzer.analyze(image_data)
        return result
    except Exception as e:
        logger.error("Analyze failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn

    port = int(os.getenv("PORT", "8000"))
    uvicorn.run(app, host="0.0.0.0", port=port)
