"""Export anonymised activity records from traces/ for the website's Activity page.

Writes site/data/activity.json. Privacy: traces contain personal screens, so this keeps only
the task type, app, outcome, step counts, safety counts, duration and cost, plus a generic
description ("Read a contact's last message"). No names, message text, commands or screens.

Usage: uv run python scripts/export_activity.py
"""

import json
import re
from datetime import datetime
from pathlib import Path

TRACES = Path("traces")
OUT = Path("site/data/activity.json")

KINDS = [  # (pattern on the command, kind, app, generic description, generic utterance)
    (r"(?i)screen (pe|par) kya|स्क्रीन", "summary", "Screen reading",
     "Describe what's on the screen", "Screen pe kya hai?"),
    (r"(?i)kis(ke|ka|ki)? .*message|unread|नए संदेश", "unread", "WhatsApp",
     "Say who has unread messages", "Kiske message aaye hain?"),
    (r"(?i)reply|bolo|जवाब", "reply", "WhatsApp",
     "Reply to a contact (stopped at the read-back)", "[contact] ko reply karo: …"),
    (r"(?i)blinkit|mangao", "order", "Blinkit",
     "Order milk (stopped at the read-back)", "Blinkit se Amul doodh 1 litre mangao"),
    (r"(?i)message padho|last message", "read", "WhatsApp",
     "Read a contact's last message", "[contact] ka last message padho"),
]  # fmt: skip
PRICES = {"gpt-5-nano": (0.05, 0.40)}  # USD per 1M tokens


def classify(command: str) -> tuple[str, str, str, str] | None:
    for pattern, kind, app, what, said in KINDS:
        if re.search(pattern, command):
            return kind, app, what, said
    return None


def outcome(result: dict) -> tuple[str, str]:
    reached, status = result.get("reached", ""), result.get("status", "")
    if reached in ("send_hold", "order_hold", "pay_hold"):
        return "cancelled", "Test run: stopped at the read-back, nothing sent or ordered"
    if status == "cancelled":
        return "cancelled", "“nahi”"
    if status == "done":
        return "done", ""
    if reached == "pin_handoff":
        return "handed", ""
    return "failed", ""


def export() -> list[dict]:
    records = []
    for d in sorted(TRACES.glob("*/")):
        result_f, cmd_f = d / "result.json", d / "command.txt"
        if not result_f.exists() or not cmd_f.exists():
            continue
        result = json.loads(result_f.read_text())
        if result.get("steps", 0) == 0:
            continue  # phone not connected: not a task attempt
        kind = classify(cmd_f.read_text())
        if kind is None:
            continue
        steps = [json.loads(f.read_text()) for f in sorted(d.glob("step_*.json"))]
        steps = [s for s in steps if "verdict" in s]
        verdicts = [s["verdict"]["kind"] for s in steps]
        cost = 0.0
        for s in steps:
            llm = s.get("llm") or {}
            pin, pout = PRICES.get(llm.get("model", ""), (0.0, 0.0))
            cost += llm.get("tokens_in", 0) * pin / 1e6 + llm.get("tokens_out", 0) * pout / 1e6
        files = sorted(d.iterdir(), key=lambda f: f.stat().st_mtime)
        secs = round(files[-1].stat().st_mtime - files[0].stat().st_mtime) if files else 0
        out, answer = outcome(result)
        holds = verdicts.count("hold")
        stops = verdicts.count("block") + verdicts.count("handoff")
        records.append({
            "ts": datetime.strptime(d.name, "%Y%m%d-%H%M%S").isoformat(),
            "app": kind[1], "kind": kind[0], "what": kind[2], "said": kind[3],
            "outcome": out, "answer": answer if holds else "",
            "steps": result.get("steps", len(steps)), "secs": max(secs, 1),
            "holds": holds, "stops": stops, "allowed": verdicts.count("allow"),
            "stopNote": "Blocked an unsafe tap (safety rule)" if stops else "",
            "costUsd": round(cost, 5), "amount": 0,
        })  # fmt: skip
    return records


if __name__ == "__main__":
    recs = export()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"generated": datetime.now().isoformat(timespec="seconds"),
                               "source": "real test runs, anonymised", "tasks": recs}, indent=1))  # fmt: skip
    print(f"wrote {OUT}: {len(recs)} tasks")
