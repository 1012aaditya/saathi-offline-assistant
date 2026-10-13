# CLAUDE.md

Instructions for Claude Code working in this repo.

## What this is

**Saathi** — a Hindi + English voice agent that operates Android apps (WhatsApp, Google Pay, Blinkit) for blind and low-vision users. Prototype: Python on a laptop controls a real Android phone over wireless ADB via Mobilerun's `AndroidDriver`. Built for HACK47: OFFGRID — **deadline 15 Oct 2026, 09:30 IST; feature freeze 14 Oct 12:00 IST.**

Read before non-trivial work: `PROJECT.md` (status, decisions, open questions) → `SYSTEM_DESIGN.md` (architecture, contracts) → `SCOPE.md` (what's in/out, cut lines). Product intent lives in `PRD.md`.

## Hard rules (never break these)

1. **Never weaken, bypass or skip `saathi/safety.py`.** Every device action goes through `safety.check()` before execution. Any change to safety rules needs a matching unit test in `tests/test_safety.py`.
2. **Never write code that reads, types, stores or logs UPI PINs, passwords or OTPs.** PIN screens → stop and hand off to the user.
   *One exception (owner decision, 13 Oct): the Android **braille reader** (`android/braille/`, `app/.../braille/`, `BrailleScreen.kt`) may show an OTP or user-typed text as braille vibration. Only via Android's SMS User Consent (per message, user taps Allow; no SMS permission). Display only: never spoken, logged, stored, sent to a model or typed anywhere; dots hidden on screen; `FLAG_SECURE`; wiped after 3 min or when the app leaves the screen. The agent itself still never reads, types or stores OTPs/passwords, and UPI PINs are never shown anywhere.*
3. **Never solve or bypass CAPTCHAs.** Hand off to the user.
4. **Read-backs may only contain strings that exist verbatim in the current screen's element list.**
5. **Confirmation defaults to cancel.** Unclear answer → ask once more → cancel.
6. **Real payments in development: ₹1–10 only, to the owner's own accounts.** Respect `MAX_AMOUNT_INR`. Eval runs must never place real orders.
7. **TalkBack must always be restored** — `talkback.restore()` in `finally`, plus `atexit` and signal handlers. Never leave the user without a screen reader.
8. **All device I/O goes through `saathi/device.py`.** No direct ADB/driver calls elsewhere (except `talkback.py`, which only toggles settings).
9. **No secrets in code.** Use `.env`. Never commit `.env` or `traces/` (traces contain screenshots of personal apps).
10. **Validate every model output with pydantic.** Invalid → retry once → `fail` action.

## Stack

- Python 3.12 (Mobilerun requires >=3.11,<3.14), `uv`, asyncio throughout
- `mobilerun` / `mobilerun_core_local.AndroidDriver` — device control (tap, swipe, input_text, press_button, start_app, screenshot, get_ui_tree)
- OpenAI — reasoning + vision, structured JSON output (`OPENAI_MODEL` in `.env`)
- Sarvam — Saaras v3 STT, Bulbul v3 TTS (`SARVAM_API_KEY`); verify endpoint details in docs.sarvam.ai
- pydantic v2, pydantic-settings, rapidfuzz, sounddevice, pynput, httpx, pyyaml, pytest, ruff

## Commands (create these as the code lands)

```bash
uv sync                                             # install deps
adb devices && mobilerun ping                       # device + Portal check
uv run python -m saathi.main --text "WhatsApp kholo aur Mummy ka last message padho"
uv run python -m saathi.main                        # voice mode (Enter to talk; SPACE-hold only if macOS Input Monitoring is granted)
uv run python scripts/dump_screen.py whatsapp_home  # record a screen fixture → tests/fixtures/raw/ (gitignored)
node --test 'walk/tests/*.test.mjs'                 # walk mode tests
uv run pytest -q                                    # unit tests (safety tests must pass before any commit)
uv run python eval/run_flows.py --flow all --n 10   # success-rate table → eval/results.md
uv run ruff check . && uv run ruff format .
# Android app (branch feature/android-app; see android/README.md)
cd android && ./gradlew :safety:test :app:assembleDebug   # needs JAVA_HOME=/opt/homebrew/opt/openjdk@17
scripts/laptop_brain.sh                             # demo: tablet's guide uses this laptop's Ollama model over adb reverse
```

## Code layout

```
saathi/main.py        entry; --text mode and voice mode
saathi/config.py      typed settings from .env
saathi/device.py      AndroidDriver wrapper; tree → Element list; observe/execute/wait_for_change
saathi/agent.py       observe → decide → gate → act loop (max 25 steps, 3-failure stop)
saathi/actions.py     pydantic action schema (tap, type, scroll, back, home, open_app, look, ask_user, done, fail)
saathi/safety.py      deterministic rules R1–R10 (see SYSTEM_DESIGN §6)
saathi/voice.py       push-to-talk, STT, TTS, phrase cache
saathi/narrator.py    short Hindi status lines (≤ 12 words, Devanagari)
saathi/talkback.py    suspend/restore TalkBack via `adb shell settings`
saathi/trace.py       per-step JSON/JPG logs → traces/<run_id>/
saathi/blinkit.py     deterministic Blinkit steps (search link, ranking, cart, checkout read-back)
saathi/prompts/       system_hi.md + app_cards/{whatsapp,gpay,blinkit}.md
eval/                 flows.yaml, run_flows.py, results.md
tests/                test_safety.py, test_actions.py, test_blinkit.py, …, fixtures/
scripts/              dump_screen, talkback_test, sarvam_test, llm_probe, blinkit_empty_cart
walk/                 walk mode (beta): on-device obstacle warnings, phone browser, no agent
site/                 website + app-screen prototypes (not connected to the agent)
android/              native app (Kotlin/Compose): Clarity screens + safety gate port (android/safety)
```

## Conventions

- Type hints everywhere; small pure functions; async for anything touching device, network or audio.
- Narration and spoken strings: Hindi in Devanagari (TTS reads it best), ≤ 12 words. Code, logs and comments: English.
- App packages live in `config.py` alias maps — don't hardcode package names in logic.
- Prefer the UI tree; attach screenshots only per the vision policy in SYSTEM_DESIGN §5.
- Keep it plain Python. Don't add agent frameworks or web servers unless `SCOPE.md` calls for it.

## How to work

- Work milestone by milestone (see `SCOPE.md`). Get one flow working end to end before generalizing.
- Before writing parsing code against device output, **dump a real fixture first** and code against it — don't guess the tree shape.
- After any change to `agent.py`, `device.py` or `safety.py`: run `pytest`, then one real run on the phone.
- When a flow fails, check `traces/<run_id>/` before changing prompts.
- If something in `PROJECT.md` "Open questions" gets answered, update that file and the decision log.
- If a cut line in `SCOPE.md` is hit, follow it — don't extend scope past feature freeze.

## Device notes

- Wireless ADB: `adb pair <ip:port>` then `adb connect <ip:port>`; serial goes in `ADB_SERIAL`.
- Get accessibility service names with `adb shell settings get secure enabled_accessibility_services`; store `PORTAL_SERVICE` and `TALKBACK_SERVICE` in `.env`.
- Mobilerun's taps are ADB coordinate taps; with TalkBack on they may only move focus — that's why we suspend TalkBack while acting.
- The UPI PIN screen is usually secure (black screenshot). Treat a ≥ 95% black screenshot as a secure screen → handoff.