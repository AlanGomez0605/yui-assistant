import datetime
import logging
from typing import List, Dict, Optional, Any
from ..core.mongodb import mongodb_manager
from ..core.config import get_settings

logger = logging.getLogger("google_service")
settings = get_settings()

class GoogleService:
    def __init__(self):
        self._initialized = False

    async def ensure_db(self):
        if not self._initialized:
            await mongodb_manager.connect()
            self._initialized = True

    async def get_all_accounts(self) -> List[Dict[str, Any]]:
        """Obtiene las cuentas de Google vinculadas por Alan."""
        await self.ensure_db()
        if not mongodb_manager.is_connected():
            return []

        coll = mongodb_manager.get_collection("google_accounts")
        cursor = coll.find({}).sort("created_at", -1)
        results = []
        async for doc in cursor:
            doc["id"] = str(doc.get("_id", ""))
            doc.pop("_id", None)
            # Ocultar secretos en listado público
            if "app_password" in doc:
                doc["has_app_password"] = bool(doc["app_password"])
                doc.pop("app_password", None)
            if "refresh_token" in doc:
                doc["has_refresh_token"] = bool(doc["refresh_token"])
                doc.pop("refresh_token", None)
            results.append(doc)
        return results

    async def link_account(self, email: str, display_name: Optional[str] = None, 
                           app_password: Optional[str] = None, 
                           oauth_token: Optional[str] = None,
                           refresh_token: Optional[str] = None,
                           scopes: Optional[List[str]] = None) -> Dict[str, Any]:
        """Registra o actualiza una cuenta de Google para acceso y manipulación autónoma por Yui."""
        await self.ensure_db()
        if not mongodb_manager.is_connected():
            return {"status": "error", "message": "MongoDB not connected"}

        coll = mongodb_manager.get_collection("google_accounts")
        doc = {
            "email": email.strip().lower(),
            "display_name": display_name or email.split("@")[0],
            "app_password": app_password,
            "oauth_token": oauth_token,
            "refresh_token": refresh_token,
            "scopes": scopes or ["gmail", "calendar", "drive"],
            "status": "active",
            "updated_at": datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
        }

        await coll.update_one(
            {"email": email.strip().lower()},
            {"$set": doc, "$setOnInsert": {"created_at": datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")}},
            upsert=True
        )

        logger.info(f"Cuenta de Google vinculada con éxito: {email}")
        return {"status": "success", "email": email, "message": f"Cuenta {email} vinculada a Yui"}

    async def delete_account(self, email: str) -> bool:
        """Elimina una cuenta de Google vinculada."""
        await self.ensure_db()
        if not mongodb_manager.is_connected():
            return False

        coll = mongodb_manager.get_collection("google_accounts")
        res = await coll.delete_one({"email": email.strip().lower()})
        return res.deleted_count > 0

    async def get_active_account(self, email: Optional[str] = None) -> Optional[Dict[str, Any]]:
        """Obtiene credenciales completas de la cuenta activa para operar Gmail/Calendar."""
        await self.ensure_db()
        if not mongodb_manager.is_connected():
            return None

        coll = mongodb_manager.get_collection("google_accounts")
        query = {"email": email.strip().lower()} if email else {"status": "active"}
        return await coll.find_one(query)

google_service = GoogleService()
