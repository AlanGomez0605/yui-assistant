# Despliegue seguro

El proyecto puede desplegarse en Render o Railway. Los planes, precios y suspensión por inactividad dependen del proveedor; compruébalos antes de asumir operación 24/7.

## Variables obligatorias

Configura secretos distintos y aleatorios en el panel del proveedor. No los guardes en Git.

| Variable | Uso |
|---|---|
| `GEMINI_API_KEY` | Acceso a Gemini |
| `GEMINI_MODEL` | Modelo configurado |
| `MONGODB_URI` | MongoDB Atlas |
| `API_TOKEN` | Protege API, WebSocket y acceso web |
| `DATA_ENCRYPTION_KEY` | Cifra credenciales almacenadas; no debe cambiarse sin migrarlas |
| `OWNER_TIMEZONE` | Zona IANA, por ejemplo `America/Mexico_City` |
| `CORS_ORIGINS` | Orígenes web externos permitidos, separados por coma; puede quedar vacío |
| `DEBUG` | Debe ser `false` en producción |

Genera `API_TOKEN` y `DATA_ENCRYPTION_KEY` como valores independientes de al menos 32 bytes aleatorios. Si omites `API_TOKEN` con `DEBUG=false`, las rutas privadas devolverán `503` por seguridad.

## Render

1. Conecta el repositorio y usa `render.yaml`.
2. Añade los secretos marcados como `sync: false`.
3. Despliega y comprueba `GET /api/health`.
4. Abre la URL HTTPS, introduce `API_TOKEN` y valida chat, memoria y recordatorios.

## Railway

El proyecto usa `python app.py` y `/api/health` como health check. Añade las mismas variables en el servicio Railway.

## Android

En ajustes de la app introduce exclusivamente la URL HTTPS y el mismo `API_TOKEN`. Concede solo los permisos de las funciones que quieras usar. La app no permite backend HTTP sin cifrar y excluye el token de copias de seguridad.

## Rotación de secretos

- `API_TOKEN`: cámbialo en servidor y dispositivos; las sesiones web anteriores dejan de ser válidas.
- `DATA_ENCRYPTION_KEY`: no la cambies directamente si existen credenciales cifradas. Descifra o migra esos registros primero.
