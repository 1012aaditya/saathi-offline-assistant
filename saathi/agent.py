"""Observe -> decide -> gate -> act loop (max 25 steps, stop after 3 consecutive failures).

SYSTEM_DESIGN section 4. Every action passes safety.check() before device.execute().
TalkBack and the user's keyboard are always restored in `finally`.
"""

import asyncio
import base64
import json
import re
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path

import httpx
from async_adbutils.errors import AdbError
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
from saathi.config import BLINKIT, GPAY, PAYMENT_APPS, WHATSAPP, get_settings
from saathi.device import Screen
from saathi.trace import Trace

MAX_STEPS = 25
MAX_FAILURES = 3
MAX_UNANSWERED = 2  # ask_user with no answer twice -> stop
LOOP_WINDOW, LOOP_REPEATS = 6, 3  # same action 3x within 6 executed steps = stuck
HISTORY = 6
PROMPTS = Path(__file__).parent / "prompts"
APP_CARDS = {WHATSAPP: "whatsapp", GPAY: "gpay", BLINKIT: "blinkit"}

Say = Callable[..., Awaitable[None] | None]  # say(line, progress=False)
Ask = Callable[[str], Awaitable[str]]
Confirm = Callable[[str], Awaitable[bool]]

STOPPED = "रुक गया। आप आगे खुद कर सकते हैं।"
DEVICE_ERRORS = (AdbError, ConnectionError, OSError, TimeoutError, httpx.TransportError)
# No single device call may hang the loop: an eval run once froze 16 min on one call.
DEVICE_TIMEOUT_S = 30

# F4 "Screen pe kya hai?" — read-only: describe, never act.
_SUMMARY_REGEX = re.compile(
    r"(?i)screen (pe|par|per) kya|kya (dikh|likha)|what('?s| is) on (the |my )?screen|"
    r"describe (the |my )?screen|स्क्रीन (पे|पर) क्या|क्या दिख|क्या लिखा"
)
SUMMARY_TASK = (
    "SCREEN SUMMARY MODE: do NOT tap, type, scroll or open anything. Answer right away with "
    "`done`: one Devanagari sentence saying which app/screen this is and what matters on it, "
    "then the top 3 things the user can do here. Quote on-screen names exactly. "
    "If the element list is unclear, use `look` once. User asked: "
)


def is_screen_summary(command: str) -> bool:
    return bool(_SUMMARY_REGEX.search(command))


ASK_AGAIN = "साफ़ बताइए: हाँ या नहीं?"


@dataclass
class Result:
    status: str  # done | fail | halt | handoff | cancelled
    message: str
    steps: int
    run_id: str
    history: list[str] = field(default_factory=list)
    # How far it got, for eval: done | pin_handoff | order_hold | send_hold | pay_hold | ...
    reached: str = ""


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
        self, command: str, screen: Screen, history: list[str], send_image: bool
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
        if send_image and screen.screenshot:
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


async def spoken_confirm(ask: Ask, readback: str) -> bool:
    """Read back, take a spoken yes/no. Unclear -> ask once more -> still unclear -> cancel.
    Only a clear yes returns True (CLAUDE.md hard rule 5)."""
    answer = safety.parse_yes_no(await ask(readback))
    if answer == "unclear":
        answer = safety.parse_yes_no(await ask(ASK_AGAIN))
    return answer == "yes"


async def build_context(command: str) -> safety.CommandContext:
    """Extract payee/amount, then add the other script's spelling of the name (Sarvam
    transliteration) so a spoken "राहुल" can match an on-screen "Rahul". Network failure
    just leaves the original spelling."""
    ctx = safety.CommandContext.from_utterance(command)
    if ctx.names:
        from saathi.voice import transliterate

        name = ctx.names[0]
        devanagari = any("\u0900" <= ch <= "\u097f" for ch in name)
        src, dst = ("hi-IN", "en-IN") if devanagari else ("en-IN", "hi-IN")
        other = await transliterate(name, src, dst)
        if other and other.lower() != name.lower():
            ctx.names.append(other)
    return ctx


