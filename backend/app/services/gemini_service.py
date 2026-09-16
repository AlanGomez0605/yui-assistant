import os
import logging
import warnings
import asyncio
from typing import List, Dict, Optional, AsyncGenerator

warnings.filterwarnings("ignore")
logging.getLogger("google").setLevel(logging.ERROR)
logging.getLogger("google.genai").setLevel(logging.ERROR)

from google import genai
from google.genai import types
from ..core.config import get_settings
from ..core.prompts import get_yui_system_prompt
from .memory_service import memory_service

settings = get_settings()

class GeminiYuiService:
    def __init__(self):
        self.api_key = settings.GEMINI_API_KEY
        self.preferred_models = [
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3.8-flash"
        ]
        self.system_prompt_base = get_yui_system_prompt(
            owner_name=settings.OWNER_NAME,
            owner_nickname=settings.OWNER_NICKNAME
        )
        self.client: Optional[genai.Client] = None
        self._pending_tasks = set()
        self._init_client()

        from .extractor_service import MemoryExtractorService
        self.extractor = MemoryExtractorService(self)

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

    async def _get_full_system_instruction(
        self,
        client_time: Optional[str] = None,
        client_timezone: Optional[str] = None
    ) -> str:
        """Combina la personalidad base con la hora local precisa del usuario y la memoria permanente."""
        self.system_prompt_base = get_yui_system_prompt(
            owner_name=settings.OWNER_NAME,
            owner_nickname=settings.OWNER_NICKNAME
        )
        memory_context = await memory_service.build_dynamic_context()
        time_info = ""
        if client_time:
            time_info = f"\n🕒 HORA Y FECHA ACTUAL EN EL DISPOSITIVO DE ALAN: {client_time} ({client_timezone or 'America/Mexico_City'})\n"

        return f"{self.system_prompt_base}\n{time_info}\n---\n### 💾 MEMORIA PERSISTENTE Y RECORDATORIOS ACTUALES:\n{memory_context}\n"

    async def generate_reply_stream(
        self,
        message: str,
        chat_history: Optional[List[Dict[str, str]]] = None,
        persist_session: Optional[str] = "default",
        client_time: Optional[str] = None,
        client_timezone: Optional[str] = None
    ) -> AsyncGenerator[str, None]:
        """Transmite la respuesta de Yui en tiempo real inyectando la hora local y memoria permanente."""
        if not self.is_configured():
            yield f"🌸 Lo siento {settings.OWNER_NICKNAME}, aún no detecto tu GEMINI_API_KEY en .env."
            return

        formatted_history = self._build_history_contents(chat_history)
        full_system_instruction = await self._get_full_system_instruction(
            client_time=client_time,
            client_timezone=client_timezone
        )

        config = types.GenerateContentConfig(
            system_instruction=full_system_instruction,
            temperature=0.75,
            top_p=0.95
        )

        full_reply_parts = []
        stream_success = False

        for model_name in self.preferred_models:
            try:
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
                        full_reply_parts.append(chunk.text)
                        yield chunk.text

                stream_success = True
                break
            except Exception:
                continue

        if not stream_success:
            err_msg = f"🌸 Lo siento mucho {settings.OWNER_NICKNAME}, ocurrió una interferencia temporal en mi señal."
            full_reply_parts.append(err_msg)
            yield err_msg

        # Post-procesamiento asíncrono controlado con hora local
        full_reply = "".join(full_reply_parts)
        if persist_session:
            task = asyncio.create_task(self._post_process(message, full_reply, persist_session, client_time=client_time))
            self._pending_tasks.add(task)
            task.add_done_callback(self._pending_tasks.discard)

    async def wait_for_pending_tasks(self, timeout: float = 3.0):
        """Espera a que finalicen las extracciones de memoria pendientes antes de cerrar la sesión."""
        if self._pending_tasks:
            tasks = list(self._pending_tasks)
            try:
                await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), timeout=timeout)
            except Exception:
                pass

    async def _post_process(self, user_message: str, assistant_reply: str, session_id: str, client_time: Optional[str] = None):
        try:
            await memory_service.save_message("user", user_message, session_id=session_id)
            await memory_service.save_message("assistant", assistant_reply, session_id=session_id)
            await self.extractor.analyze_and_extract(user_message, assistant_reply, client_time=client_time)
        except Exception as e:
            logging.error(f"Error en post-proceso de memoria: {e}")

    async def generate_reply(
        self,
        message: str,
        chat_history: Optional[List[Dict[str, str]]] = None,
        persist_session: Optional[str] = "default",
        client_time: Optional[str] = None,
        client_timezone: Optional[str] = None
    ) -> str:
        """Genera la respuesta completa de Yui."""
        chunks = []
        async for chunk in self.generate_reply_stream(
            message,
            chat_history,
            persist_session=persist_session,
            client_time=client_time,
            client_timezone=client_timezone
        ):
            chunks.append(chunk)
        return "".join(chunks).strip()

# Instancia singleton del servicio de IA
gemini_service = GeminiYuiService()
