"""Push-to-talk recording, Sarvam STT (saaras:v4), Sarvam TTS (bulbul:v4-flash), phrase cache.

Endpoints verified against docs.sarvam.ai on 12 Oct 2026 (see scripts/sarvam_test.py).

Push-to-talk has two modes:
  - SPACE (hold to talk, ESC to stop): needs macOS Input Monitoring for the terminal app,
    which lets that app read every key system-wide. Used only if already granted.
  - Enter (press to start, Enter to stop): no extra permission. Default.
"""

import asyncio
import base64
import hashlib
import io
import json
import threading
import wave
from pathlib import Path

import httpx
import numpy as np
import sounddevice as sd

from saathi import controls, narrator
from saathi.config import get_settings

API = "https://api.sarvam.ai"
STT_MODEL = "saaras:v4"
TTS_MODEL = "bulbul:v4-flash"  # default voice, owner's pick in M0
RATE = 16_000
CACHE = Path("cache/tts")  # gitignored
KEYTERMS = ["WhatsApp", "GPay", "Google Pay", "Blinkit"]

# Fixed phrases synthesized once and replayed instantly (S4).
PHRASES = {
    "ready": "हाँ, बोलिए।",
    "didnt_hear": "मुझे सुनाई नहीं दिया, फिर से बोलिए।",
    "working": "ठीक है, कर रहा हूँ।",
    "stopped": "रुक गया। आप आगे खुद कर सकते हैं।",
    "bye": "ठीक है, फिर मिलते हैं।",
    "net_error": "इंटरनेट में दिक्कत है, फिर से कोशिश कीजिए।",
}


class Recorder:
    """Collects 16 kHz mono int16 audio between start() and stop()."""

    def __init__(self) -> None:
        self._chunks: list[np.ndarray] = []
        self._stream: sd.InputStream | None = None

    def start(self) -> None:
        self._chunks = []
        self._stream = sd.InputStream(samplerate=RATE, channels=1, dtype="int16", callback=self._cb)
        self._stream.start()

    def _cb(self, data, _frames, _time, _status) -> None:
        self._chunks.append(data.copy())

    def stop(self) -> bytes:
        if self._stream:
            self._stream.stop()
            self._stream.close()
            self._stream = None
        audio = np.concatenate(self._chunks) if self._chunks else np.zeros((0, 1), np.int16)
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(audio.tobytes())
        return buf.getvalue()


def _space_mode_available() -> bool:
    try:
        from pynput import keyboard

        return bool(getattr(keyboard.Listener, "IS_TRUSTED", False))
    except Exception:  # noqa: BLE001 - pynput can fail to import without a display
        return False


class Keys:
    """SPACE/ESC listener (only when macOS trusts us). SPACE press/release drive push-to-talk
    while idle; while the agent runs, SPACE or ESC interrupts it."""

    def __init__(self) -> None:
        from pynput import keyboard

        self._kb = keyboard
        self.space_down = threading.Event()
        self.space_up = threading.Event()
        self.esc = threading.Event()
        self.agent_running = False
        self._listener = keyboard.Listener(on_press=self._press, on_release=self._release)
        self._listener.start()

    def _press(self, key) -> None:
        if key == self._kb.Key.esc:
            self.esc.set()
            controls.interrupt()
        elif key == self._kb.Key.space:
            if self.agent_running:
                controls.interrupt()
            elif not self.space_down.is_set():
                self.space_up.clear()
                self.space_down.set()

    def _release(self, key) -> None:
        if key == self._kb.Key.space and self.space_down.is_set():
            self.space_down.clear()
            self.space_up.set()


