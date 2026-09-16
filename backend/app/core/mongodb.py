import logging
from typing import Optional
import motor.motor_asyncio
from .config import get_settings

settings = get_settings()

class MongoDBManager:
    def __init__(self):
        self.client: Optional[motor.motor_asyncio.AsyncIOMotorClient] = None
        self.db = None
        self._connected = False

    async def connect(self) -> bool:
        """Conecta a la base de datos MongoDB Atlas en la nube si MONGODB_URI está configurado."""
        uri = settings.MONGODB_URI.strip()
        if not uri or "<db_password>" in uri or "tu_mongodb_uri" in uri:
            self._connected = False
            return False

        try:
            self.client = motor.motor_asyncio.AsyncIOMotorClient(
                uri,
                serverSelectionTimeoutMS=5000
            )
            # Verificar ping de conexión
            await self.client.admin.command('ping')
            self.db = self.client[settings.MONGODB_DB_NAME]
            self._connected = True
            logging.info("✅ Conexión exitosa a MongoDB Atlas en la nube!")
            return True
        except Exception as e:
            logging.warning(f"⚠️ No se pudo conectar a MongoDB Atlas ({e}). Se usará almacenamiento local.")
            self._connected = False
            return False

    def is_connected(self) -> bool:
        return self._connected

    def get_collection(self, collection_name: str):
        if self._connected and self.db is not None:
            return self.db[collection_name]
        return None

mongodb_manager = MongoDBManager()
