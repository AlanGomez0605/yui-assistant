import datetime
from typing import List, Dict, Optional, Any
from bson import ObjectId
import re
from ..core.mongodb import mongodb_manager
from ..core.config import get_settings

settings = get_settings()

def _format_id(doc: Dict[str, Any]) -> Dict[str, Any]:
    """Convierte _id de MongoDB a un string 'id' para la API."""
    if doc and "_id" in doc:
        doc["id"] = str(doc.pop("_id"))
    return doc

class MemoryService:
    def __init__(self):
        self._db_initialized = False

    async def ensure_db(self):
        if not self._db_initialized:
            await mongodb_manager.connect()
            self._db_initialized = True

    # =========================================================================
    # RECORDATORIOS / TAREAS (100% NUBE MONGODB ATLAS)
    # =========================================================================
    async def add_reminder(self, title: str, due_datetime: str, description: Optional[str] = None) -> Dict[str, Any]:
        await self.ensure_db()
        now_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
        reminder_data = {
            "title": title.strip(),
            "due_datetime": due_datetime.strip(),
            "description": description.strip() if description else None,
            "is_completed": False,
            "is_notified": False,
            "created_at": now_str
        }

        coll = mongodb_manager.get_collection("reminders")
        res = await coll.insert_one(dict(reminder_data))
        reminder_data["id"] = str(res.inserted_id)
        return reminder_data

    async def get_active_reminders(self) -> List[Dict[str, Any]]:
        await self.ensure_db()
        coll = mongodb_manager.get_collection("reminders")
        cursor = coll.find({"is_completed": False}).sort("_id", -1)
        results = []
        async for doc in cursor:
            results.append(_format_id(doc))
        return results

    async def complete_reminder(self, reminder_id: Any) -> bool:
        await self.ensure_db()
        coll = mongodb_manager.get_collection("reminders")
        try:
            await coll.update_one({"_id": ObjectId(str(reminder_id))}, {"$set": {"is_completed": True}})
        except Exception:
            await coll.update_one({"id": str(reminder_id)}, {"$set": {"is_completed": True}})
        return True

    async def mark_reminder_notified(self, reminder_id: Any) -> bool:
        """Marca un recordatorio como notificado por el motor proactivo de Yui."""
        await self.ensure_db()
        coll = mongodb_manager.get_collection("reminders")
        try:
            await coll.update_one({"_id": ObjectId(str(reminder_id))}, {"$set": {"is_notified": True, "is_completed": True}})
        except Exception:
            await coll.update_one({"id": str(reminder_id)}, {"$set": {"is_notified": True, "is_completed": True}})
        return True

    async def delete_reminder(self, reminder_id: Any) -> bool:
        """Elimina físicamente un recordatorio de MongoDB Atlas."""
        await self.ensure_db()
        coll = mongodb_manager.get_collection("reminders")
        try:
            await coll.delete_one({"_id": ObjectId(str(reminder_id))})
        except Exception:
            await coll.delete_one({"id": str(reminder_id)})
        return True

    async def delete_all_reminders(self) -> int:
        """Elimina físicamente TODOS los recordatorios de MongoDB Atlas."""
        await self.ensure_db()
        coll = mongodb_manager.get_collection("reminders")
        res = await coll.delete_many({})
        return res.deleted_count

    async def delete_reminders_by_title(self, title_query: str) -> int:
        """Elimina recordatorios que coincidan con un texto o título."""
        await self.ensure_db()
        if not title_query:
            return 0
        coll = mongodb_manager.get_collection("reminders")
        regex = re.compile(re.escape(title_query), re.IGNORECASE)
        res = await coll.delete_many({"title": regex})
        return res.deleted_count

    # =========================================================================
    # RECUERDOS Y HECHOS CON CONTEXTO Y MOTIVOS EMOCIONALES (MONGODB ATLAS)
    # =========================================================================
    async def save_or_update_memory(
        self,
        content: str,
        category: str = "fact",
        importance: int = 3,
        topic_keywords: Optional[List[str]] = None,
        reason_or_story: Optional[str] = None
    ) -> Dict[str, Any]:
        """Guarda un recuerdo rico con su contexto y motivo emocional."""
        await self.ensure_db()
        now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")

        keywords = topic_keywords or []
        if not keywords:
            if "color favorito" in content.lower():
                keywords = ["color favorito"]
            elif "viaje" in content.lower():
                keywords = ["viaje"]

        full_content = content.strip()
        if reason_or_story and reason_or_story not in full_content:
            full_content = f"{full_content} (Motivo/Historia: {reason_or_story.strip()})"

        doc_data = {
            "content": full_content,
            "category": category.strip(),
            "importance": importance,
            "topic_keywords": keywords,
            "reason_or_story": reason_or_story,
            "updated_at": now
        }

        coll = mongodb_manager.get_collection("memories")
        if keywords:
            # Actualizar si ya existía un recuerdo sobre el mismo tema
            query = {"topic_keywords": {"$in": keywords}}
            existing = await coll.find_one(query)
            if existing:
                await coll.update_one({"_id": existing["_id"]}, {"$set": doc_data})
                doc_data["id"] = str(existing["_id"])
                return doc_data

        res = await coll.insert_one(dict(doc_data))
        doc_data["id"] = str(res.inserted_id)
        return doc_data

    async def get_all_memories(self, limit: int = 30) -> List[Dict[str, Any]]:
        await self.ensure_db()
        coll = mongodb_manager.get_collection("memories")
        cursor = coll.find({}).sort("importance", -1).limit(limit)
        results = []
        async for doc in cursor:
            results.append(_format_id(doc))
        return results

    async def delete_memory(self, memory_id: Any) -> bool:
        """Elimina físicamente un recuerdo de MongoDB Atlas."""
        await self.ensure_db()
        coll = mongodb_manager.get_collection("memories")
        try:
            await coll.delete_one({"_id": ObjectId(str(memory_id))})
        except Exception:
            await coll.delete_one({"id": str(memory_id)})
        return True

    async def delete_memory_by_topic(self, topic: str) -> int:
        """Elimina recuerdos por palabra clave de tema."""
        await self.ensure_db()
        coll = mongodb_manager.get_collection("memories")
        res = await coll.delete_many({"topic_keywords": topic})
        return res.deleted_count

    async def save_message(self, role: str, content: str, session_id: str = "default") -> None:
        pass

    async def save_conversation_exchange(self, user_message: str, assistant_reply: str, session_id: str = "default") -> None:
        """Guarda permanentemente cada intercambio de conversación con marcas de tiempo íntegras."""
        await self.ensure_db()
        now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        doc = {
            "session_id": session_id,
            "user_message": user_message,
            "assistant_reply": assistant_reply,
            "timestamp": now
        }

        coll = mongodb_manager.get_collection("conversations")
        await coll.insert_one(doc)

    async def get_recent_conversations(self, limit: int = 10) -> List[Dict[str, Any]]:
        """Obtiene las conversaciones pasadas completas."""
        await self.ensure_db()
        coll = mongodb_manager.get_collection("conversations")
        cursor = coll.find({}).sort("_id", -1).limit(limit)
        res = []
        async for d in cursor:
            d.pop("_id", None)
            res.append(d)
        return list(reversed(res))

    # =========================================================================
    # CONSTRUCCIÓN DE CONTEXTO VIVO
    # =========================================================================
    async def build_dynamic_context(self) -> str:
        """Genera el bloque de contexto inyectado al razonamiento de Yui."""
        reminders = await self.get_active_reminders()
        memories = await self.get_all_memories(limit=25)
        recent_chats = await self.get_recent_conversations(limit=4)

        context_parts = []

        # Recordatorios
        if reminders:
            context_parts.append("📌 RECORDATORIOS Y PENDIENTES ACTIVOS DE ALAN:")
            for r in reminders:
                desc = f" ({r['description']})" if r.get('description') else ""
                context_parts.append(f"  • [ID: {r.get('id')}] {r['title']} - Para: {r['due_datetime']}{desc}")
        else:
            context_parts.append("📌 RECORDATORIOS: No hay recordatorios pendientes.")

        # Recuerdos y motivos
        if memories:
            context_parts.append("\n🧠 RECUERDOS Y DETALLES CONFIRMADOS SOBRE ALAN (Incluyendo motivos y anécdotas):")
            for m in memories:
                context_parts.append(f"  • [ID: {m.get('id')}] {m['content']}")

        # Resumen de conversaciones pasadas
        if recent_chats:
            context_parts.append("\n💬 EXTRACTO DE CONVERSACIONES PASADAS CON ALAN:")
            for c in recent_chats:
                context_parts.append(f"  [{c.get('timestamp', '')}] Alan: \"{c.get('user_message', '')}\" | Yui: \"{c.get('assistant_reply', '')[:120]}...\"")

        # Agenda de Contactos sincronizada
        try:
            from .contacts_service import contacts_service
            contacts = await contacts_service.get_all_contacts(limit=500)
            if contacts:
                context_parts.append(f"\n👥 AGENDA TELEFÓNICA DE ALAN ({len(contacts)} contactos disponibles para llamadas/WhatsApp):")
                for ct in contacts:
                    rel = f" [{ct.get('relationship')}]" if ct.get('relationship') and ct.get('relationship') != 'conocido' else ""
                    context_parts.append(f"  • {ct.get('name')}: {ct.get('phone')}{rel}")
        except Exception as e:
            pass

        return "\n".join(context_parts)

memory_service = MemoryService()
