# 🌸 Proyecto Yui (MHCP-0001)

Asistente de inteligencia artificial personal, multi-dispositivo y de largo plazo, inspirada en **Yui** (*Sword Art Online*).

---

## 🧭 Características Principales
* 🧠 **Cerebro Unificado**: Conexión compartida en tiempo real entre tu Laptop y Teléfono Móvil.
* 🎙️ **Autenticación por Voz**: Reconocimiento de locutor (*Voiceprint / Speaker ID*) que responde solo a voces autorizadas.
* 💾 **Memoria Persistente**: Base de datos relacional y vectorial para recordar conversaciones, gustos, notas y hábitos a largo plazo.
* 📞 **Manejo Inteligente de Llamadas**: Detección de llamadas entrantes desatendidas (30-40s), respuesta con mensaje de cortesía para contactos registrados y filtro de números desconocidos.
* 🗡️ **Interfaz Visual SAO**: HUD interactivo con estética de Sword Art Online y avatar visual.
* ⚡ **Proactividad**: Tareas programadas, recordatorios y atención activa a tu bienestar.

---

## 🛠️ Estructura del Repositorio

```text
ProyProp/
├── backend/                  # Núcleo en Python (FastAPI)
│   ├── app/
│   │   ├── api/              # Rutas y WebSockets
│   │   ├── core/             # Configuración y prompts de personalidad
│   │   ├── models/           # Modelos de base de datos y esquemas Pydantic
│   │   └── services/         # Motores de IA (Gemini, Memoria, Voz, etc.)
│   └── main.py               # Punto de entrada del servidor
├── frontend/                 # Interfaz visual Web/PWA (SAO Theme)
├── data/                     # Base de datos SQLite y memoria persistente
├── assets/                   # Avatares, sonidos e imágenes de interfaz
├── .env.example              # Plantilla de variables de entorno
├── requirements.txt          # Dependencias de Python
└── README.md
```

---

## 🚀 Inicio Rápido

1. **Instalar dependencias**:
   ```bash
   pip install -r requirements.txt
   ```
2. **Configurar tu API Key de Gemini**:
   - Copia `.env.example` a `.env`
   - Agrega tu clave en `GEMINI_API_KEY` (obtenida gratis en [Google AI Studio](https://aistudio.google.com/))
3. **Iniciar el servidor**:
   ```bash
   python -m uvicorn backend.main:app --reload
   ```
4. **Visitar la documentación**:
   - Abre en tu navegador `http://localhost:8000/docs`
