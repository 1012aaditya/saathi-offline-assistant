"""Demo bridge: the tablet's smart understanding and vision answered by OpenAI instead of Ollama.

The Android demo build talks to "the laptop" at 127.0.0.1:11434 over `adb reverse`
(LaptopBrain.kt / LaptopVision.kt, Ollama's /api/tags and /api/chat). This script serves those
two endpoints on 127.0.0.1:11435 and answers them with the OpenAI API, so the tablet app needs no
change and the API key stays on the laptop (read from .env by saathi.config; never on the tablet,
never printed or logged).

    uv run python scripts/openai_bridge.py          # starts the bridge + adb reverse tcp:11434 -> 11435
    scripts/laptop_brain.sh                         # switch back to the local Ollama models

Privacy: the tablet already refuses to send private apps (WhatsApp, payments, SMS, mail, phone,
contacts, Settings), PIN / password / protected screens and gallery screenshots. What it does send
(screen summaries of other apps, camera pictures) goes to OpenAI. Logs: timings and sizes only.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from openai import OpenAI

sys.path.insert(0, ".")
from saathi.config import get_settings  # noqa: E402

PORT = 11435
# The model names the tablet app asks for (LaptopBrain.MODEL, LaptopVision.MODEL).
TABLET_MODELS = ["qwen3:4b", "qwen3-vl:2b-instruct"]

settings = get_settings()
if not settings.openai_api_key:
    sys.exit("No OPENAI_API_KEY in .env: add it there (never in code).")
MODEL = settings.openai_model
# Pictures: a fast non-reasoning vision model (14 Oct: gpt-4.1-mini ~2.6 s vs gpt-5-nano ~5 s).
VISION_MODEL = os.environ.get("OPENAI_VISION_MODEL", "gpt-4.1-mini")
client = OpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url or None, timeout=40)


def to_openai(messages: list[dict]) -> list[dict]:
    """Ollama chat messages (text + base64 `images`) -> OpenAI chat messages."""
    out = []
    for m in messages:
        images = m.get("images") or []
        if not images:
            out.append({"role": m.get("role", "user"), "content": m.get("content", "")})
            continue
        parts: list[dict] = [{"type": "text", "text": m.get("content", "")}]
        parts += [{"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}", "detail": "low"}} for b64 in images]
        out.append({"role": m.get("role", "user"), "content": parts})
    return out


def answer(body: dict, vision: bool) -> str:
    model = VISION_MODEL if vision else MODEL
    kwargs: dict = {"model": model, "messages": to_openai(body.get("messages", []))}
    if body.get("format") == "json":
        kwargs["response_format"] = {"type": "json_object"}
    if model.startswith(("gpt-5", "o")):
        # Reasoning models: no temperature; minimal reasoning keeps answers fast.
        kwargs["reasoning_effort"] = "minimal"
        kwargs["max_completion_tokens"] = 600
    else:
        kwargs["temperature"] = (body.get("options") or {}).get("temperature", 0.2)
        kwargs["max_tokens"] = (body.get("options") or {}).get("num_predict", 200)
    r = client.chat.completions.create(**kwargs)
    return r.choices[0].message.content or ""


class Bridge(BaseHTTPRequestHandler):
    def _send(self, code: int, obj: dict) -> None:
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/api/tags":
            self._send(200, {"models": [{"name": n} for n in TABLET_MODELS]})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/api/chat":
            self._send(404, {"error": "not found"})
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        kind = "vision" if any(m.get("images") for m in body.get("messages", [])) else "text"
        t0 = time.time()
        try:
            text = answer(body, vision=kind == "vision")
            print(f"{kind}: answered in {time.time() - t0:.1f}s ({len(text)} chars)", flush=True)
            self._send(200, {"model": body.get("model"), "message": {"role": "assistant", "content": text}, "done": True})
        except Exception as e:  # never the content; the error type is enough
            print(f"{kind}: failed after {time.time() - t0:.1f}s ({type(e).__name__})", flush=True)
            self._send(502, {"error": type(e).__name__})

    def log_message(self, *args) -> None:  # silence per-request access logs
        pass


def link_tablet() -> None:
    """adb reverse: the tablet's 127.0.0.1:11434 now reaches this bridge (first connected device)."""
    lines = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    serials = [ln.split("\t")[0] for ln in lines if ln.endswith("\tdevice")]
    if not serials:
        print("No tablet connected yet: run `adb reverse tcp:11434 tcp:11435` once it is.")
        return
    subprocess.run(["adb", "-s", serials[0], "reverse", "tcp:11434", f"tcp:{PORT}"], check=False, capture_output=True)
    print(f"Tablet linked: {serials[0]}")


if __name__ == "__main__":
    link_tablet()
    print(f"OpenAI bridge on 127.0.0.1:{PORT}: understanding {MODEL}, pictures {VISION_MODEL}. Ctrl+C to stop.", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Bridge).serve_forever()
