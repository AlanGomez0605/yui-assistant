FROM python:3.11-slim

WORKDIR /app

# Instalar dependencias del sistema necesarias para audio y certificados
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt motor pymongo[srv]

COPY . .

# Crear carpeta de datos con permisos
RUN mkdir -p /app/data && chmod -R 777 /app

# Puerto estándar para Hugging Face Spaces (7860) y compatibilidad con $PORT
ENV PORT=7860
ENV HOST=0.0.0.0
ENV PYTHONUNBUFFERED=1

EXPOSE 7860

CMD ["sh", "-c", "uvicorn backend.main:app --host 0.0.0.0 --port ${PORT:-7860}"]