class Voice:
    def __init__(self) -> None:
        self.key = get_settings().sarvam_api_key
        if not self.key:
            raise SystemExit("SARVAM_API_KEY is empty in .env")
        self.http = httpx.AsyncClient(
            base_url=API, headers={"api-subscription-key": self.key}, timeout=30
        )
        self.lang = get_settings().saathi_lang
        self.keys = Keys() if _space_mode_available() else None
        self.dedup = narrator.Dedup()
        self._play_lock = asyncio.Lock()
        CACHE.mkdir(parents=True, exist_ok=True)

    @property
    def mode(self) -> str:
        return "space" if self.keys else "enter"

    # -- speaking ------------------------------------------------------------------------

    async def _to_devanagari(self, text: str) -> str:
        if not narrator.needs_transliteration(text):
            return text
        try:
            r = await self.http.post(
                "/transliterate",
                json={
                    "input": text,
                    "source_language_code": "en-IN",
                    "target_language_code": "hi-IN",
                },
            )
            r.raise_for_status()
            return r.json()["transliterated_text"]
        except (httpx.HTTPError, KeyError):
            return text  # speak it anyway rather than stay silent

    async def _tts(self, text: str) -> Path:
        path = (
            CACHE
            / f"{hashlib.sha1(f'{TTS_MODEL}|{self.lang}|{text}'.encode()).hexdigest()[:16]}.wav"
        )
        if not path.exists():
            r = await self.http.post(
                "/text-to-speech",
                json={"text": text, "language_code": self.lang, "model": TTS_MODEL},
            )
            r.raise_for_status()
            path.write_bytes(base64.b64decode(r.json()["audios"][0]))
        return path

    async def _play(self, path: Path) -> None:
        async with self._play_lock:
            proc = await asyncio.create_subprocess_exec("afplay", str(path))
            await proc.wait()

    async def speak(self, text: str, progress: bool = False) -> None:
        """Speak a line. progress=True applies the 12-word cap and skips repeats."""
        text = text.strip()
        if not text or (progress and not self.dedup.should_say(text)):
            return
        if progress:
            text = narrator.clip(text)
        try:
            text = await self._to_devanagari(text)
            print(f"  🔊 {text}", flush=True)
            await self._play(await self._tts(text))
        except httpx.HTTPError as e:
            print(f"  (TTS failed: {e})", flush=True)

    async def phrase(self, name: str) -> None:
        await self.speak(PHRASES[name])

    async def warm_cache(self) -> None:
        await asyncio.gather(*(self._tts(p) for p in PHRASES.values()), return_exceptions=True)

    # -- listening -----------------------------------------------------------------------

    async def record(self) -> bytes | None:
        """One push-to-talk utterance. None if the user asked to quit (ESC / 'q')."""
        rec = Recorder()
        if self.keys:
            print("  🎙  Hold SPACE and speak (ESC to quit)…", flush=True)
            self.keys.esc.clear()
            while not self.keys.space_down.is_set():
                if self.keys.esc.is_set():
                    return None
                await asyncio.sleep(0.05)
            rec.start()
            print("  ● recording…", flush=True)
            await asyncio.to_thread(self.keys.space_up.wait)
        else:
            cmd = await asyncio.to_thread(
                input, "  🎙  Press Enter, then speak (q + Enter to quit): "
            )
            if cmd.strip().lower() == "q":
                return None
            rec.start()
            await asyncio.to_thread(input, "  ● recording… press Enter when done ")
        await asyncio.sleep(0.25)  # keep the tail of the last word
        return rec.stop()

    async def transcribe(self, wav: bytes) -> str:
        if len(wav) < RATE * 2 * 0.4:  # under ~0.4 s of audio
            return ""
        r = await self.http.post(
            "/speech-to-text",
            data={"model": STT_MODEL, "language_code": "unknown", "keyterms": json.dumps(KEYTERMS)},
            files={"file": ("speech.wav", wav, "audio/wav")},
        )
        r.raise_for_status()
        return (r.json().get("transcript") or "").strip()

    async def listen(self) -> str | None:
        """Record + transcribe. '' if nothing was heard, None to quit."""
        wav = await self.record()
        if wav is None:
            return None
        try:
            text = await self.transcribe(wav)
        except httpx.HTTPError:
            await self.phrase("net_error")
            return ""
        print(f"  📝 {text!r}", flush=True)
        return text

    async def ask(self, question: str) -> str:
        """Agent's ask_user: speak the question, take one spoken answer."""
        await self.speak(question)
        if self.keys:
            self.keys.agent_running = False
        try:
            return (await self.listen()) or ""
        finally:
            if self.keys:
                self.keys.agent_running = True

    async def close(self) -> None:
        await self.http.aclose()
