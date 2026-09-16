import datetime
from typing import List, Dict, Optional, Any
from sqlalchemy import select, update, delete, desc
from ..core.database import AsyncSessionLocal, init_db
from ..models.db_models import Memory, Reminder, ConversationMessage, UserProfile
from ..core.config import get_settings

settings = get_settings()

class MemoryService:
    def __init__(self):
        self._initialized = False

    async def ensure_db(self):
        if not self._initialized:
            await init_db()
            self._initialized = True

    # =========================================================================
    # RECORDATORIOS / TAREAS
    # =========================================================================
    async def add_reminder(self, title: str, due_datetime: str, description: Optional[str] = None) -> Reminder:
        await self.ensure_db()
        async with AsyncSessionLocal() as session:
            reminder = Reminder(
                title=title.strip(),
                due_datetime=due_datetime.strip(),
                description=description.strip() if description else None,
                is_completed=False,
                notified=False
            )
            session.add(reminder)
            await session.commit()
            await session.refresh(reminder)
            return reminder

    async def get_active_reminders(self) -> List[Dict[str, Any]]:
        await self.ensure_db()
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

    async def complete_reminder(self, reminder_id: int) -> bool:
        await self.ensure_db()
        async with AsyncSessionLocal() as session:
            stmt = update(Reminder).where(Reminder.id == reminder_id).values(is_completed=True)
            await session.execute(stmt)
            await session.commit()
            return True

    # =========================================================================
    # RECUERDOS PERMANENTES (CON RESOLUCIÓN DE CONFLICTOS / ACTUALIZACIÓN)
    # =========================================================================
    async def save_or_update_memory(self, content: str, category: str = "fact", importance: int = 3, topic_keywords: Optional[List[str]] = None) -> Memory:
        """Guarda un nuevo recuerdo y reemplaza recuerdos anteriores contradictorios del mismo tema."""
        await self.ensure_db()
        async with AsyncSessionLocal() as session:
            # 1. Si se indican palabras clave o si es una preferencia, buscar y actualizar recuerdos obsoletos
            all_stmt = select(Memory).where(Memory.category == category)
            result = await session.execute(all_stmt)
            existing_memories = result.scalars().all()

            # Detectar si hay un recuerdo previo sobre el mismo tema (ej. 'color favorito')
            keywords_to_check = topic_keywords or []
            if not keywords_to_check and "color favorito" in content.lower():
                keywords_to_check = ["color favorito"]
            elif not keywords_to_check and "viaje" in content.lower():
                keywords_to_check = ["viaje"]

            if keywords_to_check:
                for old_mem in existing_memories:
                    if any(kw in old_mem.content.lower() for kw in keywords_to_check):
                        old_mem.content = content.strip()
                        old_mem.importance = importance
                        old_mem.last_recalled_at = datetime.datetime.utcnow()
                        await session.commit()
                        await session.refresh(old_mem)
                        return old_mem

            # 2. Si no existía uno previo del mismo tema, crear uno nuevo
            memory = Memory(
                content=content.strip(),
                category=category.strip(),
                importance=importance,
                last_recalled_at=datetime.datetime.utcnow()
            )
            session.add(memory)
            await session.commit()
            await session.refresh(memory)
            return memory

    async def add_memory(self, content: str, category: str = "fact", importance: int = 3) -> Memory:
        return await self.save_or_update_memory(content=content, category=category, importance=importance)

    async def delete_memory_by_topic(self, topic_keyword: str) -> None:
        """Elimina recuerdos obsoletos que coincidan con un tema."""
        await self.ensure_db()
        async with AsyncSessionLocal() as session:
            stmt = select(Memory)
            result = await session.execute(stmt)
            all_mems = result.scalars().all()
            for m in all_mems:
                if topic_keyword.lower() in m.content.lower():
                    await session.delete(m)
            await session.commit()

    async def get_all_memories(self, limit: int = 30) -> List[Dict[str, Any]]:
        await self.ensure_db()
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

    # =========================================================================
    # HISTORIAL DE CONVERSACIÓN PERSISTENTE
    # =========================================================================
    async def save_message(self, role: str, content: str, session_id: str = "default") -> None:
        await self.ensure_db()
        async with AsyncSessionLocal() as session:
            msg = ConversationMessage(
                session_id=session_id,
                role=role,
                content=content
            )
            session.add(msg)
            await session.commit()

    async def get_recent_messages(self, session_id: str = "default", limit: int = 15) -> List[Dict[str, str]]:
        await self.ensure_db()
        async with AsyncSessionLocal() as session:
            stmt = select(ConversationMessage).where(
                ConversationMessage.session_id == session_id
            ).order_by(desc(ConversationMessage.id)).limit(limit)
            result = await session.execute(stmt)
            messages = result.scalars().all()
            return [
                {"role": m.role, "content": m.content}
                for m in reversed(messages)
            ]

    # =========================================================================
    # INYECCIÓN DE CONTEXTO ACTIVO
    # =========================================================================
    async def build_dynamic_context(self) -> str:
        """Construye el bloque de memoria viva inyectado al prompt de Yui."""
        reminders = await self.get_active_reminders()
        memories = await self.get_all_memories(limit=20)

        context_parts = []

        if reminders:
            context_parts.append("📌 RECORDATORIOS Y PENDIENTES ACTIVOS DE ALAN:")
            for r in reminders:
                desc = f" ({r['description']})" if r['description'] else ""
                context_parts.append(f"  • [ID #{r['id']}] {r['title']} - Para: {r['due_datetime']}{desc}")
        else:
            context_parts.append("📌 RECORDATORIOS: Actualmente no hay recordatorios pendientes.")

        if memories:
            context_parts.append("\n🧠 RECUERDOS Y HECHOS CONFIRMADOS SOBRE ALAN (Respeta estrictamente estos datos):")
            for m in memories:
                context_parts.append(f"  • {m['content']}")

        return "\n".join(context_parts)

memory_service = MemoryService()
