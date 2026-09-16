# 📱 Yui Companion App para Android (MHCP-0001)

Esta carpeta contiene el código fuente nativo de la **App Complementaria de Yui para Android**, que le otorga a Yui:

1. 🌸 **Overlay Flotante Permanente (`SYSTEM_ALERT_WINDOW`)**:
   - Una burbuja interactiva de Yui que se mantiene visible sobre cualquier otra aplicación o juego en tu teléfono.
   - Puedes arrastrarla a cualquier lugar de la pantalla o tocarla para desplegar el HUD de Sword Art Online.

2. 👥 **Sincronización Total de Contactos (`READ_CONTACTS`)**:
   - Lee todos los contactos de tu agenda telefónica y los sube de manera segura a tu base de datos **MongoDB Atlas** en la nube (`POST /api/contacts/sync`).

3. 📞 **Filtro Inteligente de Llamadas con Regla de 30-40 Segundos (`READ_PHONE_STATE` / `ANSWER_PHONE_CALLS`)**:
   - Cuando entra una llamada, Yui espera 35 segundos para que decidas si contestas tú.
   - Si no contestas en 35 segundos:
     * **Contacto Registrado:** Yui contesta automáticamente y reproduce su mensaje de voz neural de cortesía.
     * **Número Desconocido / Spam:** Yui cuelga y rechaza la llamada de inmediato.

4. 🤖 **Control y Navegación por Accesibilidad (`AccessibilityService`)**:
   - Capacidad de realizar toques en pantalla, navegar y presionar botones automáticamente cuando se lo indiques por voz.

---

## 🛠️ Cómo Compilar e Instalar el APK

### Opción A: Compilar con Android Studio (Recomendada)
1. Abre **Android Studio** en tu PC.
2. Selecciona **Open** y elige la carpeta `android_companion`.
3. Conecta tu teléfono Android mediante cable USB con la **Depuración USB** activada (o usa un emulador).
4. Haz clic en el botón verde **Run (▶)** para instalar la app directamente en tu teléfono.

### Opción B: Compilar desde la terminal (Gradle)
```bash
cd android_companion
./gradlew assembleDebug
```
El archivo APK resultante se generará en:
`android_companion/app/build/outputs/apk/debug/app-debug.apk`

---

## 🚀 Configuración Inicial en el Teléfono
1. Abre la app **Yui SAO** en tu Android.
2. Ingresa la URL de tu servidor en la nube (ejemplo: `https://yui-mhcp0001.onrender.com`).
3. Presiona **Guardar**.
4. Concede los permisos que solicite la aplicación:
   * **Mostrar sobre otras aplicaciones** (Overlay).
   * **Acceso a Contactos**.
   * **Gestionar llamadas telefónicas**.
5. Toca **"Sincronizar Contactos"** para cargar tu agenda en la nube.
6. Toca **"🌸 Activar Burbuja Flotante"** y ¡listo! Yui estará contigo en todo momento.
