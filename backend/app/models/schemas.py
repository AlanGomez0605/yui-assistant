from pydantic import BaseModel, Field, field_validator
from typing import List, Optional, Dict
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

class ChatMessage(BaseModel):
    role: str = Field(..., description="'user' o 'assistant'")
    content: str = Field(..., description="Texto del mensaje")

class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=8000, description="Mensaje del usuario")
    history: Optional[List[Dict[str, str]]] = Field(default_factory=list, max_length=40, description="Historial reciente de conversación")
    client_time: Optional[str] = Field(default=None, description="Hora local exacta del cliente (ej. 2026-09-16 03:33:00)")
    client_timezone: Optional[str] = Field(default="America/Mexico_City", description="Zona horaria del cliente")

    @field_validator("client_timezone")
    @classmethod
    def validate_timezone(cls, value):
        if value:
            try:
                ZoneInfo(value)
            except ZoneInfoNotFoundError as exc:
                raise ValueError("Zona horaria IANA no válida.") from exc
        return value

class ChatResponse(BaseModel):
    reply: str = Field(..., description="Respuesta de Yui")
    assistant: str = "Yui"
    status: str = "success"
