import datetime
from sqlalchemy import Column, Integer, String, Text, Boolean, DateTime
from ..core.database import Base

class UserProfile(Base):
    __tablename__ = "user_profiles"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), default="Alan Jahir")
    nickname = Column(String(50), default="Alan")
    timezone = Column(String(50), default="America/Mexico_City")
    preferences = Column(Text, default="{}")  # Guardado en JSON
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.datetime.utcnow, onupdate=datetime.datetime.utcnow)

class Memory(Base):
    """Hechos permanentes que Yui aprende sobre Alan (gustos, lugares, hábitos, personas)."""
    __tablename__ = "memories"

    id = Column(Integer, primary_key=True, index=True)
    category = Column(String(50), index=True)  # 'preference', 'place', 'habit', 'relationship', 'fact'
    content = Column(Text, nullable=False)     # ej. "A Alan le gusta viajar a Atlapexco, Hidalgo."
    importance = Column(Integer, default=3)    # Escala 1 (menor) a 5 (vital)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    last_recalled_at = Column(DateTime, nullable=True)

class Reminder(Base):
    """Recordatorios y tareas que Alan le pide a Yui vigilar."""
    __tablename__ = "reminders"

    id = Column(Integer, primary_key=True, index=True)
    title = Column(String(200), nullable=False)   # ej. "Viaje a Atlapexco"
    description = Column(Text, nullable=True)      # Detalles, notas de equipaje, etc.
    due_datetime = Column(String(100), nullable=False)  # Fecha/hora en formato ISO o texto normalizado
    is_completed = Column(Boolean, default=False, index=True)
    notified = Column(Boolean, default=False)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

class ConversationMessage(Base):
    """Historial persistente de mensajes entre Alan y Yui."""
    __tablename__ = "conversation_messages"

    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(String(50), index=True, default="default")
    role = Column(String(20), nullable=False)      # 'user' o 'assistant'
    content = Column(Text, nullable=False)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

class AuthorizedVoice(Base):
    """Voces autorizadas para la autenticación por voz (Fase 3)."""
    __tablename__ = "authorized_voices"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)      # ej. "Alan (Voz Principal)"
    is_active = Column(Boolean, default=True)
    voiceprint_path = Column(String(255), nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
