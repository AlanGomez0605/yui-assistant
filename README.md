# Proyecto Yui

Asistente personal con dos funciones: **chat con Yui** (backend FastAPI + interfaz web/PWA) y **filtro de llamadas** en la app Android.

## Funciones

- Chat con Gemini, por texto o voz (voz neural edge-tts y dictado del navegador).
- Memoria persistente en MongoDB: Yui recuerda datos y conversaciones pasadas. Si MongoDB no está disponible, el chat sigue funcionando sin memoria.
- Filtro de llamadas Android: rechaza números que no están en la agenda (llamadas normales y, opcionalmente, de WhatsApp). Ver [android_companion/README.md](android_companion/README.md).
- Autenticación por token y sesión web HttpOnly.

## Límites importantes

- El chat necesita internet: la IA corre en el servidor. Sin conexión la interfaz avisa y conserva el mensaje escrito.
- El filtro de llamadas solo actúa cuando el usuario lo activa. En Android 10+ requiere aceptar a Yui como "App de identificación de llamadas y spam"; sin ese rol usa un modo de respaldo que necesita los permisos de registro de llamadas y de teléfono. Números ocultos y agenda vacía nunca se rechazan. No contesta llamadas.
- Yui no crea recordatorios, alarmas ni eventos, no envía mensajes y no accede a contactos ni cuentas.

## Inicio local

```powershell
Copy-Item .env.example .env
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe app.py
```

Configura como mínimo `GEMINI_API_KEY`, `MONGODB_URI` y `API_TOKEN`. Abre `http://localhost:8000` y usa el mismo `API_TOKEN` en la pantalla de acceso.

## Pruebas

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
node --check frontend\scripts\app.js
```

La compilación Android se ejecuta en GitHub Actions. El directorio local no incluye binarios del Gradle Wrapper.

Consulta [DEPLOYMENT.md](DEPLOYMENT.md) para desplegar de forma segura.
