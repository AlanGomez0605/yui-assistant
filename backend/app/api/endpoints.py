from fastapi import APIRouter, HTTPException, Response, WebSocket, WebSocketDisconnect
from typing import List, Optional, Dict, Any
from pydantic import BaseModel, Field, field_validator
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from ..core.config import get_settings
from ..models.schemas import ChatRequest, ChatResponse
from ..services.gemini_service import gemini_service
from ..services.memory_service import memory_service
from ..services.voice_service import voice_service
from ..services.voice_auth_service import voice_auth_service
from ..services.contacts_service import contacts_service
from ..services.telephony_service import telephony_service
from ..services.google_service import google_service
from ..services.proactive_service import connection_manager, proactive_service
from ..core.security import authentication_configured, websocket_is_authenticated

router = APIRouter()
settings = get_settings()

class ReminderCreate(BaseModel):
    title: str = Field(..., min_length=1, max_length=200)
    due_datetime: str = Field(..., min_length=4, max_length=64)
    description: Optional[str] = Field(default=None, max_length=1000)
    timezone: Optional[str] = Field(default=None, max_length=64)

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value):
        if value:
            try:
                ZoneInfo(value)
            except ZoneInfoNotFoundError as exc:
                raise ValueError("Zona horaria IANA no válida.") from exc
        return value

class MemoryCreate(BaseModel):
    content: str = Field(..., min_length=1, max_length=4000)
    category: str = Field(default="fact", min_length=1, max_length=50)
    importance: int = Field(default=3, ge=1, le=5)

class GoogleAccountLinkRequest(BaseModel):
    email: str = Field(..., min_length=3, max_length=320)
    display_name: Optional[str] = Field(default=None, max_length=200)
    app_password: Optional[str] = Field(default=None, max_length=500)
    oauth_token: Optional[str] = Field(default=None, max_length=8000)
    refresh_token: Optional[str] = Field(default=None, max_length=8000)
    scopes: Optional[List[str]] = Field(default=None, max_length=50)

    @field_validator("email")
    @classmethod
    def validate_email(cls, value):
        normalized = value.strip().lower()
        if "@" not in normalized or normalized.startswith("@") or normalized.endswith("@"):
            raise ValueError("Correo electrónico no válido.")
        return normalized

class SpeakRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=4000)

class VoiceAuthorizeRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=200)
    nickname: Optional[str] = Field(default=None, max_length=200)
    role: str = Field(default="guest", min_length=1, max_length=50)

class ContactItem(BaseModel):
    name: str = Field(default="Sin nombre", min_length=1, max_length=300)
    phone: str = Field(..., min_length=1, max_length=50)
    relationship: str = Field(default="conocido", max_length=100)
    is_vip: bool = False

class ContactsSyncRequest(BaseModel):
    contacts: List[ContactItem] = Field(..., max_length=1000)

class IncomingCallRequest(BaseModel):
    phone_number: str = Field(..., min_length=1, max_length=50)
    ringing_seconds: int = Field(default=35, ge=0, le=300)

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
    if authentication_configured(settings):
        if not websocket_is_authenticated(websocket, settings):
            await websocket.close(code=1008, reason="Authentication required")
            return
    elif not settings.DEBUG:
        await websocket.close(code=1013, reason="API_TOKEN is not configured")
        return
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
    count = await contacts_service.sync_contacts_from_device([c.model_dump() for c in req.contacts])
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
        ringing_seconds=req.ringing_seconds
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
    if proactive_service.parse_due_timestamp(req.due_datetime, req.timezone) is None:
        raise HTTPException(status_code=422, detail="Fecha, hora o zona horaria no válida.")
    reminder = await memory_service.add_reminder(
        title=req.title,
        due_datetime=req.due_datetime,
        description=req.description,
        timezone=req.timezone
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

@router.post("/reminders/{reminder_id}/notified")
async def acknowledge_reminder(reminder_id: str):
    success = await memory_service.mark_reminder_notified(reminder_id)
    if not success:
        raise HTTPException(status_code=404, detail="Recordatorio no encontrado.")
    return {"status": "notified", "id": reminder_id}


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
    return {"status": "created", "memory": memory}

@router.delete("/memories/{memory_id}")
async def delete_single_memory(memory_id: str):
    """Elimina físicamente un recuerdo de MongoDB Atlas."""
    success = await memory_service.delete_memory(memory_id)
    return {"status": "deleted", "id": memory_id, "success": success}

# Alias para sincronización offline / Android
@router.get("/memory/reminders")
async def get_memory_reminders_alias():
    return await memory_service.get_active_reminders()

@router.post("/memory/reminders")
async def create_memory_reminder_alias(req: ReminderCreate):
    if proactive_service.parse_due_timestamp(req.due_datetime, req.timezone) is None:
        raise HTTPException(status_code=422, detail="Fecha, hora o zona horaria no válida.")
    reminder = await memory_service.add_reminder(
        title=req.title,
        due_datetime=req.due_datetime,
        description=req.description,
        timezone=req.timezone
    )
    return {"status": "created", "id": reminder.get("id"), "title": reminder.get("title")}

# =============================================================================
# ENDPOINTS DE CUENTAS DE GOOGLE (GMAIL, CALENDAR, DRIVE)
# =============================================================================

@router.get("/google/accounts")
async def get_google_accounts():
    """Obtiene las cuentas de Google vinculadas a Yui."""
    return await google_service.get_all_accounts()

@router.post("/google/accounts")
async def link_google_account(req: GoogleAccountLinkRequest):
    """Vincula una cuenta de Google para acceso y manipulación por Yui."""
    return await google_service.link_account(
        email=req.email,
        display_name=req.display_name,
        app_password=req.app_password,
        oauth_token=req.oauth_token,
        refresh_token=req.refresh_token,
        scopes=req.scopes
    )

@router.delete("/google/accounts/{email}")
async def delete_google_account(email: str):
    """Desvincula una cuenta de Google."""
    success = await google_service.delete_account(email)
    return {"status": "deleted" if success else "not_found", "email": email}
