# Proyecto Yui

Asistente personal con backend FastAPI, interfaz web/PWA y companion Android.

## Funciones implementadas

- Chat con Gemini y memoria persistente en MongoDB.
- Recordatorios con zona horaria, WebSocket y sincronización de alarmas Android.
- Agenda sincronizada, interfaz de voz/TTS y controles móviles opcionales.
- Autenticación por token, sesión web HttpOnly, cifrado de credenciales y validación de entradas.

## Límites importantes

- Los perfiles de voz son metadatos; no hay identificación biométrica de locutor.
- El registro de una cuenta Google no activa Gmail, Drive ni Calendar por sí solo; falta un flujo OAuth funcional para esos servicios.
- El filtro de llamadas Android solo intenta rechazar números desconocidos cuando el usuario lo habilita y concede los permisos. No contesta llamadas.
- Los recordatorios en vivo requieren el backend activo y un cliente conectado; Android conserva alarmas que ya haya sincronizado.

## Inicio local

```powershell
Copy-Item .env.example .env
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe app.py
```

Configura como mínimo `GEMINI_API_KEY`, `MONGODB_URI`, `API_TOKEN` y `DATA_ENCRYPTION_KEY`. Abre `http://localhost:8000` y usa el mismo `API_TOKEN` en la pantalla de acceso.

## Pruebas

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
node --check frontend\scripts\app.js
```

La compilación Android se ejecuta en GitHub Actions. El directorio local no incluye binarios del Gradle Wrapper.

Consulta [DEPLOYMENT.md](DEPLOYMENT.md) para desplegar de forma segura.
