import os
import sys
import uvicorn

# Asegurar que el directorio raíz esté en sys.path
sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from backend.main import app

if __name__ == "__main__":
    # Obtener el puerto asignado por Railway/Render/Cloud o usar 8000 por defecto
    raw_port = os.environ.get("PORT", "8000")
    try:
        port = int(raw_port)
    except ValueError:
        port = 8000

    host = os.environ.get("HOST", "0.0.0.0")
    print(f"\n🌸 [YUI CLOUD] Servidor iniciando en {host}:{port}...")
    uvicorn.run(app, host=host, port=port)
