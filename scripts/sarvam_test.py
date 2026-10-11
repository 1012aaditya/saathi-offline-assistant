"""M0 Sarvam hello-world (PROJECT.md open question 4).

Endpoints verified against docs.sarvam.ai on 12 Oct 2026:
  STT  POST https://api.sarvam.ai/speech-to-text   multipart: file, model, mode (v3 only),
       language_code ("unknown" = auto-detect), keyterms (v4 only) -> {"transcript", "language_code"}
  TTS  POST https://api.sarvam.ai/text-to-speech   json: text, language_code, model, speaker
       -> {"audios": [base64 wav]}
  Auth header: api-subscription-key

Usage:
  uv run python scripts/sarvam_test.py record   # 5 s from the Mac mic -> scratch/rahul.wav
  uv run python scripts/sarvam_test.py stt      # transcribe it with saaras:v3 and saaras:v4
  uv run python scripts/sarvam_test.py tts      # speak a Hindi line with bulbul:v3 and v4-flash
"""

import base64
import json
import subprocess
import sys
import time
import wave
from pathlib import Path

import httpx

from saathi.config import get_settings

BASE = "https://api.sarvam.ai"
OUT = Path("scratch")  # gitignored via *.wav
WAV = OUT / "rahul.wav"
RATE = 16_000
SECONDS = 5
LINE = "GPay खोल रहा हूँ"


def headers() -> dict[str, str]:
    key = get_settings().sarvam_api_key
    if not key:
        sys.exit("SARVAM_API_KEY is empty. Add it to .env (never paste it in chat).")
    return {"api-subscription-key": key}


def record() -> None:
    import sounddevice as sd

    OUT.mkdir(exist_ok=True)
    for n in (3, 2, 1):
        print(f"Recording in {n}...", flush=True)
        time.sleep(1)
    print(f'>>> SPEAK NOW ({SECONDS} s): "Rahul ko ek rupaya bhejo"', flush=True)
    audio = sd.rec(SECONDS * RATE, samplerate=RATE, channels=1, dtype="int16")
    sd.wait()
    with wave.open(str(WAV), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(audio.tobytes())
    peak = int(abs(audio).max())
    print(f"saved {WAV}  peak={peak}")
    print("loudness over time (your voice should show as a clear bump):")
    samples = audio[:, 0].astype(float)
    for i in range(0, len(samples), RATE // 4):
        rms = (samples[i : i + RATE // 4] ** 2).mean() ** 0.5
        print(f"  {i / RATE:4.2f}s {'#' * min(60, int(rms / 50))}")
    if peak < 5000:
        print(
            "!! Very quiet. Speak louder / closer, or raise System Settings > Sound > Input volume."
        )


def stt() -> None:
    variants = [
        {"model": "saaras:v3", "mode": "transcribe", "language_code": "hi-IN"},
        {"model": "saaras:v3", "mode": "transcribe", "language_code": "unknown"},
        {"model": "saaras:v4", "language_code": "unknown"},
        {"model": "saaras:v4", "language_code": "unknown", "keyterms": json.dumps(["Rahul"])},
    ]
    for data in variants:
        t = time.time()
        with WAV.open("rb") as f:
            r = httpx.post(
                f"{BASE}/speech-to-text",
                headers=headers(),
                data=data,
                files={"file": ("rahul.wav", f, "audio/wav")},
                timeout=30,
            )
        dt = time.time() - t
        body = r.json()
        label = " ".join(f"{k}={v}" for k, v in data.items())
        if r.is_success:
            print(
                f"{dt:4.1f}s  {label}\n       -> {body.get('transcript')!r} "
                f"lang={body.get('language_code')}"
            )
        else:
            print(f"{dt:4.1f}s  {label}\n       -> HTTP {r.status_code} {body}")


def tts() -> None:
    OUT.mkdir(exist_ok=True)
    for model, speaker in (("bulbul:v3", "shubh"), ("bulbul:v4-flash", None)):
        payload = {"text": LINE, "language_code": "hi-IN", "model": model}
        if speaker:
            payload["speaker"] = speaker
        t = time.time()
        r = httpx.post(f"{BASE}/text-to-speech", headers=headers(), json=payload, timeout=30)
        dt = time.time() - t
        if not r.is_success:
            print(f"{dt:4.1f}s  {model} -> HTTP {r.status_code} {r.text[:300]}")
            continue
        path = OUT / f"tts_{model.replace(':', '_')}.wav"
        path.write_bytes(base64.b64decode(r.json()["audios"][0]))
        print(f"{dt:4.1f}s  {model} speaker={speaker or 'default'} -> {path}  (playing)")
        subprocess.run(["afplay", str(path)], check=False)


if __name__ == "__main__":
    steps = {"record": record, "stt": stt, "tts": tts}
    if len(sys.argv) != 2 or sys.argv[1] not in steps:
        sys.exit(f"usage: sarvam_test.py {{{'|'.join(steps)}}}")
    steps[sys.argv[1]]()
