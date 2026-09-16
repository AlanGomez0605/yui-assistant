import os
import sys
import uvicorn
import gradio as gr

# Asegurar que el directorio raíz esté en sys.path
sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from backend.main import app as fastapi_app

# Puerto estándar para Hugging Face Spaces (7860)
PORT = int(os.environ.get("PORT", 7860))

# Montar FastAPI como aplicación principal
if __name__ == "__main__":
    uvicorn.run(fastapi_app, host="0.0.0.0", port=PORT)
