"""Entry point: --text mode for development, voice mode by default.

uv run python -m saathi.main --text "WhatsApp kholo aur Mummy ka last message padho"
uv run python -m saathi.main            # voice: Enter (or hold SPACE) to talk
"""

import argparse
import asyncio
import sys

from saathi import controls
from saathi.agent import run_command


def _print_say(line: str, progress: bool = False) -> None:
    print(f"  🔊 {line}", flush=True)


async def _input_ask(question: str) -> str:
    print(f"  ❓ {question}", flush=True)
    return await asyncio.to_thread(input, "  > ")


async def text_mode(command: str) -> int:
    print(f"▶ {command}")
    result = await run_command(command, say=_print_say, ask=_input_ask)
    print(f"■ {result.status} in {result.steps} steps · trace: traces/{result.run_id}/")
    return 0 if result.status == "done" else 1


async def voice_mode() -> int:
    from saathi.voice import Voice

    voice = Voice()
    await voice.warm_cache()
    hint = (
        "hold SPACE to talk, ESC to quit" if voice.mode == "space" else "Enter to talk, q to quit"
    )
    print(f"Saathi voice mode ({hint}). SPACE/ESC or Ctrl+C stops a running task.")
    try:
        while True:
            await voice.phrase("ready")
            command = await voice.listen()
            if command is None:
                await voice.phrase("bye")
                return 0
            if not command:
                await voice.phrase("didnt_hear")
                continue
            await voice.phrase("working")
            controls.reset()
            if voice.keys:
                voice.keys.agent_running = True
            try:
                result = await run_command(command, say=voice.speak, ask=voice.ask)
            finally:
                if voice.keys:
                    voice.keys.agent_running = False
            print(f"■ {result.status} in {result.steps} steps · trace: traces/{result.run_id}/")
    finally:
        await voice.close()


async def main() -> int:
    p = argparse.ArgumentParser(prog="saathi")
    p.add_argument("--text", help="run one command in text mode")
    args = p.parse_args()
    return await (text_mode(args.text) if args.text else voice_mode())


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
