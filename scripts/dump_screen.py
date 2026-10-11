"""Dump the current phone screen to tests/fixtures/raw/<name>.json + .png (gitignored).

Usage: uv run python scripts/dump_screen.py whatsapp_home
Read-only: it never taps or types.
"""

import asyncio
import sys

from saathi import device


async def main(name: str) -> None:
    d = await device.connect(portal_mode="required")
    json_path, png_path = await device.dump_fixture(name)
    print(f"portal_available={d.portal_available}")
    print(
        f"saved {json_path} ({json_path.stat().st_size} B) and {png_path} ({png_path.stat().st_size} B)"
    )


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: dump_screen.py <name>")
    asyncio.run(main(sys.argv[1]))
