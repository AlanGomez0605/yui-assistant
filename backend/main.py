import os
import sys

# Asegurar UTF-8 en Windows para evitar errores de codificación en consola
try:
    if sys.stdout:
        sys.stdout.reconfigure(encoding='utf-8')
    if sys.stderr:
        sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass

from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse

try:
    from backend.app.core.config import get_settings
    from backend.app.api.endpoints import router as api_router
    from backend.app.core.mongodb import mongodb_manager
    from backend.app.core.database import init_db
    from backend.app.services.proactive_service import proactive_service
except ImportError:
    from app.core.config import get_settings
    from app.api.endpoints import router as api_router
    from app.core.mongodb import mongodb_manager
    from app.core.database import init_db
    from app.services.proactive_service import proactive_service

settings = get_settings()

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Inicializar bases de datos antes de recibir solicitudes
    print("[YUI] Conectando con MongoDB Atlas y SQLite local...")
    try:
        await mongodb_manager.connect()
        await init_db()
        print("[YUI] Bases de datos listas y sincronizadas.")
        # Iniciar monitoreo autónomo en segundo plano
        proactive_service.start()
    except Exception as e:
        print(f"[YUI] Aviso al iniciar bases de datos: {e}")
    yield
    proactive_service.stop()
    print("[YUI] Servidor finalizado.")


app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="Backend y Núcleo Cognitivo de Yui (MHCP-0001)",
    debug=settings.DEBUG,
    lifespan=lifespan
)

# Permitir CORS para conexiones desde frontend web / móvil / local
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 1. Incluir rutas de la API primero
app.include_router(api_router, prefix="/api")

# 2. Servir recursos estáticos (CSS, JS, Assets)
frontend_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "frontend"))
styles_dir = os.path.join(frontend_dir, "styles")
scripts_dir = os.path.join(frontend_dir, "scripts")
assets_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "assets"))

if os.path.exists(styles_dir):
    app.mount("/styles", StaticFiles(directory=styles_dir), name="styles")
if os.path.exists(scripts_dir):
    app.mount("/scripts", StaticFiles(directory=scripts_dir), name="scripts")
if os.path.exists(assets_dir):
    app.mount("/assets", StaticFiles(directory=assets_dir), name="assets")

# 3. Ruta principal de la Interfaz SAO y PWA
@app.get("/")
async def serve_index():
    index_file = os.path.join(frontend_dir, "index.html")
    if os.path.exists(index_file):
        return FileResponse(index_file)
    return {"message": f"Núcleo de {settings.ASSISTANT_NAME} en línea."}

@app.get("/manifest.json")
async def serve_manifest():
    manifest_file = os.path.join(frontend_dir, "manifest.json")
    if os.path.exists(manifest_file):
        return FileResponse(manifest_file, media_type="application/json")
    return {}

@app.get("/sw.js")
async def serve_sw():
    sw_file = os.path.join(frontend_dir, "sw.js")
    if os.path.exists(sw_file):
        return FileResponse(sw_file, media_type="application/javascript")
    return {}


if __name__ == "__main__":
    import uvicorn
    print(f"\n[YUI] Servidor de Yui activo en http://localhost:{settings.PORT}")
    uvicorn.run("backend.main:app", host=settings.HOST, port=settings.PORT, reload=settings.DEBUG)
