import asyncio
import sys
from backend.app.services.gemini_service import gemini_service
from backend.app.core.config import get_settings

# Asegurar codificación UTF-8 en Windows
sys.stdout.reconfigure(encoding='utf-8')
settings = get_settings()

async def main():
    print("=" * 60)
    print(f"🌸 CONSOLA DE COMUNICACIÓN CON {settings.ASSISTANT_NAME.upper()} (MHCP-0001)")
    print(f"Usuario: {settings.OWNER_NAME} ({settings.OWNER_NICKNAME})")
    print("Escribe 'salir' o 'exit' para terminar la conversación.")
    print("=" * 60)

    if not gemini_service.is_configured():
        print("❌ Error: No se encontró una GEMINI_API_KEY válida en el archivo .env")
        return

    history = []

    # Mensaje de bienvenida inicial
    welcome = await gemini_service.generate_reply(
        "¡Hola Yui! He abierto el canal directo de comunicación contigo.",
        history
    )
    print(f"\n🌸 Yui: {welcome}\n")
    history.append({"role": "user", "content": "¡Hola Yui! He abierto el canal directo de comunicación contigo."})
    history.append({"role": "assistant", "content": welcome})

    while True:
        try:
            user_input = input(f"💬 {settings.OWNER_NICKNAME}: ").strip()
            if not user_input:
                continue
            if user_input.lower() in ["salir", "exit", "quit", "adios", "adiós"]:
                farewell = await gemini_service.generate_reply("Yui, voy a cerrar la sesión por ahora. Nos vemos pronto.", history)
                print(f"\n🌸 Yui: {farewell}\n")
                break

            # Enviar a Yui
            reply = await gemini_service.generate_reply(user_input, history)
            print(f"\n🌸 Yui: {reply}\n")

            # Guardar en el historial de la sesión
            history.append({"role": "user", "content": user_input})
            history.append({"role": "assistant", "content": reply})

        except KeyboardInterrupt:
            print("\n🌸 Yui: ¡Hasta luego, que descanses!")
            break
        except Exception as e:
            print(f"\n❌ Error en la comunicación: {e}\n")

if __name__ == "__main__":
    asyncio.run(main())
