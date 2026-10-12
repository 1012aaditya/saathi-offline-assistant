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
    from saathi.session import HELP_TEXT, Session, control
    from saathi.voice import Voice

    voice = Voice()
    session = Session()
    await voice.warm_cache()
    hint = (
        "hold SPACE to talk, ESC to quit" if voice.mode == "space" else "Enter to talk, q to quit"
    )
    print(f"Saathi voice mode ({hint}). Enter (or SPACE/ESC) stops a running task.")
    if voice.mode == "enter":
        await voice.phrase("stop_hint")
    try:
        first = True
        while True:
            await voice.phrase("ready" if first else "anything_else")
            first = False
            heard = await voice.listen()
            if heard is None:
                await voice.phrase("bye")
                return 0
            if not heard:
                await voice.phrase("didnt_hear")
                continue

            kind = control(heard)
            if kind == "quit":
                await voice.phrase("bye")
                return 0
            if kind == "repeat":
                await (voice.speak(session.last_answer) if session.last_answer
                       else voice.phrase("nothing_to_repeat"))  # fmt: skip
                continue
            if kind in ("slower", "faster"):
                voice.pace = session.adjust_pace(faster=kind == "faster")
                await voice.phrase(kind)
                continue
            if kind == "help":
                await voice.speak(HELP_TEXT)
                continue

            command = session.expand(heard)
            if command != heard:
                print(f"  ↪ {command}", flush=True)
            await voice.phrase("working")
            controls.reset()
            voice.agent_running = True
            if voice.keys:
                voice.keys.agent_running = True
            try:
                result = await run_command(command, say=voice.speak, ask=voice.ask)
            finally:
                voice.agent_running = False
                if voice.keys:
                    voice.keys.agent_running = False
            session.remember(command, result.message)
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
