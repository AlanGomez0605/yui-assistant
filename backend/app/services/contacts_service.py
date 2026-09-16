import datetime
import logging
import re
from typing import List, Dict, Optional, Any
from ..core.mongodb import mongodb_manager
from ..core.config import get_settings

settings = get_settings()

class ContactsService:
    def __init__(self):
        self._initialized = False

    async def ensure_db(self):
        if not self._initialized:
            await mongodb_manager.connect()
            self._initialized = True

    def _normalize_phone(self, phone: str) -> str:
        """Limpia espacios, guiones y símbolos de un número telefónico para comparación exacta."""
        cleaned = re.sub(r'[^\d+]', '', phone.strip())
        return cleaned

    async def sync_contacts_from_device(self, contacts_list: List[Dict[str, Any]]) -> int:
        """Sincroniza la agenda de contactos de Android directamente a MongoDB Atlas."""
        await self.ensure_db()
        if not mongodb_manager.is_connected():
            return 0

        coll = mongodb_manager.get_collection("contacts")
        saved_count = 0

        for contact in contacts_list:
            name = contact.get("name", "").strip()
            phone = str(contact.get("phone", "")).strip()
            if not name or not phone:
                continue

            normalized = self._normalize_phone(phone)
            doc = {
                "name": name,
                "phone": phone,
                "normalized_phone": normalized,
                "relationship": contact.get("relationship", "known"),
                "is_vip": bool(contact.get("is_vip", False)),
                "custom_greeting": contact.get("custom_greeting"),
                "updated_at": datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M")
            }

            await coll.update_one(
                {"$or": [{"normalized_phone": normalized}, {"name": name}]},
                {"$set": doc},
                upsert=True
            )
            saved_count += 1

        return saved_count

    async def find_contact_by_number(self, phone_number: str) -> Optional[Dict[str, Any]]:
        """Busca si un número entrante pertenece a los contactos registrados de Alan."""
        await self.ensure_db()
        if not mongodb_manager.is_connected():
            return None

        coll = mongodb_manager.get_collection("contacts")
        norm = self._normalize_phone(str(phone_number))

        last_10 = norm[-10:] if len(norm) >= 10 else norm
        query = {
            "$or": [
                {"normalized_phone": norm},
                {"phone": str(phone_number)},
                {"normalized_phone": {"$regex": f"{last_10}$"}}
            ]
        }
        contact = await coll.find_one(query)
        if contact:
            contact["id"] = str(contact.get("_id", ""))
            contact.pop("_id", None)
            return contact
        return None

    async def get_all_contacts(self, limit: int = 50) -> List[Dict[str, Any]]:
        """Obtiene la lista completa de contactos autorizados."""
        await self.ensure_db()
        if not mongodb_manager.is_connected():
            return []

        coll = mongodb_manager.get_collection("contacts")
        cursor = coll.find({}).sort("name", 1).limit(limit)
        results = []
        async for doc in cursor:
            doc["id"] = str(doc.get("_id", ""))
            doc.pop("_id", None)
            results.append(doc)
        return results

    async def add_manual_contact(self, name: str, phone: str, relationship: str = "amigo", is_vip: bool = False) -> Dict[str, Any]:
        """Permite agregar un contacto manualmente o mediante voz."""
        await self.ensure_db()
        data = [{
            "name": name,
            "phone": phone,
            "relationship": relationship,
            "is_vip": is_vip
        }]
        await self.sync_contacts_from_device(data)
        return await self.find_contact_by_number(phone) or {"name": name, "phone": phone}

contacts_service = ContactsService()
