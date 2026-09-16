import os
from typing import List, Dict, Optional
from google import genai
from google.genai import types
from ..core.config import get_settings
from ..core.prompts import get_yui_system_prompt

settings = get_settings()

class GeminiYuiService:
    def __init__(self):
        self.api_key = settings.GEMINI_API_KEY
        self.model_name = "gemini-3.6-flash"  # Modelo de alta velocidad y razonamiento óptimo
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

        try:
            # Construir historial previo si existe
            contents = []
            if chat_history:
                for msg in chat_history:
                    role = "user" if msg.get("role") in ["user", "human"] else "model"
                    contents.append(
                        types.Content(
                            role=role,
                            parts=[types.Part.from_text(text=msg.get("content", ""))]
                        )
                    )

            # Agregar el mensaje actual del usuario
            contents.append(
                types.Content(
                    role="user",
                    parts=[types.Part.from_text(text=message)]
                )
            )

            config = types.GenerateContentConfig(
                system_instruction=self.system_prompt,
                temperature=0.75,
                top_p=0.95
            )

            # Ejecutar llamada asíncrona al modelo
            response = await self.client.aio.models.generate_content(
                model=self.model_name,
                contents=contents,
                config=config
            )

            return response.text.strip() if response.text else "..."
        except Exception as e:
            return f"🌸 Lo siento mucho {settings.OWNER_NICKNAME}, ocurrió un detalle al conectar con mis pensamientos: {str(e)}"

# Instancia singleton del servicio
gemini_service = GeminiYuiService()
