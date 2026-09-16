from fastapi import APIRouter
from backend.app.core.config import get_settings

router = APIRouter()
settings = get_settings()

@router.get("/health")
async def health_check():
    return {
        "status": "online",
        "system": settings.PROJECT_NAME,
        "version": settings.VERSION,
        "assistant": settings.ASSISTANT_NAME,
        "owner": settings.OWNER_NAME,
        "gemini_configured": bool(settings.GEMINI_API_KEY and settings.GEMINI_API_KEY != "tu_gemini_api_key_aqui")
    }

@router.get("/status")
async def system_status():
    return {
        "assistant_state": "active",
        "mood": "happy",
        "message": f"¡Hola {settings.OWNER_NICKNAME}! Todos los sistemas base están listos."
    }
