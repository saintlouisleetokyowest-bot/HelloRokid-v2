import logging
import os
import base64
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

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
    ui_locale: str = Field(default="zh-CN", alias="uiLocale")

    model_config = {"populate_by_name": True}


class ContactEnrichRequest(BaseModel):
    name: str = ""
    title: str = ""
    department: str = ""
    company: str = ""
    phone: str = ""
    mobile: str = ""
    fax: str = ""
    email: str = ""
    address: str = ""
    website: str = ""
    source_language: str = Field(default="", alias="sourceLanguage")
    name_reading: str = Field(default="", alias="nameReading")
    company_name_en: str = Field(default="", alias="companyNameEn")
    title_localized: str = Field(default="", alias="titleLocalized")
    department_localized: str = Field(default="", alias="departmentLocalized")
    address_en: str = Field(default="", alias="addressEn")
    output_locale: str = Field(default="zh-CN", alias="outputLocale")

    model_config = {"populate_by_name": True}


@app.get("/")
async def root():
    return {
        "status": "ok",
        "message": "Rokid Card Backend API",
        "pipeline": "extract -> enrich (staged)",
        "cloudsway_configured": _CLOUDSWAY_CONFIGURED,
    }


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "gemini_configured": _API_KEY_CONFIGURED,
        "cloudsway_configured": _CLOUDSWAY_CONFIGURED,
    }


@app.post("/api/extract")
async def extract_card(request: ImageAnalysisRequest):
    """Fast path: OCR contact fields + optional localization."""
    try:
        image_data = base64.b64decode(request.image)
        logger.info("Extract request: %d bytes, ui_locale=%s", len(image_data), request.ui_locale)
        return card_analyzer.extract(image_data, ui_locale=request.ui_locale)
    except Exception as e:
        logger.error("Extract failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/enrich")
async def enrich_card(request: ContactEnrichRequest):
    """Slow path: search + intelligence fields from contact info."""
    try:
        contact = {
            "name": request.name,
            "title": request.title,
            "department": request.department,
            "company": request.company,
            "phone": request.phone,
            "mobile": request.mobile,
            "fax": request.fax,
            "email": request.email,
            "address": request.address,
            "website": request.website,
            "sourceLanguage": request.source_language,
            "nameReading": request.name_reading,
            "companyNameEn": request.company_name_en,
            "titleLocalized": request.title_localized,
            "departmentLocalized": request.department_localized,
            "addressEn": request.address_en,
        }
        logger.info(
            "Enrich request: company=%r, name=%r, output_locale=%s",
            contact.get("company"),
            contact.get("name"),
            request.output_locale,
        )
        intel = card_analyzer.enrich(contact, output_locale=request.output_locale)
        return {**contact, **intel}
    except Exception as e:
        logger.error("Enrich failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/analyze")
async def analyze_card(request: ImageAnalysisRequest):
    """Legacy full pipeline (extract + enrich in one call)."""
    try:
        image_data = base64.b64decode(request.image)
        logger.info(
            "Analyze request: %d bytes, model=%s, cloudsway=%s, ui_locale=%s",
            len(image_data),
            GEMINI_MODEL,
            _CLOUDSWAY_CONFIGURED,
            request.ui_locale,
        )
        result = card_analyzer.analyze(image_data, ui_locale=request.ui_locale)
        return result
    except Exception as e:
        logger.error("Analyze failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn

    port = int(os.getenv("PORT", "8000"))
    uvicorn.run(app, host="0.0.0.0", port=port)
