import json
import logging
import asyncio
import re
import datetime
import warnings
from typing import Dict, Any, Optional

warnings.filterwarnings("ignore")
logging.getLogger("google").setLevel(logging.ERROR)
logging.getLogger("google.genai").setLevel(logging.ERROR)

from google.genai import types
from .memory_service import memory_service
from ..core.config import get_settings

settings = get_settings()

EXTRACTION_SYSTEM_PROMPT = """Eres el subsistema cognitivo de extracción, actualización y eliminación de memoria de Yui (MHCP-0001).
Tu misión es analizar el último intercambio entre el usuario ({owner_name}) y Yui, y extraer:
1. Nuevos recordatorios o tareas solicitadas.
   - Hora actual de {owner_name}: {client_time}.
   - Si {owner_name} dice "en 5 minutos" o "a las 3:35 am", calcula la fecha y hora en formato "YYYY-MM-DD HH:MM".
2. Eliminación física de recordatorios:
   - Si {owner_name} dice "borra mis recordatorios", "elimina todos los recordatorios", "limpia los pendientes": marca "delete_all_reminders": true.
   - Si pide borrar uno específico (ej. "borra el de cambiar la música"): pon su título en "delete_reminder_titles": ["cambiar la música"].
3. Nuevos hechos, gustos o preferencias de {owner_name} con su motivo emocional.
4. Eliminación de recuerdos:
   - Si {owner_name} dice "olvida que...", "borra lo de mi color favorito": pon el tema en "delete_memory_topics": ["color favorito"].
5. Recordatorios que el usuario haya indicado que completó.

Responde ÚNICAMENTE con un JSON válido:
{{
  "new_reminders": [
    {{
      "title": "Título claro del recordatorio",
      "due_datetime": "YYYY-MM-DD HH:MM",
      "description": "Detalles adicionales o null"
    }}
  ],
  "delete_all_reminders": false,
  "delete_reminder_titles": [],
  "completed_reminder_ids": [],
  "new_memories": [
    {{
      "content": "Hecho en 3ra persona",
      "category": "preference" | "place" | "habit" | "project" | "fact",
      "importance": 1 a 5,
      "topic_keywords": ["tema"],
      "reason_or_story": "Motivo o null"
    }}
  ],
  "delete_memory_topics": []
}}
"""

class MemoryExtractorService:
    def __init__(self, gemini_service):
        self.gemini = gemini_service

    async def analyze_and_extract(
        self,
        user_message: str,
        assistant_reply: str,
        client_time: Optional[str] = None
    ) -> None:
        """Analiza la interacción y actualiza o elimina registros en la base de datos."""
        if not self.gemini.is_configured():
            return

        try:
            current_time_str = client_time or datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
            prompt = f"INTERCAMBIO A ANALIZAR:\nUsuario ({settings.OWNER_NICKNAME}): {user_message}\nYui: {assistant_reply}"

            def _call_extraction():
                config = types.GenerateContentConfig(
                    system_instruction=EXTRACTION_SYSTEM_PROMPT.format(
                        owner_name=settings.OWNER_NAME,
                        client_time=current_time_str
                    ),
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

            # 1. Eliminación de recordatorios si Alan lo pidió
            if data.get("delete_all_reminders") is True:
                print(f"[YUI DB] Eliminando TODOS los recordatorios de {settings.OWNER_NICKNAME}...")
                await memory_service.delete_all_reminders()

            for title_to_del in data.get("delete_reminder_titles", []):
                print(f"[YUI DB] Eliminando recordatorio que coincide con: '{title_to_del}'...")
                await memory_service.delete_reminders_by_title(title_to_del)

            # 2. Eliminación de recuerdos por tema
            for topic_to_del in data.get("delete_memory_topics", []):
                print(f"[YUI DB] Eliminando recuerdos del tema: '{topic_to_del}'...")
                await memory_service.delete_memory_by_topic(topic_to_del)

            # 3. Guardar nuevos recordatorios
            for rem in data.get("new_reminders", []):
                title = rem.get("title")
                due = rem.get("due_datetime")
                desc = rem.get("description")
                if title and due:
                    await memory_service.add_reminder(title=title, due_datetime=due, description=desc)

            # 4. Guardar o actualizar recuerdos con sus motivos
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

            # 5. Completar recordatorios
            for rem_id in data.get("completed_reminder_ids", []):
                try:
                    await memory_service.complete_reminder(rem_id)
                except Exception:
                    pass

            # 6. Guardar la conversación completa como documento permanente
            await memory_service.save_conversation_exchange(user_message, assistant_reply)

        except Exception as e:
            logging.error(f"Error en extracción/eliminación automática de memoria: {e}")
