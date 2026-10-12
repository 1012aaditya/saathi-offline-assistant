"""Observe -> decide -> gate -> act loop (max 25 steps, stop after 3 consecutive failures).

SYSTEM_DESIGN section 4. Every action passes safety.check() before device.execute().
TalkBack and the user's keyboard are always restored in `finally`.
"""

import base64
import json
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path

from openai import AsyncOpenAI, OpenAIError

from saathi import controls, device, safety, talkback
from saathi.actions import (
    MODEL_SCHEMA,
    AskUser,
    Decision,
    Done,
    Fail,
    Look,
    OpenApp,
    parse_decision,
)
from saathi.config import BLINKIT, GPAY, WHATSAPP, get_settings
from saathi.device import Screen
from saathi.trace import Trace

MAX_STEPS = 25
MAX_FAILURES = 3
HISTORY = 6
PROMPTS = Path(__file__).parent / "prompts"
APP_CARDS = {WHATSAPP: "whatsapp", GPAY: "gpay", BLINKIT: "blinkit"}

Say = Callable[..., Awaitable[None] | None]  # say(line, progress=False)
Ask = Callable[[str], Awaitable[str]]


@dataclass
class Result:
    status: str  # done | fail | halt | handoff | cancelled
    message: str
    steps: int
    run_id: str
    history: list[str] = field(default_factory=list)


def _app_card(package: str) -> str:
    name = APP_CARDS.get(package)
    return (PROMPTS / "app_cards" / f"{name}.md").read_text() if name else ""


def needs_vision(screen: Screen | None, last_changed: bool, look_requested: bool) -> bool:
    """SYSTEM_DESIGN section 5 screenshot policy."""
    if look_requested or not last_changed:
        return True
    if screen is None:
        return False
    return screen.labeled_clickables() < 5 or screen.package in get_settings().vision_app_set


class Brain:
    """One model call per step. Invalid output -> retry once -> Fail (CLAUDE.md hard rule 10)."""

    def __init__(self) -> None:
        s = get_settings()
        self.model = s.openai_model
        self.client = AsyncOpenAI(api_key=s.openai_api_key or "ollama", base_url=s.openai_base_url)
        self.system = (PROMPTS / "system_hi.md").read_text()

    async def decide(
        self, command: str, screen: Screen, history: list[str]
    ) -> tuple[Decision, dict]:
        text = (
            f"Command: {command}\n"
            f"Foreground app: {screen.package}\n"
            f"Keyboard visible: {screen.keyboard_visible}\n"
            f"App card:\n{_app_card(screen.package) or '(none)'}\n"
            f"History (oldest first):\n" + ("\n".join(history[-HISTORY:]) or "(none)") + "\n"
            f"Screen:\n{device.to_prompt(screen.elements)}"
        )
        content: list[dict] = [{"type": "text", "text": text}]
        if screen.screenshot:
            b64 = base64.b64encode(screen.screenshot).decode()
            content.append(
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}}
            )
        kwargs: dict = {}
        if self.model.startswith(("gpt-5", "o")):
            kwargs["reasoning_effort"] = "low"
        meta: dict = {"model": self.model, "tokens_in": 0, "tokens_out": 0, "attempts": 0}
        for _attempt in range(2):
            meta["attempts"] += 1
            t = time.time()
            try:
                r = await self.client.chat.completions.create(
                    model=self.model,
                    messages=[
                        {"role": "system", "content": self.system},
                        {"role": "user", "content": content},
                    ],
                    response_format={
                        "type": "json_schema",
                        "json_schema": {"name": "step", "strict": True, "schema": MODEL_SCHEMA},
                    },
                    **kwargs,
                )
                meta["latency_s"] = round(time.time() - t, 2)
                if r.usage:
                    meta["tokens_in"] += r.usage.prompt_tokens
                    meta["tokens_out"] += r.usage.completion_tokens
                raw = json.loads(r.choices[0].message.content or "")
                meta["raw"] = raw
                return parse_decision(raw), meta
            except (OpenAIError, ValueError) as e:
                meta["error"] = f"{type(e).__name__}: {str(e)[:300]}"
        return Decision(action=Fail(reason="मॉडल का जवाब समझ नहीं आया।")), meta


async def _say(say: Say, line: str, progress: bool = False) -> None:
    if line:
        out = say(line, progress=progress)
        if out is not None:
            await out


