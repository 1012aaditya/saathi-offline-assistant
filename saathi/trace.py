"""Per-step JSON + JPG logs under traces/<run_id>/ (gitignored: contains personal screens)."""

import json
import time
from pathlib import Path
from typing import Any

ROOT = Path("traces")


class Trace:
    def __init__(self, command: str) -> None:
        self.run_id = time.strftime("%Y%m%d-%H%M%S")
        self.dir = ROOT / self.run_id
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / "command.txt").write_text(command)

    def step(self, n: int, record: dict[str, Any], screenshot: bytes | None = None) -> None:
        (self.dir / f"step_{n:02d}.json").write_text(
            json.dumps(record, ensure_ascii=False, indent=2, default=str)
        )
        if screenshot:
            (self.dir / f"step_{n:02d}.jpg").write_bytes(screenshot)

    def result(self, record: dict[str, Any]) -> None:
        (self.dir / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2))
