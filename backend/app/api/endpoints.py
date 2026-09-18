from fastapi import APIRouter, HTTPException, Response, WebSocket, WebSocketDisconnect
from typing import List, Optional, Dict, Any
from pydantic import BaseModel
from ..core.config import get_settings
from ..models.schemas import ChatRequest, ChatResponse
from ..services.gemini_service import gemini_service
from ..services.memory_service import memory_service
from ..services.voice_service import voice_service
from ..services.voice_auth_service import voice_auth_service
from ..services.contacts_service import contacts_service
from ..services.telephony_service import telephony_service
from ..services.proactive_service import connection_manager, proactive_service

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

class ContactItem(BaseModel):
    name: Optional[str] = "Sin nombre"
    phone: Optional[str] = ""
    relationship: Optional[str] = "conocido"
    is_vip: Optional[bool] = False

class ContactsSyncRequest(BaseModel):
    contacts: List[ContactItem]

class IncomingCallRequest(BaseModel):
    phone_number: str
    ringing_seconds: Optional[int] = 35

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
        "message": f"¡Hola {settings.OWNER_NICKNAME}! Todos los sistemas base, voz, telefonía y memoria están listos.",
        "active_reminders_count": len(reminders)
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
# CANAL WEBSOCKET PROACTIVO EN VIVO (/ws/live)
# =============================================================================

@router.websocket("/ws/live")
async def websocket_live_channel(websocket: WebSocket):
    """Canal en vivo para recibir avisos proactivos y recordatorios autónomos de Yui."""
    await connection_manager.connect(websocket)
    try:
        while True:
            # Mantener conexión viva con pings/pongs o mensajes de cliente
            data = await websocket.receive_text()
            if data == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        connection_manager.disconnect(websocket)
    except Exception:
        connection_manager.disconnect(websocket)

# =============================================================================
# ENDPOINTS TELEFÓNICOS Y CONTACTOS MÓVILES (FASE 5)
# =============================================================================

@router.post("/contacts/sync")
async def sync_contacts(req: ContactsSyncRequest):
    """Sincroniza la agenda de contactos de Android hacia MongoDB Atlas."""
    count = await contacts_service.sync_contacts_from_device([c.dict() for c in req.contacts])
    return {"status": "synced", "total_contacts": count}

@router.get("/contacts")
async def get_contacts():
    """Obtiene la lista de contactos registrados de Alan."""
    return await contacts_service.get_all_contacts()

@router.post("/contacts")
async def add_single_contact(req: ContactItem):
    """Agrega un contacto individualmente."""
    contact = await contacts_service.add_manual_contact(
        name=req.name,
        phone=req.phone,
        relationship=req.relationship or "conocido",
        is_vip=bool(req.is_vip)
    )
    return {"status": "created", "contact": contact}

@router.post("/telephony/incoming")
async def handle_incoming_call(req: IncomingCallRequest):
    """
    Evalúa una llamada entrante tras 30-40 segundos:
    - Si es contacto conocido: responde con mensaje de cortesía.
    - Si es desconocido: cuelga la llamada.
    """
    decision = await telephony_service.evaluate_incoming_call(
        phone_number=req.phone_number,
        ringing_seconds=req.ringing_seconds or 35
    )
    return decision

@router.get("/telephony/history")
async def get_call_history():
    """Obtiene el registro de llamadas atendidas o filtradas por Yui."""
    return await telephony_service.get_call_history()

# =============================================================================
# ENDPOINTS DE VOZ Y AUDIO
# =============================================================================

@router.post("/voice/speak")
async def speak_text(req: SpeakRequest):
    audio_bytes = await voice_service.synthesize_to_bytes(req.text)
    return Response(content=audio_bytes, media_type="audio/mpeg")

@router.get("/voice/authorized")
async def get_authorized_voices():
    return await voice_auth_service.get_authorized_voices()

@router.post("/voice/authorize")
async def authorize_voice(req: VoiceAuthorizeRequest):
    profile = await voice_auth_service.add_authorized_voice(
        name=req.name,
        role=req.role,
        nickname=req.nickname
    )
    return {"status": "authorized", "profile": profile}

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

@router.delete("/reminders")
async def delete_all_reminders():
    """Elimina físicamente todos los recordatorios."""
    count = await memory_service.delete_all_reminders()
    return {"status": "deleted_all", "count": count}

@router.delete("/reminders/{reminder_id}")
async def delete_single_reminder(reminder_id: str):
    """Elimina físicamente un recordatorio de MongoDB Atlas."""
    success = await memory_service.delete_reminder(reminder_id)
    return {"status": "deleted", "id": reminder_id, "success": success}


@router.get("/memories")
async def get_memories():
    return await memory_service.get_all_memories()

@router.delete("/memories/{memory_id}")
async def delete_single_memory(memory_id: str):
    """Elimina físicamente un recuerdo de MongoDB Atlas."""
    success = await memory_service.delete_memory(memory_id)
    return {"status": "deleted", "id": memory_id, "success": success}

