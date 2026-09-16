import uvicorn
import os
import sys

# Asegurar que el directorio raíz esté en sys.path
sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from backend.main import app

if __name__ == "__main__":
    # Hugging Face Spaces asigna el puerto 7860 por defecto
    port = int(os.environ.get("PORT", 7860))
    uvicorn.run(app, host="0.0.0.0", port=port)
