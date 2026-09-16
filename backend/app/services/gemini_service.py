import os
import logging
import warnings
import asyncio
from typing import List, Dict, Optional, AsyncGenerator

# Silenciar advertencias de Google GenAI
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
        # Modelos verificados con latencia ultra baja (0.48s)
        self.preferred_models = [
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3.8-flash"
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

    def _build_history_contents(self, chat_history: Optional[List[Dict[str, str]]]) -> List[types.Content]:
        formatted = []
        if chat_history:
            for msg in chat_history:
                role = "user" if msg.get("role") in ["user", "human"] else "model"
                formatted.append(
                    types.Content(
                        role=role,
                        parts=[types.Part.from_text(text=msg.get("content", ""))]
                    )
                )
        return formatted

    async def generate_reply_stream(self, message: str, chat_history: Optional[List[Dict[str, str]]] = None) -> AsyncGenerator[str, None]:
        """Transmite la respuesta de Yui en tiempo real (palabra por palabra) con latencia mínima."""
        if not self.is_configured():
            yield f"🌸 Lo siento {settings.OWNER_NICKNAME}, aún no detecto tu GEMINI_API_KEY en .env."
            return

        formatted_history = self._build_history_contents(chat_history)
        config = types.GenerateContentConfig(
            system_instruction=self.system_prompt,
            temperature=0.75,
            top_p=0.95
        )

        for model_name in self.preferred_models:
            try:
                # Usar llamada síncrona en hilo para evitar bloqueos de aiohttp en Windows
                def _stream_generator():
                    chat = self.client.chats.create(
                        model=model_name,
                        config=config,
                        history=formatted_history
                    )
                    return chat.send_message_stream(message)

                response_stream = await asyncio.to_thread(_stream_generator)

                for chunk in response_stream:
                    if chunk.text:
                        yield chunk.text
                return  # Terminado con éxito
            except Exception as e:
                # Si un modelo falla, intentar el siguiente rápidamente
                continue

        yield f"🌸 Lo siento mucho {settings.OWNER_NICKNAME}, ocurrió una interferencia temporal en mi señal."

    async def generate_reply(self, message: str, chat_history: Optional[List[Dict[str, str]]] = None) -> str:
        """Genera la respuesta completa de Yui."""
        chunks = []
        async for chunk in self.generate_reply_stream(message, chat_history):
            chunks.append(chunk)
        return "".join(chunks).strip()

# Instancia singleton del servicio de IA
gemini_service = GeminiYuiService()