async def run_command(command: str, say: Say, ask: Ask) -> Result:
    trace = Trace(command)
    brain = Brain()
    talkback.install_guards()
    history: list[str] = []
    screen: Screen | None = None
    last_changed, look_requested, failures = True, False, 0

    async def finish(status: str, message: str, steps: int) -> Result:
        await _say(say, message)
        trace.result({"status": status, "message": message, "steps": steps, "history": history})
        return Result(status, message, steps, trace.run_id, history)

    await device.connect(portal_mode="required")
    await talkback.suspend()
    try:
        for step in range(1, MAX_STEPS + 1):
            if controls.interrupted():
                return await finish("halt", "रुक गया। आप आगे खुद कर सकते हैं।", step)
            t0 = time.time()
            screen = await device.observe(vision=needs_vision(screen, last_changed, look_requested))
            observe_s = round(time.time() - t0, 2)
            look_requested = False

            if safety.is_pin_screen(screen):
                return await finish("handoff", "अपना पिन खुद डालिए, मैं रुक रहा हूँ।", step)

            decision, meta = await brain.decide(command, screen, history)
            action = decision.action
            await _say(say, decision.say, progress=True)

            if controls.interrupted():
                return await finish("halt", "रुक गया। आप आगे खुद कर सकते हैं।", step)
            verdict = safety.check(action, screen)
            record = {
                "step": step,
                "package": screen.package,
                "screen_hash": screen.hash,
                "elements": [e.to_line() for e in screen.elements],
                "vision": screen.screenshot is not None,
                "decision": decision.model_dump(),
                "verdict": verdict.__dict__,
                "llm": meta,
                "observe_s": observe_s,
            }

            if verdict.kind == "handoff":
                trace.step(step, record, screen.screenshot)
                return await finish("handoff", verdict.message, step)
            if verdict.kind == "block":
                trace.step(step, record, screen.screenshot)
                history.append(f"{step}: {action.type} BLOCKED by safety ({verdict.rule})")
                await _say(say, verdict.message)
                failures += 1
                if failures >= MAX_FAILURES:
                    return await finish(
                        "halt", "यह काम सुरक्षित तरीके से नहीं हो पा रहा, मैं रुक रहा हूँ।", step
                    )
                continue
            if verdict.kind == "hold":
                # M1 has no spoken confirmation yet; confirmation defaults to cancel (hard rule 5).
                trace.step(step, record, screen.screenshot)
                await _say(say, verdict.readback)
                return await finish("cancelled", "पुष्टि अभी उपलब्ध नहीं है, कैंसल कर दिया।", step)

            if isinstance(action, Done):
                trace.step(step, record, screen.screenshot)
                return await finish("done", action.summary, step)
            if isinstance(action, Fail):
                trace.step(step, record, screen.screenshot)
                return await finish("fail", action.reason, step)
            if isinstance(action, Look):
                look_requested = True
                history.append(f"{step}: look (screenshot requested)")
                trace.step(step, record, screen.screenshot)
                continue
            if isinstance(action, AskUser):
                answer = await ask(action.question)
                history.append(f"{step}: asked user {action.question!r} -> answered {answer!r}")
                trace.step(step, record, screen.screenshot)
                continue

            t1 = time.time()
            ok = await device.execute(action, screen)
            # Cold-starting an app took ~3.5 s on the tablet; taps settle well within 3 s.
            wait_s = 8 if isinstance(action, OpenApp) else 3
            changed = await device.wait_for_change(screen.hash, timeout_s=wait_s)
            record |= {"executed": ok, "changed": changed, "act_s": round(time.time() - t1, 2)}
            trace.step(step, record, screen.screenshot)

            target = ""
            idx = getattr(action, "index", None)
            if idx is not None and idx < len(screen.elements):
                e = screen.elements[idx]
                target = f" on [{idx}] {(e.text or e.desc)[:40]!r}"
            history.append(
                f"{step}: {action.type} {action.model_dump_json(exclude={'type'})}{target}"
                f" -> {'screen changed' if changed else 'NO CHANGE'}"
            )
            last_changed = ok and changed
            failures = 0 if last_changed else failures + 1
            if failures >= MAX_FAILURES:
                return await finish("halt", "यह स्टेप नहीं हो पा रहा। मैं रुक रहा हूँ।", step)
        return await finish("halt", "बहुत स्टेप हो गए, मैं रुक रहा हूँ।", MAX_STEPS)
    finally:
        await talkback.restore()
        await device.restore_keyboard()
