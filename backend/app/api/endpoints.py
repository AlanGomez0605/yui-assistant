from fastapi import APIRouter, Response
from pydantic import BaseModel, Field
from ..core.config import get_settings
from ..models.schemas import ChatRequest, ChatResponse
from ..services.gemini_service import gemini_service
from ..services.memory_service import memory_service
from ..services.voice_service import voice_service

router = APIRouter()
settings = get_settings()

class SpeakRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=4000)

@router.get("/health")
async def health_check():
    return {
        "status": "online",
        "system": settings.PROJECT_NAME,
        "version": settings.VERSION,
        "assistant": settings.ASSISTANT_NAME,
        "owner": settings.OWNER_NAME,
        "gemini_configured": gemini_service.is_configured()
    }

@router.post("/chat", response_model=ChatResponse)
async def chat_with_yui(request: ChatRequest):
    reply = await gemini_service.generate_reply(
        message=request.message,
        chat_history=request.history,
        persist_session="api_session",
        client_time=request.client_time,
        client_timezone=request.client_timezone
    )
    return ChatResponse(
        reply=reply,
        assistant=settings.ASSISTANT_NAME,
        status="success"
    )

# =============================================================================
# ENDPOINTS DE VOZ
# =============================================================================

@router.post("/voice/speak")
async def speak_text(req: SpeakRequest):
    audio_bytes = await voice_service.synthesize_to_bytes(req.text)
    return Response(content=audio_bytes, media_type="audio/mpeg")

# =============================================================================
# ENDPOINTS DE MEMORIA
# =============================================================================

@router.get("/memories")
async def get_memories():
    return await memory_service.get_all_memories()

@router.delete("/memories/{memory_id}")
async def delete_single_memory(memory_id: str):
    """Elimina físicamente un recuerdo de MongoDB Atlas."""
    success = await memory_service.delete_memory(memory_id)
    return {"status": "deleted", "id": memory_id, "success": success}
