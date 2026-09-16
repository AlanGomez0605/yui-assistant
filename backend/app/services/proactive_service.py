import asyncio
import datetime
import re
from typing import List, Dict, Any, Set, Optional
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
            print("[YUI PROACTIVE] Motor de recordatorios autónomos 24/7 iniciado con sincronización de zona horaria.")

    def stop(self):
        self.is_running = False
        if self._task:
            self._task.cancel()

    async def _monitoring_loop(self):
        """Ciclo en segundo plano que revisa recordatorios cada 3 segundos."""
        while self.is_running:
            try:
                await self.check_pending_reminders()
            except Exception as e:
                print(f"[YUI PROACTIVE] Error en ciclo de recordatorios: {e}")
            await asyncio.sleep(3)

    def parse_due_timestamp(self, due_str: str, client_timezone_offset_hours: int = -6) -> Optional[float]:
        """
        Convierte cualquier formato de fecha/hora de recordatorio a timestamp Unix UTC real.
        Por defecto usa UTC-6 (Horario estándar de México / Alan).
        """
        if not due_str:
            return None

        due_str = due_str.strip()
        now_utc = datetime.datetime.now(datetime.timezone.utc)
        tz_user = datetime.timezone(datetime.timedelta(hours=client_timezone_offset_hours))
        now_user = now_utc.astimezone(tz_user)

        # 1. Formato completo 'YYYY-MM-DD HH:MM' o 'YYYY-MM-DD HH:MM:SS'
        match_full = re.match(r'(\d{4})-(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?', due_str)
        if match_full:
            year, month, day, hour, minute = map(int, match_full.groups()[:5])
            second = int(match_full.group(6)) if match_full.group(6) else 0
            dt_user = datetime.datetime(year, month, day, hour, minute, second, tzinfo=tz_user)
            return dt_user.timestamp()

        # 2. Formato de hora solo 'HH:MM am/pm' o 'HH:MM'
        match_time = re.match(r'(\d{1,2}):(\d{2})\s*(am|pm|a\.m\.|p\.m\.)?', due_str, re.IGNORECASE)
        if match_time:
            hour = int(match_time.group(1))
            minute = int(match_time.group(2))
            ampm = match_time.group(3)

            if ampm:
                ampm = ampm.lower().replace(".", "")
                if ampm == "pm" and hour < 12:
                    hour += 12
                elif ampm == "am" and hour == 12:
                    hour = 0

            # Construir fecha para hoy en la zona horaria del usuario
            dt_user = datetime.datetime(
                now_user.year, now_user.month, now_user.day,
                hour, minute, 0, tzinfo=tz_user
            )

            # Si la hora ya pasó hoy por más de 12 horas, programarlo para mañana
            if dt_user.timestamp() < (now_utc.timestamp() - 600):
                dt_user += datetime.timedelta(days=1)

            return dt_user.timestamp()

        return None

    async def check_pending_reminders(self):
        now_utc_ts = datetime.datetime.now(datetime.timezone.utc).timestamp()

        # Consultar recordatorios activos no notificados
        reminders = await memory_service.get_active_reminders()
        
        for r in reminders:
            due_str = r.get("due_datetime", "")
            is_notified = r.get("is_notified", False)

            if is_notified:
                continue

            target_ts = self.parse_due_timestamp(due_str, client_timezone_offset_hours=-6)
            if target_ts is None:
                continue

            # El recordatorio debe dispararse ÚNICAMENTE cuando la hora actual >= hora fijada
            if now_utc_ts >= target_ts:
                time_diff = now_utc_ts - target_ts
                if time_diff <= 3600: # Disparar si ocurrió hace menos de 1 hora
                    await self.trigger_autonomous_reminder(r)
                else:
                    # Si era muy viejo (días atrás), marcarlo como completado sin interrumpir
                    rem_id = str(r.get("id") or r.get("_id", ""))
                    await memory_service.complete_reminder(rem_id)

    async def trigger_autonomous_reminder(self, reminder: Dict[str, Any]):
        rem_id = str(reminder.get("id") or reminder.get("_id", ""))
        title = reminder.get("title", "Recordatorio")
        description = reminder.get("description", "")
        owner_nick = settings.OWNER_NICKNAME

        print(f"\n⚡ [YUI AUTÓNOMA] Disparando recordatorio a la hora exacta: '{title}' para {owner_nick}!")

        # 1. Mensaje espontáneo y dulce de Yui
        message_text = f"¡{owner_nick}! 🌸 Disculpa que te interrumpa, me pediste que te avisara: **{title}**."
        if description:
            message_text += f" ({description})"
        message_text += " ¡Aquí estoy para recordártelo!"

        # 2. Marcar de inmediato como notificado en la base de datos para evitar dobles envíos
        await memory_service.mark_reminder_notified(rem_id)

        # 3. Guardar en el historial de conversación para que aparezca en chat
        try:
            await memory_service.save_conversation_exchange(
                user_message=f"[Recordatorio automático programado: {title}]",
                assistant_reply=message_text,
                session_id="api_session"
            )
        except Exception as e:
            print(f"[YUI PROACTIVE] Error guardando conversación: {e}")

        # 4. Generar audio con voz de Yui
        try:
            audio_base64 = await voice_service.synthesize_to_base64(message_text)
        except Exception as e:
            print(f"[YUI PROACTIVE] Error sintetizando voz: {e}")
            audio_base64 = None

        # 5. Transmitir por WebSocket a la Laptop y Móvil
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
