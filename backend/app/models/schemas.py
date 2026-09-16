from pydantic import BaseModel, Field
from typing import List, Optional, Dict

class ChatMessage(BaseModel):
    role: str = Field(..., description="'user' o 'assistant'")
    content: str = Field(..., description="Texto del mensaje")

class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, description="Mensaje del usuario")
    history: Optional[List[Dict[str, str]]] = Field(default=[], description="Historial reciente de conversación")
    client_time: Optional[str] = Field(default=None, description="Hora local exacta del cliente (ej. 2026-09-16 03:33:00)")
    client_timezone: Optional[str] = Field(default="America/Mexico_City", description="Zona horaria del cliente")

class ChatResponse(BaseModel):
    reply: str = Field(..., description="Respuesta de Yui")
    assistant: str = "Yui"
    status: str = "success"
