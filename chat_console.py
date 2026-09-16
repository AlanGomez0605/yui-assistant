import asyncio
import sys
import warnings

# Silenciar advertencias de consola
warnings.filterwarnings("ignore")

from backend.app.services.gemini_service import gemini_service
from backend.app.core.config import get_settings

# Asegurar codificación UTF-8 en terminal Windows
sys.stdout.reconfigure(encoding='utf-8')
sys.stdin.reconfigure(encoding='utf-8')
settings = get_settings()

async def main():
    print("\n" + "═" * 65)
    print(f"  🌸  PROYECTO YUI - TERMINAL DE ENLACE DIRECTO (MHCP-0001)")
    print(f"  👤  Usuario activo: {settings.OWNER_NAME} ({settings.OWNER_NICKNAME})")
    print("  💡  Escribe 'salir' o 'exit' para cerrar la sesión.")
    print("═" * 65 + "\n")

    if not gemini_service.is_configured():
        print("❌ Error: No se encontró una GEMINI_API_KEY válida en tu archivo .env")
        return

    history = []

    print("🌸 Estableciendo enlace mental con Yui...\n")
    sys.stdout.write("🌸 Yui:\n")
    sys.stdout.flush()

    initial_greeting = []
    async for chunk in gemini_service.generate_reply_stream(
        "¡Hola Yui! He abierto el canal directo de comunicación contigo.",
        history,
        persist_session=None  # No persistir saludo de enlace
    ):
        sys.stdout.write(chunk)
        sys.stdout.flush()
        initial_greeting.append(chunk)

    full_welcome = "".join(initial_greeting)
    print("\n\n" + "─" * 65)
    history.append({"role": "user", "content": "¡Hola Yui! He abierto el canal directo de comunicación contigo."})
    history.append({"role": "assistant", "content": full_welcome})

    while True:
        try:
            user_input = input(f"\n💬 {settings.OWNER_NICKNAME}: ").strip()
            if not user_input:
                continue

            if user_input.lower() in ["salir", "exit", "quit", "adios", "adiós"]:
                print("\n🌸 Yui:\n", end="", flush=True)
                async for chunk in gemini_service.generate_reply_stream(
                    "Yui, voy a cerrar la sesión por ahora. Nos vemos pronto.",
                    history,
                    persist_session=None
                ):
                    sys.stdout.write(chunk)
                    sys.stdout.flush()
                
                # Esperar a que se guarden todos los recuerdos en segundo plano antes de salir
                print("\n\n🌸 Guardando recuerdos en la base de datos...", end="", flush=True)
                await gemini_service.wait_for_pending_tasks(timeout=3.0)
                print(" ¡Listo!")
                print("═" * 65)
                print("🌸 Enlace cerrado. ¡Que tengas un excelente día!")
                break

            print("\n🌸 Yui:\n", end="", flush=True)
            reply_chunks = []
            async for chunk in gemini_service.generate_reply_stream(user_input, history, persist_session="console_session"):
                sys.stdout.write(chunk)
                sys.stdout.flush()
                reply_chunks.append(chunk)

            full_reply = "".join(reply_chunks)
            print("\n\n" + "─" * 65)

            # Guardar en el historial de la sesión
            history.append({"role": "user", "content": user_input})
            history.append({"role": "assistant", "content": full_reply})

        except (KeyboardInterrupt, EOFError):
            print("\n\n🌸 Guardando recuerdos antes de salir...", end="", flush=True)
            await gemini_service.wait_for_pending_tasks(timeout=2.0)
            print(" ¡Listo!")
            print("🌸 Yui: ¡Hasta luego Alan! Estaré aquí esperándote cuando me necesites.")
            break
        except Exception as e:
            print(f"\n❌ Error en la comunicación: {e}\n")

if __name__ == "__main__":
    asyncio.run(main())
