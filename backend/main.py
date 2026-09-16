import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

try:
    from backend.app.core.config import get_settings
    from backend.app.api.endpoints import router as api_router
except ImportError:
    from app.core.config import get_settings
    from app.api.endpoints import router as api_router

settings = get_settings()

app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="Backend y Núcleo Cognitivo de Yui (MHCP-0001)",
    debug=settings.DEBUG
)

# Permitir CORS para conexiones desde frontend web / móvil / local
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Incluir rutas API
app.include_router(api_router, prefix="/api")

# Montar interfaz web estilo Sword Art Online
frontend_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "frontend"))
if os.path.exists(frontend_dir):
    app.mount("/", StaticFiles(directory=frontend_dir, html=True), name="frontend")

if __name__ == "__main__":
    import uvicorn
    print(f"\n🌸 Iniciando servidor de {settings.ASSISTANT_NAME} en http://localhost:{settings.PORT}")
    uvicorn.run("backend.main:app", host=settings.HOST, port=settings.PORT, reload=settings.DEBUG)
