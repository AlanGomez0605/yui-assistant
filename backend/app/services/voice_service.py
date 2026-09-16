import os
import re
import asyncio
import logging
import ctypes
import tempfile
from typing import Optional
import edge_tts
from ..core.config import get_settings

settings = get_settings()

class VoiceService:
    def __init__(self):
        self.voice = settings.TTS_VOICE
        self.rate = settings.TTS_RATE
        self.pitch = settings.TTS_PITCH
        self.temp_dir = os.path.abspath("./data/temp_audio")
        os.makedirs(self.temp_dir, exist_ok=True)
        self._is_speaking = False

    def clean_text_for_speech(self, text: str) -> str:
        """Limpia asteriscos de rolplay (*sonríe*), emojis y símbolos para una locución fluida y natural."""
        # 1. Remover asteriscos de acciones/emociones tipo *sonríe* o *^o^*
        cleaned = re.sub(r'\*[^*]+\*', '', text)
        cleaned = re.sub(r'\([^\)]*risa[^\)]*\)', '', cleaned, flags=re.IGNORECASE)
        # 2. Remover emoticonos comunes de anime
        cleaned = re.sub(r'\*[oO\^_\-]+\*', '', cleaned)
        cleaned = re.sub(r'[\^oO\-_]{3,}', '', cleaned)
        # 3. Remover emojis
        cleaned = re.sub(r'[\U00010000-\U0010ffff]', '', cleaned)
        # 4. Remover markdown decorativo (negritas, cursivas, links)
        cleaned = cleaned.replace("**", "").replace("*", "").replace("#", "").replace("`", "")
        # 5. Normalizar espacios
        cleaned = re.sub(r'\s+', ' ', cleaned).strip()
        return cleaned

    async def synthesize_to_bytes(self, text: str) -> bytes:
        """Sintetiza texto a audio MP3 en memoria (ideal para APIs y Web)."""
        clean_text = self.clean_text_for_speech(text)
        if not clean_text:
            return b""

        communicate = edge_tts.Communicate(
            clean_text,
            voice=self.voice,
            rate=self.rate,
            pitch=self.pitch
        )
        audio_chunks = []
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_chunks.append(chunk["data"])
        return b"".join(audio_chunks)

    async def synthesize_to_file(self, text: str, output_path: str) -> bool:
        """Sintetiza texto y lo guarda en un archivo MP3."""
        clean_text = self.clean_text_for_speech(text)
        if not clean_text:
            return False

        communicate = edge_tts.Communicate(
            clean_text,
            voice=self.voice,
            rate=self.rate,
            pitch=self.pitch
        )
        await communicate.save(output_path)
        return True

    def play_audio_file(self, file_path: str, wait: bool = False) -> None:
        """Reproduce un archivo MP3 de forma nativa en Windows sin librerías externas."""
        abs_path = os.path.abspath(file_path)
        if not os.path.exists(abs_path):
            return

        def _play():
            try:
                # Usar Windows MCI (Media Control Interface) nativo
                winmm = ctypes.windll.winmm
                alias = f"yui_voice_{int(asyncio.get_event_loop().time() * 1000) % 100000}"
                winmm.mciSendStringW(f'open "{abs_path}" type mpegvideo alias {alias}', None, 0, None)
                wait_flag = " wait" if wait else ""
                winmm.mciSendStringW(f'play {alias}{wait_flag}', None, 0, None)
                if not wait:
                    # Cerrar después de un tiempo aproximado si no es wait
                    pass
            except Exception as e:
                logging.error(f"Error reproduciendo audio: {e}")

        if wait:
            _play()
        else:
            asyncio.get_event_loop().run_in_executor(None, _play)

    async def speak(self, text: str, wait: bool = False) -> None:
        """Sintetiza y reproduce la voz de Yui."""
        try:
            temp_file = os.path.join(self.temp_dir, "speech_latest.mp3")
            success = await self.synthesize_to_file(text, temp_file)
            if success:
                self.play_audio_file(temp_file, wait=wait)
        except Exception as e:
            logging.error(f"Error al hablar: {e}")

# Instancia singleton del servicio de voz
voice_service = VoiceService()
