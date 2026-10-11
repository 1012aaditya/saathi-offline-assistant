"""Entry point: --text mode for development, voice mode by default (M2).

uv run python -m saathi.main --text "WhatsApp kholo aur Mummy ka last message padho"
"""

import argparse
import asyncio
import sys

from saathi.agent import run_command


def _say(line: str) -> None:
    print(f"  🔊 {line}", flush=True)


async def _ask(question: str) -> str:
    print(f"  ❓ {question}", flush=True)
    return await asyncio.to_thread(input, "  > ")


async def main() -> int:
    p = argparse.ArgumentParser(prog="saathi")
    p.add_argument("--text", help="run one command in text mode")
    args = p.parse_args()
    if not args.text:
        print('Voice mode arrives in M2. Use --text "..." for now.')
        return 2
    print(f"▶ {args.text}")
    result = await run_command(args.text, say=_say, ask=_ask)
    print(f"■ {result.status} in {result.steps} steps · trace: traces/{result.run_id}/")
    return 0 if result.status == "done" else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
