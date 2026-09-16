from fastapi import APIRouter, HTTPException
from typing import List, Optional
from pydantic import BaseModel
from ..core.config import get_settings
from ..models.schemas import ChatRequest, ChatResponse
from ..services.gemini_service import gemini_service
from ..services.memory_service import memory_service

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
        "message": f"¡Hola {settings.OWNER_NICKNAME}! Todos los sistemas base y mi memoria persistente están listos.",
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
# ENDPOINTS DE MEMORIA Y RECORDATORIOS
# =============================================================================

@router.get("/reminders")
async def get_reminders():
    """Obtiene los recordatorios y tareas pendientes."""
    return await memory_service.get_active_reminders()

@router.post("/reminders")
async def create_reminder(req: ReminderCreate):
    """Crea un nuevo recordatorio en la memoria de Yui."""
    reminder = await memory_service.add_reminder(
        title=req.title,
        due_datetime=req.due_datetime,
        description=req.description
    )
    return {"status": "created", "id": reminder.id, "title": reminder.title}

@router.post("/reminders/{reminder_id}/complete")
async def complete_reminder(reminder_id: int):
    """Marca un recordatorio como completado."""
    await memory_service.complete_reminder(reminder_id)
    return {"status": "completed", "id": reminder_id}

@router.get("/memories")
async def get_memories():
    """Obtiene los recuerdos a largo plazo que Yui ha aprendido sobre Alan."""
    return await memory_service.get_all_memories()

@router.post("/memories")
async def create_memory(req: MemoryCreate):
    """Agrega un recuerdo manualmente a la memoria permanente de Yui."""
    memory = await memory_service.add_memory(
        content=req.content,
        category=req.category,
        importance=req.importance
    )
    return {"status": "saved", "id": memory.id, "content": memory.content}

@router.get("/history")
async def get_history(session_id: str = "default", limit: int = 20):
    """Obtiene el historial de mensajes persistentes."""
    return await memory_service.get_recent_messages(session_id=session_id, limit=limit)
