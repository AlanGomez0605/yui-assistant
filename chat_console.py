import asyncio
import sys
import warnings

# Silenciar advertencias internas de librerías para una salida limpia
warnings.filterwarnings("ignore")

from backend.app.services.gemini_service import gemini_service
from backend.app.core.config import get_settings

# Asegurar codificación UTF-8 en terminal Windows
sys.stdout.reconfigure(encoding='utf-8')
sys.stdin.reconfigure(encoding='utf-8')
settings = get_settings()

async def main():
    print("\n" + "=" * 62)
    print(f"🌸  CANAL DIRECTO CON {settings.ASSISTANT_NAME.upper()} (MHCP-0001)")
    print(f"    Usuario: {settings.OWNER_NAME} ({settings.OWNER_NICKNAME})")
    print("    Escribe 'salir' para cerrar la sesión.")
    print("=" * 62 + "\n")

    if not gemini_service.is_configured():
        print("❌ Error: No se encontró una GEMINI_API_KEY válida en tu archivo .env")
        return

    history = []

    # Mensaje inicial de conexión
    print("🌸 Conectando con Yui...\n")
    welcome = await gemini_service.generate_reply(
        "¡Hola Yui! He abierto el canal directo de comunicación contigo.",
        history
    )
    print(f"🌸 Yui:\n{welcome}\n")
    history.append({"role": "user", "content": "¡Hola Yui! He abierto el canal directo de comunicación contigo."})
    history.append({"role": "assistant", "content": welcome})

    while True:
        try:
            user_input = input(f"💬 {settings.OWNER_NICKNAME}: ").strip()
            if not user_input:
                continue
            if user_input.lower() in ["salir", "exit", "quit", "adios", "adiós"]:
                farewell = await gemini_service.generate_reply("Yui, voy a cerrar la sesión por ahora. Nos vemos pronto.", history)
                print(f"\n🌸 Yui:\n{farewell}\n")
                break

            # Enviar a Yui
            print("\n🌸 Yui está pensando...")
            reply = await gemini_service.generate_reply(user_input, history)
            # Limpiar línea previa
            print(f"\r🌸 Yui:\n{reply}\n")

            # Guardar en el historial de la sesión
            history.append({"role": "user", "content": user_input})
            history.append({"role": "assistant", "content": reply})

        except KeyboardInterrupt:
            print("\n\n🌸 Yui: ¡Hasta luego Alan! Estaré aquí cuando me necesites.")
            break
        except Exception as e:
            print(f"\n❌ Error en la comunicación: {e}\n")

if __name__ == "__main__":
    asyncio.run(main())
