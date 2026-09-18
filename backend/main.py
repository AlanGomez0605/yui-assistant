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
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

try:
    from backend.app.core.config import get_settings
    from backend.app.api.endpoints import router as api_router
    from backend.app.core.mongodb import mongodb_manager
    from backend.app.core.mongodb import DatabaseUnavailableError
    from backend.app.services.proactive_service import proactive_service
    from backend.app.core.security import (
        SESSION_COOKIE_NAME,
        authentication_configured,
        create_session_value,
        request_is_authenticated,
        token_is_valid,
    )
except ImportError:
    from app.core.config import get_settings
    from app.api.endpoints import router as api_router
    from app.core.mongodb import mongodb_manager
    from app.core.mongodb import DatabaseUnavailableError
    from app.services.proactive_service import proactive_service
    from app.core.security import (
        SESSION_COOKIE_NAME,
        authentication_configured,
        create_session_value,
        request_is_authenticated,
        token_is_valid,
    )

settings = get_settings()

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Inicializar MongoDB Atlas antes de recibir solicitudes
    print("[YUI] Conectando con MongoDB Atlas en la nube...")
    try:
        connected = await mongodb_manager.connect()
        if connected:
            print("[YUI] MongoDB Atlas conectado.")
            proactive_service.start()
        else:
            print("[YUI] MongoDB no configurado; las funciones persistentes no estan disponibles.")
    except Exception as e:
        print(f"[YUI] Aviso al conectar MongoDB Atlas: {e}")
    yield
    proactive_service.stop()
    await mongodb_manager.close()
    print("[YUI] Servidor finalizado.")



app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="Backend y Núcleo Cognitivo de Yui (MHCP-0001)",
    debug=settings.DEBUG,
    lifespan=lifespan
)


@app.exception_handler(DatabaseUnavailableError)
async def database_unavailable_handler(_request: Request, exc: DatabaseUnavailableError):
    return JSONResponse(status_code=503, content={"detail": str(exc)})

if settings.cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type"],
    )


PUBLIC_API_PATHS = {"/api/health", "/api/auth/login", "/api/auth/logout", "/api/auth/status"}


@app.middleware("http")
async def secure_api_and_responses(request: Request, call_next):
    if request.url.path.startswith("/api/") and request.url.path not in PUBLIC_API_PATHS:
        if not authentication_configured(settings):
            if not settings.DEBUG:
                return JSONResponse(
                    status_code=503,
                    content={"detail": "API_TOKEN no esta configurado en el servidor."},
                )
        elif not request_is_authenticated(request, settings):
            return JSONResponse(status_code=401, content={"detail": "Autenticacion requerida."})

    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Permissions-Policy"] = "camera=(), geolocation=(), payment=()"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
        "font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; media-src 'self' blob: data:; "
        "connect-src 'self' ws: wss:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
    )
    return response


class LoginRequest(BaseModel):
    token: str


@app.get("/api/auth/status")
async def auth_status(request: Request):
    configured = authentication_configured(settings)
    authenticated = (settings.DEBUG and not configured) or request_is_authenticated(request, settings)
    return {"configured": configured, "authenticated": authenticated}


@app.post("/api/auth/login")
async def auth_login(request: Request, credentials: LoginRequest):
    if not authentication_configured(settings):
        return JSONResponse(status_code=503, content={"detail": "API_TOKEN no configurado."})
    if not token_is_valid(credentials.token, settings):
        return JSONResponse(status_code=401, content={"detail": "Token incorrecto."})

    response = JSONResponse({"status": "authenticated"})
    response.set_cookie(
        SESSION_COOKIE_NAME,
        create_session_value(settings),
        httponly=True,
        secure=request.url.scheme == "https",
        samesite="strict",
        max_age=60 * 60 * 24 * 30,
        path="/",
    )
    return response


@app.post("/api/auth/logout")
async def auth_logout():
    response = JSONResponse({"status": "logged_out"})
    response.delete_cookie(SESSION_COOKIE_NAME, path="/")
    return response

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

@app.get("/index.html", include_in_schema=False)
async def serve_index_alias():
    return await serve_index()

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
