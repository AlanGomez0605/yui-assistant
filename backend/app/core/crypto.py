import base64
import hashlib
from typing import Optional

from cryptography.fernet import Fernet, InvalidToken

from .config import get_settings


class SecretCipher:
    PREFIX = "enc:v1:"

    def __init__(self):
        settings = get_settings()
        source = settings.DATA_ENCRYPTION_KEY.strip() or settings.API_TOKEN.strip()
        self._fernet = None
        if source:
            key = base64.urlsafe_b64encode(hashlib.sha256(source.encode("utf-8")).digest())
            self._fernet = Fernet(key)

    def encrypt(self, value: Optional[str]) -> Optional[str]:
        if not value:
            return value
        if value.startswith(self.PREFIX):
            return value
        if self._fernet is None:
            raise RuntimeError("DATA_ENCRYPTION_KEY o API_TOKEN es obligatorio para guardar secretos.")
        encrypted = self._fernet.encrypt(value.encode("utf-8")).decode("ascii")
        return self.PREFIX + encrypted

    def decrypt(self, value: Optional[str]) -> Optional[str]:
        if not value or not value.startswith(self.PREFIX):
            return value
        if self._fernet is None:
            raise RuntimeError("No se puede descifrar el secreto sin DATA_ENCRYPTION_KEY o API_TOKEN.")
        try:
            return self._fernet.decrypt(value[len(self.PREFIX):].encode("ascii")).decode("utf-8")
        except InvalidToken as exc:
            raise RuntimeError("La clave de cifrado no coincide con los datos almacenados.") from exc


secret_cipher = SecretCipher()
