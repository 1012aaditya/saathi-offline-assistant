"""Probe candidate OpenAI models on a real fixture: vision + strict JSON + latency + cost.

Usage: uv run python scripts/llm_probe.py gpt-5-nano gpt-6-luna
Sends tests/fixtures/raw/whatsapp_home.{json,png} (needs data sharing OFF on the project).
Prints only the chosen action and whether it targets the right chat, not chat contents.
"""

import base64
import io
import json
import sys
import time
from pathlib import Path

from openai import OpenAI, OpenAIError

from saathi.config import get_settings
from saathi.device import normalize, to_prompt

RAW = Path("tests/fixtures/raw")
COMMAND = "WhatsApp kholo aur Mummy ka last message padho"
SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["thought", "say", "action"],
    "properties": {
        "thought": {"type": "string"},
        "say": {"type": "string"},
        "action": {
            "type": "object",
            "additionalProperties": False,
            "required": ["type", "index", "text"],
            "properties": {
                "type": {
                    "type": "string",
                    "enum": ["tap", "type", "scroll", "back", "done", "fail"],
                },
                "index": {"type": ["integer", "null"]},
                "text": {"type": ["string", "null"]},
            },
        },
    },
}
SYSTEM = (
    "You operate an Android phone for a blind user. Given the user's command, the element list "
    "and a screenshot of the current screen, choose exactly ONE next action. `say` is a short "
    "Hindi status line in Devanagari script, at most 12 words."
)


def screenshot_b64() -> str:
    """JPEG, at most 1080 px wide (SYSTEM_DESIGN section 5)."""
    try:
        from PIL import Image
    except ImportError:
        return base64.b64encode((RAW / "whatsapp_home.png").read_bytes()).decode()
    img = Image.open(RAW / "whatsapp_home.png").convert("RGB")
    if img.width > 1080:
        img = img.resize((1080, round(img.height * 1080 / img.width)))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=80)
    return base64.b64encode(buf.getvalue()).decode()


def main(models: list[str]) -> None:
    s = get_settings()
    client = OpenAI(api_key=s.openai_api_key)
    els = normalize(json.loads((RAW / "whatsapp_home.json").read_text()))
    user = [
        {
            "type": "text",
            "text": f"Command: {COMMAND}\nForeground app: com.whatsapp\nScreen:\n{to_prompt(els)}",
        },
        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{screenshot_b64()}"}},
    ]
    for model in models:
        t = time.time()
        try:
            r = client.chat.completions.create(
                model=model,
                messages=[{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
                response_format={
                    "type": "json_schema",
                    "json_schema": {"name": "step", "strict": True, "schema": SCHEMA},
                },
                reasoning_effort="low",
            )
        except OpenAIError as e:  # report and continue with the next model
            print(f"{model}: ERROR {type(e).__name__}: {str(e)[:300]}")
            continue
        dt = time.time() - t
        out = json.loads(r.choices[0].message.content)
        a = out["action"]
        tgt = (
            els[a["index"]]
            if isinstance(a.get("index"), int) and 0 <= a["index"] < len(els)
            else None
        )
        label = f"{tgt.text} {tgt.desc}".lower() if tgt else ""
        verdict = (
            "OK: taps the Mummy chat"
            if ("mumm" in label or "मम्मी" in label)
            else (
                "OK-ish: taps search"
                if "search" in label
                else f"WRONG target rid={tgt.rid if tgt else None}"
            )
        )
        u = r.usage
        devanagari = any("ऀ" <= ch <= "ॿ" for ch in out["say"])
        print(
            f"{model}: {dt:.1f}s  in={u.prompt_tokens} out={u.completion_tokens}  "
            f"action={a['type']}[{a['index']}] -> {verdict}  say_devanagari={devanagari}"
        )


if __name__ == "__main__":
    main(sys.argv[1:] or ["gpt-5-nano", "gpt-6-luna"])
