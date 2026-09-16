import datetime
import logging
from typing import Dict, Any, Optional
from .contacts_service import contacts_service
from .voice_service import voice_service
from ..core.mongodb import mongodb_manager
from ..core.config import get_settings

settings = get_settings()

class TelephonyService:
    def __init__(self):
        self.wait_threshold_seconds = 30
        self._initialized = False

    async def ensure_db(self):
        if not self._initialized:
            await mongodb_manager.connect()
            self._initialized = True

    async def evaluate_incoming_call(self, phone_number: str, ringing_seconds: int = 35) -> Dict[str, Any]:
        """
        Evalúa una llamada entrante tras el periodo de espera (30-40s).
        - Si es un contacto registrado: Contesta y reproduce el mensaje de voz de Yui.
        - Si es un número desconocido: Cuelga / rechaza la llamada automáticamente.
        """
        await self.ensure_db()
        now = datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
        contact = await contacts_service.find_contact_by_number(phone_number)

        if contact:
            # 1. Contacto Registrado -> Contestar amablemente
            contact_name = contact.get("name", "amigo/a")
            spoken_message = (
                f"Hola, {contact_name}. Soy Yui, la asistente personal de {settings.OWNER_NICKNAME}. "
                f"En este momento {settings.OWNER_NICKNAME} se encuentra ocupado y no puede tomar la llamada, "
                f"pero se comunicará contigo en cuanto se desocupe. "
                f"Puedes colgar tranquilamente o aguardar en la línea si gustas."
            )

            result = {
                "action": "answer",
                "is_registered_contact": True,
                "contact_name": contact_name,
                "phone_number": str(phone_number),
                "spoken_message": spoken_message,
                "ringing_seconds": ringing_seconds,
                "timestamp": now,
                "status": "handled_by_yui"
            }
        else:
            # 2. Desconocido / No Registrado -> Colgar inmediatamente
            result = {
                "action": "hangup",
                "is_registered_contact": False,
                "contact_name": "Número Desconocido",
                "phone_number": str(phone_number),
                "spoken_message": None,
                "ringing_seconds": ringing_seconds,
                "timestamp": now,
                "status": "rejected_unknown_number"
            }

        await self._log_call(result)
        return result

    async def _log_call(self, call_record: Dict[str, Any]):
        await self.ensure_db()
        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("call_history")
            await coll.insert_one(dict(call_record))

    async def get_call_history(self, limit: int = 20) -> list:
        await self.ensure_db()
        if not mongodb_manager.is_connected():
            return []

        coll = mongodb_manager.get_collection("call_history")
        cursor = coll.find({}).sort("_id", -1).limit(limit)
        calls = []
        async for c in cursor:
            c["id"] = str(c.get("_id", ""))
            c.pop("_id", None)
            calls.append(c)
        return calls

telephony_service = TelephonyService()
