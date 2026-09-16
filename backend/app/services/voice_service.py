import os
import re
import asyncio
import logging
import subprocess
from typing import Optional
import edge_tts
from ..core.config import get_settings

settings = get_settings()

class VoiceService:
    def __init__(self):
        self.voice = settings.TTS_VOICE
        self.rate = "+18%"  # Velocidad dinámica y ágil
        self.pitch = "+2Hz"
        self.temp_dir = os.path.abspath("./data/temp_audio")
        self.ps_script = os.path.abspath(os.path.join(os.path.dirname(__file__), "play_audio.ps1"))
        os.makedirs(self.temp_dir, exist_ok=True)
        self._counter = 0
        self._current_process = None

        # Compilar filtro exhaustivo de todos los rangos Unicode de emojis y símbolos
        self._emoji_regex = re.compile(
            r'['
            r'\U00010000-\U0010FFFF'  # Emojis suplementarios, rostros, objetos, animales, banderas
            r'\u2600-\u27BF'          # Símbolos misceláneos, corazones, destellos, dingbats
            r'\u2300-\u23FF'          # Símbolos técnicos
            r'\u2B50\u2B55\uFE0F\u200D\u200C'  # Estrellas y modificadores de emoji
            r']+'
        )

    def clean_text_for_speech(self, text: str) -> str:
        """Limpia todos los emojis, símbolos, emoticonos y rolplay para locución pura y natural."""
        # 1. Eliminar descripciones entre paréntesis con asteriscos tipo *(Doy un brinquito...)* o (sonríe)
        cleaned = re.sub(r'\*\([^)]*\)\*', '', text)
        cleaned = re.sub(r'\([^)]*(?:sonríe|ojitos|mira|brinquito|manita|abrazo|besito|suspiro|asiente|emoción|alegría|guiño)[^)]*\)', '', cleaned, flags=re.IGNORECASE)

        # 2. Convertir negritas (**texto**) a texto simple para preservar las palabras
        cleaned = cleaned.replace("**", "").replace("__", "")

        # 3. Eliminar acciones simples entre asteriscos individuales tipo *sonríe* o *se ríe*
        cleaned = re.sub(r'\*(?:[a-záéíóúñ\s,.]+)\*', '', cleaned, flags=re.IGNORECASE)

        # 4. Eliminar TODOS los emojis Unicode (destellos, corazones, autos, mochilas, etc.)
        cleaned = self._emoji_regex.sub('', cleaned)

        # 5. Eliminar emoticonos de texto y kaomojis (ej. *^o^*, :D, <3, ;), xD, ^_^)
        cleaned = re.sub(r'(?:<3|:3|:\)|:D|;\)|xD|XD|T_T|;_;|\^_\^|\*[\^oO_~-]+\*|\*[oO\^_\-]+\*)', '', cleaned)

        # 6. Eliminar caracteres residuales de markdown
        cleaned = cleaned.replace("#", "").replace("`", "").replace(">", "").replace("~", "").replace("*", "")

        # 7. Normalizar espacios
        cleaned = re.sub(r'\s+', ' ', cleaned).strip()
        return cleaned

    def stop(self):
        """Detiene cualquier audio que se esté reproduciendo actualmente."""
        if self._current_process is not None:
            try:
                self._current_process.terminate()
            except Exception:
                pass
            self._current_process = None

    async def synthesize_to_bytes(self, text: str) -> bytes:
        """Sintetiza texto a audio MP3 en memoria con reintentos automáticos."""
        clean_text = self.clean_text_for_speech(text)
        if not clean_text:
            return b""

        for attempt in range(3):
            try:
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
            except Exception:
                await asyncio.sleep(0.2)
                continue
        return b""

    async def synthesize_to_file(self, text: str, output_path: str) -> bool:
        """Sintetiza texto y lo guarda en un archivo MP3."""
        clean_text = self.clean_text_for_speech(text)
        if not clean_text:
            return False

        for attempt in range(3):
            try:
                communicate = edge_tts.Communicate(
                    clean_text,
                    voice=self.voice,
                    rate=self.rate,
                    pitch=self.pitch
                )
                await communicate.save(output_path)
                return True
            except Exception:
                await asyncio.sleep(0.2)
                continue
        return False

    def play_audio_file(self, file_path: str, wait: bool = False) -> None:
        """Reproduce un archivo MP3 deteniendo audios previos para evitar solapamientos."""
        abs_path = os.path.abspath(file_path)
        if not os.path.exists(abs_path):
            return

        self.stop()

        cmd = [
            "powershell",
            "-ExecutionPolicy", "Bypass",
            "-File", self.ps_script,
            "-FilePath", abs_path
        ]

        try:
            if wait:
                subprocess.run(cmd, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                self._current_process = subprocess.Popen(
                    cmd,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
        except Exception:
            pass

    async def speak(self, text: str, wait: bool = False) -> None:
        """Sintetiza y reproduce la voz de Yui."""
        try:
            self._counter += 1
            temp_file = os.path.join(self.temp_dir, f"speech_{self._counter % 20}.mp3")
            success = await self.synthesize_to_file(text, temp_file)
            if success:
                self.play_audio_file(temp_file, wait=wait)
        except Exception:
            pass

voice_service = VoiceService()
