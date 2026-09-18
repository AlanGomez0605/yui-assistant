from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from functools import lru_cache

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    PROJECT_NAME: str = "Proyecto Yui"
    VERSION: str = "0.1.0"
    
    # Gemini API
    GEMINI_API_KEY: str = ""
    GEMINI_MODEL: str = "gemini-3.5-flash-lite"
    
    # Servidor
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    DEBUG: bool = False
    API_TOKEN: str = ""
    DATA_ENCRYPTION_KEY: str = ""
    CORS_ORIGINS: str = ""
    
    # Perfil
    OWNER_NAME: str = "Alan Jahir"
    OWNER_NICKNAME: str = "Alan"
    OWNER_EMAIL: str = ""
    OWNER_TIMEZONE: str = "America/Mexico_City"
    ASSISTANT_NAME: str = "Yui"
    
    # Base de Datos (Nube NoSQL MongoDB & SQLite fallback)
    MONGODB_URI: str = ""
    MONGODB_DB_NAME: str = "yui_cloud_memory"
    DATABASE_URL: str = "sqlite+aiosqlite:///./data/yui_memory.db"
    
    # Voz
    TTS_VOICE: str = "es-MX-DaliaNeural"
    TTS_RATE: str = "+0%"
    TTS_PITCH: str = "+5Hz"

    @field_validator("DEBUG", mode="before")
    @classmethod
    def parse_debug_mode(cls, value):
        """Parse common boolean and environment names without breaking startup."""
        if isinstance(value, str):
            normalized = value.strip().lower()
            if normalized in {"release", "production", "prod", "false", "0", "no", "off"}:
                return False
            if normalized in {"development", "develop", "dev", "true", "1", "yes", "on"}:
                return True
        return value

    @property
    def cors_origins(self) -> list[str]:
        return [origin.strip() for origin in self.CORS_ORIGINS.split(",") if origin.strip()]

@lru_cache()
def get_settings() -> Settings:
    return Settings()
