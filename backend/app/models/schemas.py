from pydantic import BaseModel, Field
from typing import List, Optional, Dict

class ChatMessage(BaseModel):
    role: str = Field(..., description="'user' o 'assistant'")
    content: str = Field(..., description="Texto del mensaje")

class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, description="Mensaje del usuario")
    history: Optional[List[Dict[str, str]]] = Field(default=[], description="Historial reciente de conversación")

class ChatResponse(BaseModel):
    reply: str = Field(..., description="Respuesta de Yui")
    assistant: str = "Yui"
    status: str = "success"
