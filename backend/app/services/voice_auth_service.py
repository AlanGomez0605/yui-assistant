import datetime
import logging
from typing import List, Dict, Optional, Any
from ..core.mongodb import mongodb_manager
from ..core.config import get_settings

settings = get_settings()

class VoiceAuthService:
    def __init__(self):
        self._initialized = False

    async def ensure_defaults(self):
        """Asegura que el perfil de voz principal (Alan) esté siempre registrado como autorizado."""
        if self._initialized:
            return

        now = datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M")
        owner_profile = {
            "name": settings.OWNER_NAME,
            "nickname": settings.OWNER_NICKNAME,
            "role": "owner",
            "is_authorized": True,
            "voiceprint_status": "metadata_only",
            "created_at": now
        }

        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("authorized_voices")
            existing = await coll.find_one({"role": "owner"})
            if not existing:
                await coll.insert_one(owner_profile)

        self._initialized = True

    async def get_authorized_voices(self) -> List[Dict[str, Any]]:
        """Obtiene la lista de todas las voces con permiso de interactuar con Yui."""
        await self.ensure_defaults()
        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("authorized_voices")
            cursor = coll.find({"is_authorized": True})
            voices = []
            async for v in cursor:
                v["id"] = str(v.get("_id", v.get("id")))
                v.pop("_id", None)
                voices.append(v)
            return voices

        # Fallback predeterminado
        return [
            {
                "name": settings.OWNER_NAME,
                "nickname": settings.OWNER_NICKNAME,
                "role": "owner",
                "is_authorized": True
            }
        ]

    async def add_authorized_voice(self, name: str, role: str = "guest", nickname: Optional[str] = None) -> Dict[str, Any]:
        """Registra una nueva voz autorizada según las órdenes del usuario."""
        await self.ensure_defaults()
        now = datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M")
        profile = {
            "name": name.strip(),
            "nickname": nickname.strip() if nickname else name.strip(),
            "role": role,
            "is_authorized": True,
            "voiceprint_status": "pending_calibration",
            "created_at": now
        }

        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("authorized_voices")
            # Si ya existía, reactivar
            existing = await coll.find_one({"name": name.strip()})
            if existing:
                await coll.update_one({"_id": existing["_id"]}, {"$set": {"is_authorized": True}})
                profile["id"] = str(existing["_id"])
                return profile

            res = await coll.insert_one(profile)
            profile["id"] = str(res.inserted_id)

        return profile

    async def revoke_authorized_voice(self, name_or_id: str) -> bool:
        """Revoca los permisos de una voz autorizada."""
        await self.ensure_defaults()
        if mongodb_manager.is_connected():
            coll = mongodb_manager.get_collection("authorized_voices")
            # No permitir revocar al dueño principal
            await coll.update_one(
                {"$or": [{"name": name_or_id}, {"nickname": name_or_id}], "role": {"$ne": "owner"}},
                {"$set": {"is_authorized": False}}
            )
            return True
        return False

    async def is_voice_authorized(self, name: str) -> bool:
        """Verifica si un locutor está autorizado para que Yui le responda."""
        voices = await self.get_authorized_voices()
        name_lower = name.lower().strip()
        return any(
            name_lower in v.get("name", "").lower() or name_lower in v.get("nickname", "").lower()
            for v in voices
        )

# Instancia singleton del servicio de autenticación de voz
voice_auth_service = VoiceAuthService()
