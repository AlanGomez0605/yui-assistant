from fastapi import APIRouter, HTTPException, Response
from typing import List, Optional
from pydantic import BaseModel
from ..core.config import get_settings
from ..models.schemas import ChatRequest, ChatResponse
from ..services.gemini_service import gemini_service
from ..services.memory_service import memory_service
from ..services.voice_service import voice_service
from ..services.voice_auth_service import voice_auth_service

router = APIRouter()
settings = get_settings()

class ReminderCreate(BaseModel):
    title: str
    due_datetime: str
    description: Optional[str] = None

class MemoryCreate(BaseModel):
    content: str
    category: str = "fact"
    importance: int = 3

class SpeakRequest(BaseModel):
    text: str

class VoiceAuthorizeRequest(BaseModel):
    name: str
    nickname: Optional[str] = None
    role: str = "guest"

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

@router.get("/status")
async def system_status():
    reminders = await memory_service.get_active_reminders()
    return {
        "assistant_state": "active",
        "mood": "happy",
        "message": f"¡Hola {settings.OWNER_NICKNAME}! Todos los sistemas base, voz y memoria persistente están listos.",
        "active_reminders_count": len(reminders)
    }

@router.post("/chat", response_model=ChatResponse)
async def chat_with_yui(request: ChatRequest):
    """Envía un mensaje a Yui y recibe su respuesta con personalidad y memoria activa."""
    reply = await gemini_service.generate_reply(
        message=request.message,
        chat_history=request.history,
        persist_session="api_session"
    )
    return ChatResponse(
        reply=reply,
        assistant=settings.ASSISTANT_NAME,
        status="success"
    )

# =============================================================================
# ENDPOINTS DE VOZ Y AUDIO (TTS & SPEAKER ID)
# =============================================================================

@router.post("/voice/speak")
async def speak_text(req: SpeakRequest):
    """Sintetiza texto a audio MP3 de alta fidelidad para reproducción en cliente."""
    audio_bytes = await voice_service.synthesize_to_bytes(req.text)
    return Response(content=audio_bytes, media_type="audio/mpeg")

@router.get("/voice/authorized")
async def get_authorized_voices():
    """Obtiene la lista de voces autorizadas para interactuar con Yui."""
    return await voice_auth_service.get_authorized_voices()

@router.post("/voice/authorize")
async def authorize_voice(req: VoiceAuthorizeRequest):
    """Agrega un nuevo perfil de voz autorizado."""
    profile = await voice_auth_service.add_authorized_voice(
        name=req.name,
        role=req.role,
        nickname=req.nickname
    )
    return {"status": "authorized", "profile": profile}

@router.post("/voice/revoke")
async def revoke_voice(name: str):
    """Revoca los permisos de una voz."""
    success = await voice_auth_service.revoke_authorized_voice(name)
    return {"status": "revoked" if success else "not_found", "name": name}

# =============================================================================
# ENDPOINTS DE MEMORIA Y RECORDATORIOS
# =============================================================================

@router.get("/reminders")
async def get_reminders():
    return await memory_service.get_active_reminders()

@router.post("/reminders")
async def create_reminder(req: ReminderCreate):
    reminder = await memory_service.add_reminder(
        title=req.title,
        due_datetime=req.due_datetime,
        description=req.description
    )
    return {"status": "created", "id": reminder.get("id"), "title": reminder.get("title")}

@router.post("/reminders/{reminder_id}/complete")
async def complete_reminder(reminder_id: str):
    await memory_service.complete_reminder(reminder_id)
    return {"status": "completed", "id": reminder_id}

@router.get("/memories")
async def get_memories():
    return await memory_service.get_all_memories()

@router.post("/memories")
async def create_memory(req: MemoryCreate):
    memory = await memory_service.save_or_update_memory(
        content=req.content,
        category=req.category,
        importance=req.importance
    )
    return {"status": "saved", "memory": memory}

@router.get("/history")
async def get_history(limit: int = 15):
    return await memory_service.get_recent_conversations(limit=limit)
