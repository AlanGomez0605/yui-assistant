import json
import logging
import asyncio
import re
import warnings
from typing import Dict, Any, Optional

warnings.filterwarnings("ignore")
logging.getLogger("google").setLevel(logging.ERROR)
logging.getLogger("google.genai").setLevel(logging.ERROR)

from google.genai import types
from .memory_service import memory_service
from ..core.config import get_settings

settings = get_settings()

EXTRACTION_SYSTEM_PROMPT = """Eres el subsistema cognitivo de extracción y actualización de memoria de Yui (MHCP-0001).
Tu misión es analizar el último intercambio entre el usuario ({owner_name}) y Yui, y extraer:
1. Nuevos recordatorios o tareas solicitadas.
2. Nuevos hechos, gustos, o preferencias del usuario, CAPTURANDO EL MOTIVO O HISTORIA EMOCIONAL SI LO MENCIONÓ.
   - Ejemplo: Si el usuario dice "Mi color favorito es el café por el color de tus ojos", debes capturar:
     * content: "El color favorito de Alan es el café."
     * reason_or_story: "Me dijo que es su color favorito por el color de mis ojos."
     * topic_keywords: ["color favorito"]
3. Correcciones o cambios sobre datos anteriores.
4. Recordatorios que el usuario haya indicado que ya completó o canceló.

Responde ÚNICAMENTE con un JSON válido:
{{
  "new_reminders": [
    {{
      "title": "Título del recordatorio",
      "due_datetime": "Fecha y hora",
      "description": "Detalles adicionales o null"
    }}
  ],
  "new_memories": [
    {{
      "content": "Hecho redactado en 3ra persona (ej. 'El color favorito de Alan es el café.')",
      "category": "preference" | "place" | "habit" | "project" | "fact",
      "importance": 1 a 5,
      "topic_keywords": ["color favorito"],
      "reason_or_story": "Motivo, anécdota o explicación que dio el usuario, o null"
    }}
  ],
  "completed_reminder_ids": []
}}
"""

class MemoryExtractorService:
    def __init__(self, gemini_service):
        self.gemini = gemini_service

    async def analyze_and_extract(self, user_message: str, assistant_reply: str) -> None:
        """Analiza la interacción y actualiza la memoria persistente."""
        if not self.gemini.is_configured():
            return

        try:
            prompt = f"INTERCAMBIO A ANALIZAR:\nUsuario ({settings.OWNER_NICKNAME}): {user_message}\nYui: {assistant_reply}"

            def _call_extraction():
                config = types.GenerateContentConfig(
                    system_instruction=EXTRACTION_SYSTEM_PROMPT.format(owner_name=settings.OWNER_NAME),
                    temperature=0.1
                )
                chat = self.gemini.client.chats.create(
                    model="gemini-3.5-flash-lite",
                    config=config
                )
                return chat.send_message(prompt)

            response = await asyncio.to_thread(_call_extraction)
            if not response or not response.text:
                return

            raw_text = response.text.strip()
            cleaned_text = raw_text
            if "```json" in cleaned_text:
                cleaned_text = cleaned_text.split("```json", 1)[1].split("```", 1)[0].strip()
            elif "```" in cleaned_text:
                cleaned_text = cleaned_text.split("```", 1)[1].split("```", 1)[0].strip()

            json_match = re.search(r'\{.*\}', cleaned_text, re.DOTALL)
            if json_match:
                cleaned_text = json_match.group(0)

            data = json.loads(cleaned_text)

            # 1. Guardar recordatorios
            for rem in data.get("new_reminders", []):
                title = rem.get("title")
                due = rem.get("due_datetime")
                desc = rem.get("description")
                if title and due:
                    await memory_service.add_reminder(title=title, due_datetime=due, description=desc)

            # 2. Guardar o actualizar recuerdos con sus motivos
            for mem in data.get("new_memories", []):
                content = mem.get("content")
                cat = mem.get("category", "fact")
                imp = int(mem.get("importance", 3))
                topics = mem.get("topic_keywords", [])
                reason = mem.get("reason_or_story")
                if content:
                    await memory_service.save_or_update_memory(
                        content=content,
                        category=cat,
                        importance=imp,
                        topic_keywords=topics,
                        reason_or_story=reason
                    )

            # 3. Completar recordatorios
            for rem_id in data.get("completed_reminder_ids", []):
                try:
                    await memory_service.complete_reminder(rem_id)
                except Exception:
                    pass

            # 4. Guardar la conversación completa como documento permanente
            await memory_service.save_conversation_exchange(user_message, assistant_reply)

        except Exception as e:
            logging.error(f"Error en extracción automática de memoria: {e}")
