import asyncio
import datetime
from typing import List, Dict, Any, Set
from fastapi import WebSocket
from .memory_service import memory_service
from .voice_service import voice_service
from ..core.mongodb import mongodb_manager
from ..core.config import get_settings

settings = get_settings()

class ConnectionManager:
    def __init__(self):
        self.active_connections: Set[WebSocket] = set()

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.add(websocket)
        print(f"[PROACTIVE WS] Nuevo cliente conectado. Clientes activos: {len(self.active_connections)}")

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
            print(f"[PROACTIVE WS] Cliente desconectado. Clientes activos: {len(self.active_connections)}")

    async def broadcast_json(self, data: Dict[str, Any]):
        dead_connections = []
        for connection in list(self.active_connections):
            try:
                await connection.send_json(data)
            except Exception as e:
                print(f"[PROACTIVE WS] Error enviando a cliente: {e}")
                dead_connections.append(connection)

        for dead in dead_connections:
            self.disconnect(dead)

connection_manager = ConnectionManager()

class ProactiveService:
    def __init__(self):
        self.is_running = False
        self._task = None

    def start(self):
        if not self.is_running:
            self.is_running = True
            self._task = asyncio.create_task(self._monitoring_loop())
            print("[YUI PROACTIVE] Motor de recordatorios autónomos 24/7 iniciado.")

    def stop(self):
        self.is_running = False
        if self._task:
            self._task.cancel()

    async def _monitoring_loop(self):
        """Ciclo en segundo plano que revisa recordatorios cada 5 segundos."""
        while self.is_running:
            try:
                await self.check_pending_reminders()
            except Exception as e:
                print(f"[YUI PROACTIVE] Error en ciclo de recordatorios: {e}")
            await asyncio.sleep(5)

    async def check_pending_reminders(self):
        now_dt = datetime.datetime.now()
        now_str = now_dt.strftime("%Y-%m-%d %H:%M")
        current_time_str = now_dt.strftime("%I:%M %p")

        # Consultar recordatorios activos no notificados
        reminders = await memory_service.get_active_reminders()
        
        for r in reminders:
            due_str = r.get("due_datetime", "")
            is_notified = r.get("is_notified", False)

            if is_notified:
                continue

            # Comparar si la hora o fecha ya venció
            # Soporta formatos 'YYYY-MM-DD HH:MM', 'HH:MM', etc.
            should_trigger = False
            
            # Chequeo simple si coincide con la hora actual o ya pasó
            if due_str in now_str or now_str >= due_str:
                should_trigger = True
            elif ":" in due_str and not "-" in due_str:
                # Si solo especificó hora (ej. '03:33' o '3:33 am')
                short_now = now_dt.strftime("%H:%M")
                if due_str.strip() == short_now or due_str.lower().strip() == current_time_str.lower().strip():
                    should_trigger = True

            if should_trigger:
                await self.trigger_autonomous_reminder(r)

    async def trigger_autonomous_reminder(self, reminder: Dict[str, Any]):
        rem_id = str(reminder.get("id") or reminder.get("_id", ""))
        title = reminder.get("title", "Recordatorio")
        description = reminder.get("description", "")
        owner_nick = settings.OWNER_NICKNAME

        print(f"\n⚡ [YUI AUTÓNOMA] Disparando recordatorio: '{title}' para {owner_nick}!")

        # Mensaje espontáneo y dulce de Yui
        message_text = f"¡{owner_nick}! 🌸 Disculpa que te interrumpa, me pediste que te avisara: **{title}**."
        if description:
            message_text += f" ({description})"
        message_text += " ¡Aquí estoy para acompañarte!"

        # Marcar como notificado en la base de datos
        await memory_service.mark_reminder_notified(rem_id)

        # Generar audio con voz de Yui
        try:
            audio_base64 = await voice_service.synthesize_to_base64(message_text)
        except Exception as e:
            print(f"[YUI PROACTIVE] Error sintetizando voz: {e}")
            audio_base64 = None

        # Transmitir por WebSocket a la Laptop y Móvil
        payload = {
            "type": "proactive_reminder",
            "title": title,
            "message": message_text,
            "reminder_id": rem_id,
            "timestamp": datetime.datetime.now().isoformat(),
            "audio_base64": audio_base64
        }

        await connection_manager.broadcast_json(payload)

proactive_service = ProactiveService()