async def run_command(command: str, say: Say, ask: Ask, confirm: Confirm | None = None) -> Result:
    trace = Trace(command)
    brain = Brain()
    settings = get_settings()
    cap = settings.max_amount_inr
    summary_mode = is_screen_summary(command)

    async def _confirm(readback: str) -> bool:
        return await (confirm(readback) if confirm else spoken_confirm(ask, readback))

    talkback.install_guards()
    history: list[str] = []
    screen: Screen | None = None
    last_changed, look_requested, failures, reconnected = True, summary_mode, 0, False
    recent: list[str] = []  # signatures of executed actions, for loop detection
    unanswered = 0

    async def finish(status: str, message: str, steps: int, reached: str = "") -> Result:
        await _say(say, message)
        reached = reached or status
        trace.result(
            {
                "status": status,
                "reached": reached,
                "message": message,
                "steps": steps,
                "history": history,
            }
        )
        return Result(status, message, steps, trace.run_id, history, reached)

    async def device_call(fn, *args, **kwargs):
        """One reconnect attempt on a dropped ADB / Portal link (SYSTEM_DESIGN section 11)."""
        nonlocal reconnected
        try:
            return await asyncio.wait_for(fn(*args, **kwargs), DEVICE_TIMEOUT_S)
        except DEVICE_ERRORS:
            if reconnected:
                raise
            reconnected = True
            await _say(say, "फ़ोन से कनेक्शन टूट गया, फिर से जोड़ रहा हूँ।")
            await asyncio.wait_for(device.reconnect(), DEVICE_TIMEOUT_S)
            return await asyncio.wait_for(fn(*args, **kwargs), DEVICE_TIMEOUT_S)

    await asyncio.wait_for(device.connect(portal_mode="required"), DEVICE_TIMEOUT_S)
    ctx = await build_context(command)
    trace.step(0, {"context": ctx.__dict__, "summary_mode": summary_mode})
    await talkback.suspend()
    try:
        for step in range(1, MAX_STEPS + 1):
            if controls.interrupted():
                return await finish("halt", STOPPED, step)
            t0 = time.time()
            send_image = needs_vision(screen, last_changed, look_requested)
            screen = await device_call(device.observe, vision=send_image)
            if screen.package in PAYMENT_APPS and screen.screenshot is None:
                # Always capture in payment apps so R8 can spot a black (secure) PIN screen;
                # it is only sent to the model when the vision policy asks for it.
                screen.screenshot = await device_call(device.capture_jpeg)
            observe_s = round(time.time() - t0, 2)
            look_requested = False

            if safety.is_pin_screen(screen):
                return await finish(
                    "handoff", "अपना पिन खुद डालिए, मैं रुक रहा हूँ।", step, "pin_handoff"
                )

            task = (SUMMARY_TASK + command) if summary_mode else command
            decision, meta = await brain.decide(task, screen, history, send_image)
            action = decision.action

            if controls.interrupted():
                return await finish("halt", STOPPED, step)
            verdict = safety.check(action, screen, ctx, cap)
            if (
                summary_mode
                and verdict.kind == "allow"
                and not isinstance(action, (Done, Fail, Look))
            ):
                # "Screen pe kya hai?" must never change anything on the phone.
                verdict = safety.Verdict("block", "summary", "")
            # Narrate only actions that will really run, so "Send dabaya" is never said for a
            # send the user hasn't confirmed yet (the read-back is spoken instead).
            if verdict.kind == "allow" and not isinstance(action, Done):
                await _say(say, decision.say, progress=True)
            record = {
                "step": step,
                "package": screen.package,
                "screen_hash": screen.hash,
                "elements": [e.to_line() for e in screen.elements],
                "vision": send_image,
                "decision": decision.model_dump(),
                "verdict": verdict.__dict__,
                "llm": meta,
                "observe_s": observe_s,
            }

            if verdict.kind == "handoff":
                trace.step(step, record, screen.screenshot)
                reached = "pin_handoff" if verdict.rule == "R8" else "captcha_handoff"
                return await finish("handoff", verdict.message, step, reached)
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
                if verdict.rule == "R3" and not settings.allow_orders:
                    # Real orders stay off unless ALLOW_ORDERS=true (eval never orders).
                    trace.step(step, record, screen.screenshot)
                    await _say(say, verdict.readback.rsplit(".", 1)[0] + ".")
                    return await finish(
                        "done", "ऑर्डर तैयार है, पर मैं अभी ऑर्डर नहीं कर रहा।", step, "order_hold"
                    )
                confirmed = await _confirm(verdict.readback)
                record["confirmed"] = confirmed
                if not confirmed:
                    trace.step(step, record, screen.screenshot)
                    entry = next((e for e in screen.elements if e.rid == "entry"), None)
                    if verdict.rule == "R2" and entry and entry.text:
                        # Don't leave an unsent draft a blind user doesn't know about.
                        await device_call(device.clear_field, entry)
                    held = {"R1": "pay_hold", "R2": "send_hold", "R3": "order_hold"}
                    return await finish(
                        "cancelled", "कैंसल कर दिया।", step, held.get(verdict.rule, "cancelled")
                    )
                if controls.interrupted():
                    trace.step(step, record, screen.screenshot)
                    return await finish("halt", STOPPED, step)
                history.append(f"{step}: user CONFIRMED the {verdict.rule} read-back")

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
                answer = (await ask(action.question)).strip()
                history.append(f"{step}: asked user {action.question!r} -> answered {answer!r}")
                trace.step(step, record, screen.screenshot)
                unanswered = 0 if answer else unanswered + 1
                if unanswered >= MAX_UNANSWERED:
                    return await finish("fail", "मुझे जवाब नहीं मिला, मैं रुक रहा हूँ।", step)
                continue

            t1 = time.time()
            try:
                ok = await device_call(device.execute, action, screen)
            except (RuntimeError, ValueError) as e:  # an action that can't run is a failed step
                ok = False
                record["execute_error"] = f"{type(e).__name__}: {e}"[:200]
            # Cold-starting an app took ~3.5 s on the tablet; taps settle well within 3 s.
            wait_s = 8 if isinstance(action, OpenApp) else 3
            changed = await device_call(device.wait_for_change, screen.hash, timeout_s=wait_s)
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
            sig = action.model_dump_json()
            recent = (recent + [sig])[-LOOP_WINDOW:]
            looping = recent.count(sig) >= LOOP_REPEATS
            if looping:
                # Screens with rotating content (Blinkit's search hint) look "changed" even when
                # nothing progressed; repeating the same action is the reliable signal.
                history.append(f"{step}: WARNING you repeated this exact action; it isn't working")
            last_changed = ok and changed and not looping
            failures = 0 if last_changed else failures + 1
            if failures >= MAX_FAILURES:
                return await finish("halt", "यह स्टेप नहीं हो पा रहा। मैं रुक रहा हूँ।", step)
        return await finish("halt", "बहुत स्टेप हो गए, मैं रुक रहा हूँ।", MAX_STEPS)
    except DEVICE_ERRORS:
        return await finish("halt", "फ़ोन से कनेक्शन नहीं जुड़ पा रहा। मैं रुक रहा हूँ।", 0)
    finally:
        # Bounded so a dead link can't hang the exit; talkback's atexit/signal guard is the
        # backstop if this times out (hard rule 7).
        for cleanup in (talkback.restore, device.restore_keyboard):
            try:
                await asyncio.wait_for(cleanup(), DEVICE_TIMEOUT_S)
            except (*DEVICE_ERRORS, RuntimeError):
                pass
