# 📱 Yui para Android

La app tiene dos funciones:

1. 💬 **Chat con Yui**: abre la interfaz web del backend (texto, voz de Yui y recuerdos). Necesita internet.
2. 📵 **Filtro de llamadas**: rechaza llamadas de números que no están en tu agenda. Funciona sin internet.
   - **Llamadas normales (Android 10+)**: usa el filtro oficial del sistema (`CallScreeningService`). Rechaza al instante.
   - **Respaldo (Android 9, o si no concedes el rol de filtro)**: detecta la llamada y cuelga a los 5 segundos.
   - **WhatsApp**: si activas Yui en *Accesibilidad*, rechaza a los 5 segundos las llamadas de WhatsApp de números no guardados.
   - **Contactos bloqueados**: con **🚫 Bloqueados** eliges contactos de tu agenda cuyas llamadas también quieres rechazar. Android no le pasa al filtro oficial las llamadas de contactos, así que estas se cuelgan con el método de respaldo (suenan unos 5 segundos y requieren los permisos de Teléfono y Registro de llamadas).
   - Nunca rechaza números ocultos, ni nada si la agenda está vacía o falta el permiso de contactos.

## 🛠️ Compilar el APK

El APK se compila en GitHub Actions al hacer push a `master` o `main` (workflow `build_apk.yml`). También puedes abrir `android_companion` en Android Studio y pulsar **Run**.

## 🚀 Configuración en el teléfono

1. Abre **Yui SAO**, toca **⚙**, escribe la URL HTTPS de tu servidor y pulsa **Guardar**.
2. Inicia sesión en el chat con el `API_TOKEN` del servidor.
3. Toca **📞 Filtro: OFF** para activarlo y concede:
   - Contactos, Teléfono y Registro de llamadas.
   - **App de identificación de llamadas y spam** → elige **Yui SAO** (Android 10+).
4. Opcional: toca **🚫 Bloqueados** → **+ Agregar contacto** para rechazar también a contactos concretos. Toca uno de la lista para desbloquearlo.
5. Opcional, para WhatsApp: toca la línea de estado y activa **Yui SAO** en *Accesibilidad*.
