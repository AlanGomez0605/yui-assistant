# ☁️ Guía de Despliegue en la Nube 24/7 (Proyecto Yui)

Esta guía te explica cómo tener a Yui activa en internet **24/7 de forma 100% gratuita** en **Render.com** (o Railway), de modo que puedas usarla desde tu teléfono móvil en cualquier viaje sin necesidad de tener tu laptop encendida ni conectada.

---

## 🌟 Pasos para Despliegue Gratuito en Render.com

### 1. Subir tu proyecto a un repositorio de GitHub
Asegúrate de que tu código esté en un repositorio (público o privado) en tu cuenta de GitHub:
```bash
git add .
git commit -m "Fase 5: Integracion completa de Yui en la Nube y Companion Android"
git push origin main
```

### 2. Crear tu servicio en Render.com
1. Entra a [https://render.com](https://render.com) e inicia sesión con tu cuenta de GitHub.
2. Haz clic en **New +** y selecciona **Web Service** (o **Blueprint** usando el archivo `render.yaml`).
3. Conecta tu repositorio `ProyProp`.
4. Render detectará automáticamente la configuración de Python:
   - **Runtime:** `Python`
   - **Build Command:** `pip install -r requirements.txt`
   - **Start Command:** `uvicorn backend.main:app --host 0.0.0.0 --port $PORT`
   - **Instance Type:** `Free`

### 3. Configurar tus Variables de Entorno en Render
En la pestaña **Environment** de tu servicio en Render, agrega las siguientes variables (las mismas de tu archivo local `.env`):

| Clave (Key) | Valor (Value) |
|---|---|
| `GEMINI_API_KEY` | *(Tu clave de Google Gemini API)* |
| `MONGODB_URI` | `mongodb+srv://admin_user01:YOUR_PASSWORD@cluster0.ntawnnf.mongodb.net/?retryWrites=true&w=majority&appName=Cluster0` |
| `OWNER_NAME` | `Alan Jahir` |
| `OWNER_NICKNAME` | `Alan` |
| `OWNER_EMAIL` | `alanjahir305@gmail.com` |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` |

### 4. ¡Listo! Despliegue en Vivo
Haz clic en **Deploy Web Service**. En 1 a 2 minutos, Render te dará tu URL pública segura con HTTPS (ejemplo: `https://yui-mhcp0001.onrender.com`).

---

## 📱 Cómo Usar a Yui desde tu Teléfono Móvil

### Método 1: Como Aplicación Web Progresiva (PWA)
1. Abre tu navegador móvil (Chrome / Edge / Safari) en tu teléfono e ingresa a tu URL de Render: `https://yui-mhcp0001.onrender.com`
2. Toca el menú de opciones del navegador (los 3 puntos ⋮ arriba a la derecha).
3. Selecciona **"Instalar aplicación"** o **"Agregar a la pantalla principal"**.
4. ¡Yui se instalará como una app nativa en tu teléfono con su propio icono, pantalla completa y acceso a micrófono y audio!

### Método 2: Con la App Companion Android (Overlay Flotante + Filtro de Llamadas)
1. Abre la app `android_companion` en tu teléfono.
2. Pega tu URL de Render en el campo de texto y pulsa **Guardar**.
3. Activa la **Burbuja Flotante** para tener a Yui siempre superpuesta sobre cualquier aplicación y filtrando tus llamadas.
