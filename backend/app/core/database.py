import os
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession, async_sessionmaker
from sqlalchemy.orm import declarative_base
from .config import get_settings

settings = get_settings()

# Asegurar que el directorio data exista
os.makedirs("./data", exist_ok=True)

DATABASE_URL = settings.DATABASE_URL

engine = create_async_engine(
    DATABASE_URL,
    echo=False,
    future=True
)

AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False
)

Base = declarative_base()

async def get_db():
    """Generador de sesiones de base de datos asíncronas."""
    async with AsyncSessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()

async def init_db():
    """Crea todas las tablas en la base de datos si no existen."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
