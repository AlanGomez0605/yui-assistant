from pydantic_settings import BaseSettings
from functools import lru_cache
import os

class Settings(BaseSettings):
    PROJECT_NAME: str = "Proyecto Yui"
    VERSION: str = "0.1.0"
    
    # Gemini API
    GEMINI_API_KEY: str = ""
    
    # Servidor
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    DEBUG: bool = True
    
    # Perfil
    OWNER_NAME: str = "Alan Jahir"
    OWNER_NICKNAME: str = "Alan"
    ASSISTANT_NAME: str = "Yui"
    
    # Base de Datos
    DATABASE_URL: str = "sqlite+aiosqlite:///./data/yui_memory.db"
    
    # Voz
    TTS_VOICE: str = "es-MX-DaliaNeural"
    TTS_RATE: str = "+0%"
    TTS_PITCH: str = "+5Hz"

    class Config:
        env_file = ".env"
        extra = "ignore"

@lru_cache()
def get_settings() -> Settings:
    return Settings()
