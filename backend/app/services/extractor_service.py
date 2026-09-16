import json
import logging
import asyncio
import re
from typing import Dict, Any, Optional
from google.genai import types
from .memory_service import memory_service
from ..core.config import get_settings

settings = get_settings()

EXTRACTION_SYSTEM_PROMPT = """Eres el subsistema cognitivo de extracción de memoria y tareas de Yui (MHCP-0001).
Tu trabajo es analizar el último intercambio entre el usuario ({owner_name}) y Yui, y extraer:
1. Nuevos recordatorios o tareas que el usuario pidió programar o tener presentes.
2. Nuevos hechos o recuerdos permanentes sobre el usuario (gustos, lugares, viajes, proyectos, relaciones, rutinas).
3. Recordatorios que el usuario haya indicado que ya completó o canceló.

Debes responder ÚNICAMENTE con un JSON válido:
{{
  "new_reminders": [
    {{
      "title": "Título del recordatorio",
      "due_datetime": "Fecha y hora (ej. 'mañana a las 12:00')",
      "description": "Detalles adicionales o null"
    }}
  ],
  "new_memories": [
    {{
      "content": "Hecho redactado en tercera persona (ej. 'El color favorito de Alan es el azul zafiro.')",
      "category": "place" | "preference" | "habit" | "project" | "fact",
      "importance": 1 a 5
    }}
  ],
  "completed_reminder_ids": []
}}
"""

class MemoryExtractorService:
    def __init__(self, gemini_service):
        self.gemini = gemini_service

    async def analyze_and_extract(self, user_message: str, assistant_reply: str) -> None:
        """Analiza la interacción y actualiza la memoria persistente de forma asíncrona y transparente."""
        if not self.gemini.is_configured():
            return

        try:
            prompt = f"INTERCAMBIO A ANALIZAR:\nUsuario ({settings.OWNER_NICKNAME}): {user_message}\nYui: {assistant_reply}"

            def _call_extraction():
                config = types.GenerateContentConfig(
                    system_instruction=EXTRACTION_SYSTEM_PROMPT.format(owner_name=settings.OWNER_NAME),
                    temperature=0.1
                )
                return self.gemini.client.models.generate_content(
                    model="gemini-3.5-flash-lite",
                    contents=prompt,
                    config=config
                )

            response = await asyncio.to_thread(_call_extraction)
            if not response or not response.text:
                return

            raw_text = response.text.strip()
            
            # Limpiar bloques de markdown ```json ... ```
            cleaned_text = raw_text
            if "```json" in cleaned_text:
                cleaned_text = cleaned_text.split("```json", 1)[1].split("```", 1)[0].strip()
            elif "```" in cleaned_text:
                cleaned_text = cleaned_text.split("```", 1)[1].split("```", 1)[0].strip()

            # Intentar encontrar objeto JSON en el texto si aún tiene caracteres externos
            json_match = re.search(r'\{.*\}', cleaned_text, re.DOTALL)
            if json_match:
                cleaned_text = json_match.group(0)

            data = json.loads(cleaned_text)

            # 1. Guardar nuevos recordatorios
            for rem in data.get("new_reminders", []):
                title = rem.get("title")
                due = rem.get("due_datetime")
                desc = rem.get("description")
                if title and due:
                    await memory_service.add_reminder(title=title, due_datetime=due, description=desc)

            # 2. Guardar nuevos recuerdos
            for mem in data.get("new_memories", []):
                content = mem.get("content")
                cat = mem.get("category", "fact")
                imp = int(mem.get("importance", 3))
                if content:
                    await memory_service.add_memory(content=content, category=cat, importance=imp)

            # 3. Completar recordatorios
            for rem_id in data.get("completed_reminder_ids", []):
                try:
                    await memory_service.complete_reminder(int(rem_id))
                except Exception:
                    pass

        except Exception as e:
            logging.error(f"Error en extracción automática de memoria: {e}")
