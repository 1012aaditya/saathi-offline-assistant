"""Run eval flows N times and write a success-rate table to eval/results.md.

    uv run python eval/run_flows.py --flow all --n 10
    uv run python eval/run_flows.py --flow whatsapp_read --n 3

results.md is meant to be committed, so it holds only pass/fail, steps, time, cost and
failure categories, never message text or summaries (those stay in gitignored traces/).
"""

import argparse
import asyncio
import json
import re
import statistics
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from saathi import device
from saathi.actions import Back, Home, OpenApp
from saathi.agent import run_command
from saathi.config import get_settings

ROOT = Path(__file__).parent
TRACES = Path("traces")
SETUP_TIMEOUT_S = 120
# USD per 1M tokens (developers.openai.com pricing, checked 12 Oct 2026). Unknown model -> 0.
PRICES = {"gpt-5-nano": (0.05, 0.40), "gpt-6-luna": (0.10, 0.50), "gpt-5.4-nano": (0.20, 1.25)}


@dataclass
class RunResult:
    ok: bool
    reached: str
    steps: int
    seconds: float
    cost_usd: float
    reason: str = ""


@dataclass
class FlowReport:
    id: str
    runs: list[RunResult] = field(default_factory=list)
    skipped: str = ""


# -- device setup between runs ---------------------------------------------------------------


class TabletLocked(Exception):
    pass


async def setup(kind: str) -> None:
    await device.wake()
    await asyncio.sleep(0.5)
    if await device.is_locked():
        raise TabletLocked("the tablet is locked — unlock it and rerun")
    s = await device.observe()
    await device.execute(Home(), s)
    await asyncio.sleep(1.5)
    if kind == "whatsapp_chat_list":
        await device.execute(OpenApp(app="whatsapp"), await device.observe())
        await asyncio.sleep(3)
        for _ in range(4):  # back out of an open chat / contact info / search
            s = await device.observe()
            if any(e.rid == "search_bar_inner_layout" for e in s.elements):
                break
            await device.execute(Back(), s)
            await asyncio.sleep(1.2)
    elif kind == "blinkit_empty_cart":
        # Each run starts with an empty cart, so "already in cart" never inflates results.
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "blinkit_empty_cart", Path(__file__).parents[1] / "scripts" / "blinkit_empty_cart.py"
        )
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        try:
            await mod.main()
        except SystemExit as e:  # its safety stops must not end the whole eval
            raise RuntimeError(f"cart cleanup stopped: {e}") from None
        s = await device.observe()
        await device.execute(Home(), s)
        await asyncio.sleep(1)
    await device.restore_keyboard()


async def installed(package: str) -> bool:
    # `pm path` prints the APK path, e.g. "package:/data/app/~~x/com.whatsapp-y/base.apk".
    out = await device.shell(f"pm path {package}")
    return out.strip().startswith("package:") and package in out


# -- scoring ---------------------------------------------------------------------------------


def _trace_steps(run_id: str) -> list[dict]:
    files = sorted((TRACES / run_id).glob("step_*.json"))
    return [json.loads(f.read_text()) for f in files]


def _cost(steps: list[dict]) -> float:
    total = 0.0
    for s in steps:
        llm = s.get("llm") or {}
        pin, pout = PRICES.get(llm.get("model", ""), (0.0, 0.0))
        total += llm.get("tokens_in", 0) * pin / 1e6 + llm.get("tokens_out", 0) * pout / 1e6
    return total


def _last_received(steps: list[dict]) -> str | None:
    """Text of the lowest from=them message on the final screen of the run."""
    for s in reversed(steps):
        rows = [
            ln for ln in s.get("elements", []) if "rid=message_text" in ln and "from=them" in ln
        ]
        if rows:

            def y(line: str) -> int:
                return int(re.search(r"\((\d+),(\d+)\)", line).group(2))

            return re.search(r'"(.*?)" desc=', max(rows, key=y)).group(1)
    return None


def score(flow: dict, result, steps: list[dict]) -> tuple[bool, str]:
    want = flow.get("success") or {}
    if want.get("reached") and result.reached != want["reached"]:
        return False, f"reached {result.reached} ({result.status})"
    summary = result.message.lower()
    anyof = want.get("summary_contains_any")
    if anyof and not any(a.lower() in summary for a in anyof):
        return False, "summary missing expected words"
    if want.get("quotes_last_received"):
        last = _last_received(steps)
        if not last:
            return False, "no received message on final screen"
        if last.lower() not in summary:
            return False, "summary does not quote the last received message"
    return True, ""


