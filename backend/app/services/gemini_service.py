import os
import logging
import warnings
from typing import List, Dict, Optional

# Silenciar advertencias internas de Google GenAI
warnings.filterwarnings("ignore")
logging.getLogger("google").setLevel(logging.ERROR)
logging.getLogger("google.genai").setLevel(logging.ERROR)

from google import genai
from google.genai import types
from ..core.config import get_settings
from ..core.prompts import get_yui_system_prompt

settings = get_settings()

class GeminiYuiService:
    def __init__(self):
        self.api_key = settings.GEMINI_API_KEY
        # Modelos activos en orden de velocidad y estabilidad
        self.preferred_models = [
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-2.5-pro"
        ]
        self.system_prompt = get_yui_system_prompt(
            owner_name=settings.OWNER_NAME,
            owner_nickname=settings.OWNER_NICKNAME
        )
        self.client: Optional[genai.Client] = None
        self._init_client()

    def _init_client(self):
        current_key = settings.GEMINI_API_KEY
        if current_key and current_key != "tu_gemini_api_key_aqui":
            self.api_key = current_key
            self.client = genai.Client(api_key=self.api_key)
        else:
            self.client = None

    def is_configured(self) -> bool:
        if not self.client:
            self._init_client()
        return self.client is not None

    async def generate_reply(self, message: str, chat_history: Optional[List[Dict[str, str]]] = None) -> str:
        """Genera una respuesta conversacional con la personalidad de Yui usando el SDK oficial google-genai."""
        if not self.is_configured():
            return (
                f"🌸 Lo siento {settings.OWNER_NICKNAME}, aún no he detectado tu clave GEMINI_API_KEY en el archivo .env. "
                f"Por favor revísala para que podamos conversar."
            )

        # Construir historial previo formateado
        formatted_history = []
        if chat_history:
            for msg in chat_history:
                role = "user" if msg.get("role") in ["user", "human"] else "model"
                formatted_history.append(
                    types.Content(
                        role=role,
                        parts=[types.Part.from_text(text=msg.get("content", ""))]
                    )
                )

        config = types.GenerateContentConfig(
            system_instruction=self.system_prompt,
            temperature=0.75,
            top_p=0.95
        )

        last_error = None
        # Intentar con la lista de modelos en cascada
        for model_name in self.preferred_models:
            try:
                chat = self.client.aio.chats.create(
                    model=model_name,
                    config=config,
                    history=formatted_history
                )
                response = await chat.send_message(message)
                if response and response.text:
                    return response.text.strip()
            except Exception as e:
                last_error = e
                # Continuar al siguiente modelo si hubo 503 o error temporal
                continue

        return f"🌸 Lo siento mucho {settings.OWNER_NICKNAME}, mis pensamientos están algo saturados por el momento: {str(last_error)}"

# Instancia singleton del servicio
gemini_service = GeminiYuiService()
