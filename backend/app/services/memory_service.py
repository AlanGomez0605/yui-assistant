import datetime
from typing import List, Dict, Optional, Any
from sqlalchemy import select, update, delete, desc
from ..core.database import AsyncSessionLocal, init_db
from ..models.db_models import Memory, Reminder, ConversationMessage
from ..core.mongodb import mongodb_manager
from ..core.config import get_settings

settings = get_settings()

class MemoryService:
    def __init__(self):
        self._db_initialized = False

    async def ensure_db(self):
        if not self._db_initialized:
            # 1. Intentar conectar a MongoDB Atlas en la nube
            await mongodb_manager.connect()
            # 2. Inicializar SQLite local como respaldo seguro
            await init_db()
            self._db_initialized = True

    # =========================================================================
    # RECORDATORIOS / TAREAS (NUBE MONGODB + LOCAL SQLITE)
    # =========================================================================
    async def add_reminder(self, title: str, due_datetime: str, description: Optional[str] = None) -> Dict[str, Any]:
        await self.ensure_db()
        now_str = datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M")
        reminder_data = {
            "title": title.strip(),
            "due_datetime": due_datetime.strip(),
            "description": description.strip() if description else None,
            "is_completed": False,
            "created_at": now_str
        }

        # Guardar en MongoDB si está activo
        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("reminders")
            res = await coll.insert_one(dict(reminder_data))
            reminder_data["id"] = str(res.inserted_id)

        # Respaldar en SQLite local
        async with AsyncSessionLocal() as session:
            r = Reminder(
                title=reminder_data["title"],
                due_datetime=reminder_data["due_datetime"],
                description=reminder_data["description"],
                is_completed=False
            )
            session.add(r)
            await session.commit()
            if "id" not in reminder_data:
                reminder_data["id"] = r.id

        return reminder_data

    async def get_active_reminders(self) -> List[Dict[str, Any]]:
        await self.ensure_db()
        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("reminders")
            cursor = coll.find({"is_completed": False}).sort("_id", -1)
            results = []
            async for doc in cursor:
                doc["id"] = str(doc.get("_id", doc.get("id")))
                doc.pop("_id", None)
                results.append(doc)
            return results

        # Fallback local SQLite
        async with AsyncSessionLocal() as session:
            stmt = select(Reminder).where(Reminder.is_completed == False).order_by(Reminder.id.desc())
            result = await session.execute(stmt)
            reminders = result.scalars().all()
            return [
                {
                    "id": r.id,
                    "title": r.title,
                    "due_datetime": r.due_datetime,
                    "description": r.description,
                    "created_at": r.created_at.strftime("%Y-%m-%d %H:%M")
                }
                for r in reminders
            ]

    async def complete_reminder(self, reminder_id: Any) -> bool:
        await self.ensure_db()
        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("reminders")
            from bson import ObjectId
            try:
                await coll.update_one({"_id": ObjectId(str(reminder_id))}, {"$set": {"is_completed": True}})
            except Exception:
                await coll.update_one({"id": reminder_id}, {"$set": {"is_completed": True}})

        try:
            async with AsyncSessionLocal() as session:
                stmt = update(Reminder).where(Reminder.id == int(reminder_id)).values(is_completed=True)
                await session.execute(stmt)
                await session.commit()
        except Exception:
            pass
        return True

    async def mark_reminder_notified(self, reminder_id: Any) -> bool:
        """Marca un recordatorio como notificado por el motor proactivo de Yui."""
        await self.ensure_db()
        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("reminders")
            from bson import ObjectId
            try:
                await coll.update_one({"_id": ObjectId(str(reminder_id))}, {"$set": {"is_notified": True, "is_completed": True}})
            except Exception:
                await coll.update_one({"id": reminder_id}, {"$set": {"is_notified": True, "is_completed": True}})

        try:
            async with AsyncSessionLocal() as session:
                stmt = update(Reminder).where(Reminder.id == int(reminder_id)).values(is_completed=True)
                await session.execute(stmt)
                await session.commit()
        except Exception:
            pass
        return True


    # =========================================================================
    # RECUERDOS Y HECHOS CON CONTEXTO Y MOTIVOS EMOCIONALES
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
        now = datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M")

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

        # Guardar en MongoDB Atlas
        if mongodb_manager.is_connected():
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

        # Guardar en SQLite local
        async with AsyncSessionLocal() as session:
            all_stmt = select(Memory)
            res = await session.execute(all_stmt)
            existing_mems = res.scalars().all()

            updated = False
            if keywords:
                for old in existing_mems:
                    if any(kw in old.content.lower() for kw in keywords):
                        old.content = full_content
                        old.importance = importance
                        await session.commit()
                        updated = True
                        break

            if not updated:
                m = Memory(
                    content=full_content,
                    category=category.strip(),
                    importance=importance
                )
                session.add(m)
                await session.commit()

        return doc_data

    async def get_all_memories(self, limit: int = 30) -> List[Dict[str, Any]]:
        await self.ensure_db()
        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("memories")
            cursor = coll.find({}).sort("importance", -1).limit(limit)
            results = []
            async for doc in cursor:
                doc["id"] = str(doc.get("_id", doc.get("id")))
                doc.pop("_id", None)
                results.append(doc)
            return results

        async with AsyncSessionLocal() as session:
            stmt = select(Memory).order_by(desc(Memory.importance), desc(Memory.id)).limit(limit)
            result = await session.execute(stmt)
            memories = result.scalars().all()
            return [
                {
                    "id": m.id,
                    "category": m.category,
                    "content": m.content,
                    "importance": m.importance
                }
                for m in memories
            ]

    async def save_message(self, role: str, content: str, session_id: str = "default") -> None:
        """Compatibilidad para guardar mensajes individuales."""
        pass
    async def save_conversation_exchange(self, user_message: str, assistant_reply: str, session_id: str = "default") -> None:
        """Guarda permanentemente cada intercambio de conversación con marcas de tiempo íntegras."""
        await self.ensure_db()
        now = datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")

        doc = {
            "session_id": session_id,
            "user_message": user_message,
            "assistant_reply": assistant_reply,
            "timestamp": now
        }

        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("conversations")
            await coll.insert_one(doc)

        async with AsyncSessionLocal() as session:
            m1 = ConversationMessage(session_id=session_id, role="user", content=user_message)
            m2 = ConversationMessage(session_id=session_id, role="assistant", content=assistant_reply)
            session.add_all([m1, m2])
            await session.commit()

    async def get_recent_conversations(self, limit: int = 10) -> List[Dict[str, Any]]:
        """Obtiene las conversaciones pasadas completas."""
        await self.ensure_db()
        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("conversations")
            cursor = coll.find({}).sort("_id", -1).limit(limit)
            res = []
            async for d in cursor:
                d.pop("_id", None)
                res.append(d)
            return list(reversed(res))

        async with AsyncSessionLocal() as session:
            stmt = select(ConversationMessage).order_by(desc(ConversationMessage.id)).limit(limit * 2)
            result = await session.execute(stmt)
            msgs = list(reversed(result.scalars().all()))
            formatted = []
            for i in range(0, len(msgs) - 1, 2):
                if msgs[i].role == "user" and msgs[i+1].role == "assistant":
                    formatted.append({
                        "user_message": msgs[i].content,
                        "assistant_reply": msgs[i+1].content,
                        "timestamp": msgs[i].created_at.strftime("%Y-%m-%d %H:%M")
                    })
            return formatted

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
                context_parts.append(f"  • {r['title']} - Para: {r['due_datetime']}{desc}")
        else:
            context_parts.append("📌 RECORDATORIOS: No hay recordatorios pendientes.")

        # Recuerdos y motivos
        if memories:
            context_parts.append("\n🧠 RECUERDOS Y DETALLES CONFIRMADOS SOBRE ALAN (Incluyendo motivos y anécdotas):")
            for m in memories:
                context_parts.append(f"  • {m['content']}")

        # Resumen de conversaciones pasadas
        if recent_chats:
            context_parts.append("\n💬 EXTRACTO DE CONVERSACIONES PASADAS CON ALAN:")
            for c in recent_chats:
                context_parts.append(f"  [{c.get('timestamp', '')}] Alan: \"{c.get('user_message', '')}\" | Yui: \"{c.get('assistant_reply', '')[:120]}...\"")

        return "\n".join(context_parts)

memory_service = MemoryService()
