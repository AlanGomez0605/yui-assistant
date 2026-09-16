from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from backend.app.core.config import get_settings
from backend.app.api.endpoints import router as api_router

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

@app.get("/")
async def root():
    return {
        "message": f"🌸 Núcleo de {settings.ASSISTANT_NAME} en línea.",
        "owner": settings.OWNER_NAME,
        "docs": "/docs"
    }

if __name__ == "__main__":
    import uvicorn
    print(f"Iniciando el servidor de {settings.ASSISTANT_NAME} en http://{settings.HOST}:{settings.PORT}")
    uvicorn.run("backend.main:app", host=settings.HOST, port=settings.PORT, reload=settings.DEBUG)
