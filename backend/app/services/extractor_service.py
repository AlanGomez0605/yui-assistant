import json
import logging
import asyncio
import re
import warnings
from typing import Optional

warnings.filterwarnings("ignore")
logging.getLogger("google").setLevel(logging.ERROR)
logging.getLogger("google.genai").setLevel(logging.ERROR)

from google.genai import types
from .memory_service import memory_service
from ..core.config import get_settings

settings = get_settings()

EXTRACTION_SYSTEM_PROMPT = """Eres el subsistema cognitivo de extracción, actualización y eliminación de memoria de Yui (MHCP-0001).
Tu misión es analizar el último intercambio entre el usuario ({owner_name}) y Yui, y extraer:
1. Nuevos hechos, gustos o preferencias de {owner_name} con su motivo emocional.
2. Eliminación de recuerdos:
   - Si {owner_name} dice "olvida que...", "borra lo de mi color favorito": pon el tema en "delete_memory_topics": ["color favorito"].

Responde ÚNICAMENTE con un JSON válido:
{{
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
        client_time: Optional[str] = None,
        client_timezone: Optional[str] = None
    ) -> None:
        """Analiza la interacción y guarda, actualiza o elimina recuerdos en la base de datos."""
        if not self.gemini.is_configured():
            return

        try:
            prompt = f"INTERCAMBIO A ANALIZAR:\nUsuario ({settings.OWNER_NICKNAME}): {user_message}\nYui: {assistant_reply}"

            def _call_extraction():
                config = types.GenerateContentConfig(
                    system_instruction=EXTRACTION_SYSTEM_PROMPT.format(
                        owner_name=settings.OWNER_NAME
                    ),
                    temperature=0.1
                )
                chat = self.gemini.client.chats.create(
                    model=settings.GEMINI_MODEL,
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

            # 1. Eliminación de recuerdos por tema
            for topic_to_del in data.get("delete_memory_topics", []):
                print(f"[YUI DB] Eliminando recuerdos del tema: '{topic_to_del}'...")
                await memory_service.delete_memory_by_topic(topic_to_del)

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
        except Exception as e:
            logging.error(f"Error en extracción/eliminación automática de memoria: {e}")