# -- runner ----------------------------------------------------------------------------------


async def run_flow(flow: dict, n: int) -> FlowReport:
    report = FlowReport(flow["id"])
    if flow.get("requires") and not await installed(flow["requires"]):
        report.skipped = f"{flow['requires']} not installed"
        return report

    async def quiet_say(line: str, progress: bool = False) -> None:
        pass

    answers = list(flow.get("answers") or [])

    async def no_answer(question: str) -> str:
        # Scripted replies from flows.yaml (in order); "" once they run out.
        return answers.pop(0) if answers else ""

    async def auto_confirm(readback: str) -> bool:
        return bool(flow.get("auto_confirm"))

    for i in range(1, n + 1):
        answers[:] = list(flow.get("answers") or [])
        try:
            await asyncio.wait_for(setup(flow.get("setup", "home")), SETUP_TIMEOUT_S)
        except TabletLocked as e:
            print(f"  STOPPED: {e}", flush=True)
            break
        except (TimeoutError, ConnectionError, OSError, RuntimeError) as e:
            print(f"  {flow['id']} #{i}: setup problem ({e}); retrying once", flush=True)
            await device.reconnect()
            try:
                await asyncio.wait_for(setup(flow.get("setup", "home")), SETUP_TIMEOUT_S)
            except (TabletLocked, TimeoutError, ConnectionError, OSError, RuntimeError) as e2:
                print(f"  STOPPED: setup failed twice ({e2})", flush=True)
                break
        t = time.time()
        result = await run_command(
            flow["command"], say=quiet_say, ask=no_answer, confirm=auto_confirm
        )
        secs = time.time() - t
        steps = _trace_steps(result.run_id)
        ok, reason = score(flow, result, steps)
        r = RunResult(ok, result.reached, result.steps, secs, _cost(steps), reason)
        report.runs.append(r)
        mark = "✓" if ok else "✗"
        print(
            f"  {flow['id']} #{i}: {mark} reached={r.reached} steps={r.steps} "
            f"{secs:.0f}s ${r.cost_usd:.4f} {reason} · traces/{result.run_id}/",
            flush=True,
        )
    return report


def write_results(reports: list[FlowReport], n: int, path: Path) -> None:
    s = get_settings()
    lines = [
        "# Eval results",
        "",
        f"Generated {time.strftime('%Y-%m-%d %H:%M')} · model `{s.openai_model}` · {n} runs per flow",
        "",
        "| Flow | Success | Rate | Median steps | Median time | Median cost | Failures |",
        "|------|---------|------|--------------|-------------|-------------|----------|",
    ]
    for r in reports:
        if r.skipped:
            lines.append(f"| {r.id} | skipped | — | — | — | — | {r.skipped} |")
            continue
        ok = sum(x.ok for x in r.runs)
        total = len(r.runs)
        fails = Counter(x.reason for x in r.runs if not x.ok)
        fail_txt = "; ".join(f"{k} ×{v}" for k, v in fails.most_common()) or "—"
        lines.append(
            f"| {r.id} | {ok}/{total} | {ok / total:.0%} "
            f"| {statistics.median(x.steps for x in r.runs):.0f} "
            f"| {statistics.median(x.seconds for x in r.runs):.0f} s "
            f"| ${statistics.median(x.cost_usd for x in r.runs):.4f} | {fail_txt} |"
        )
    lines += [
        "",
        (
            "Success criteria and setup per flow: `eval/flows.yaml`. Step-by-step traces "
            "(local only, contain personal screens): `traces/<run_id>/`."
        ),
        "",
    ]
    path.write_text("\n".join(lines))


async def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--flow", default="all")
    p.add_argument("--n", type=int, default=10)
    p.add_argument("--out", default=str(ROOT / "results.md"))
    args = p.parse_args()

    if get_settings().allow_orders:
        sys.exit("Refusing to run: ALLOW_ORDERS=true would let eval place real Blinkit orders.")
    flows = yaml.safe_load((ROOT / "flows.yaml").read_text())
    if args.flow != "all":
        flows = [f for f in flows if f["id"] == args.flow]
        if not flows:
            sys.exit(f"unknown flow {args.flow!r}")

    await device.connect(portal_mode="required")
    reports = []
    for flow in flows:
        print(f"▶ {flow['id']}: {flow['command']}", flush=True)
        reports.append(await run_flow(flow, args.n))
    write_results(reports, args.n, Path(args.out))
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
