# Saathi

**A Hindi + English voice agent that operates everyday Android apps for blind and low-vision users, with a hard safety layer for anything irreversible.**

You say *"WhatsApp kholo aur Mummy ka last message padho"*. Saathi opens WhatsApp, finds Mummy's chat and reads her last message aloud, word for word. You say *"Mummy ko reply karo: main 7 baje aaunga"*. Saathi types it, reads back who it's going to and the exact words, and sends only after you say *"haan"*.

Built for HACK47: OFFGRID (Oct 2026).

---

## What works today

| Command (Hindi / Hinglish / English) | What Saathi does | Measured on a real device |
|---|---|---|
| "Mummy ka last message padho" | Opens WhatsApp, finds the chat, reads the last message the **contact** sent, quoted exactly | 3/3 |
| "Mummy ko reply karo: …" | Types the reply, reads back recipient + text from the screen, sends only on a clear "haan" | 3/3 to the read-back |
| "Screen pe kya hai?" | One sentence about the screen + the top 3 things you can do. Read-only | 3/3 |
| "Blinkit se Amul doodh 1 litre mangao" | Searches, picks the best match (asks if two tie), adds one, opens the cart, reads back the cart items and stops before ordering | 10/10 to the read-back |
| Walk mode (beta, separate page) | Phone camera → spoken Hindi obstacle warnings with rough distance, on-device | Unit-tested; not yet field-tested |

Measured on 12 Oct 2026 on a Samsung Galaxy Tab S6 Lite (Android 13), model `gpt-5-nano`, about ₹0.05 per task. Details: [`eval/results.md`](eval/results.md). Samples are small (3–10 runs).

**Not done yet:** payments (GPay) are paused; the safety rules for them are built and unit-tested but not run on GPay. Sending a real WhatsApp message has not been tested (every test answered "nahi"). No blind tester session yet.

---

## Safety: the model never decides anything irreversible

Every action passes a deterministic gate ([`saathi/safety.py`](saathi/safety.py), 88 safety tests; 155 Python + 21 walk-mode tests in total) before it touches the phone.

| Rule | What it does |
|---|---|
| R1–R3 holds | Send / pay / order → read-back from the screen, then an explicit spoken yes. Unclear → ask once more → cancel. "No" anywhere wins |
| R4 grounding | Read-backs and read-outs may only quote text that is on screen verbatim |
| R5 / R6 | Payee must match the name you said; amount capped at `MAX_AMOUNT_INR` (₹10) and must match what you said |
| R7 / R8 / R9 | Never types PINs, OTPs or passwords; stops at PIN screens (incl. black secure screenshots) and CAPTCHAs and hands over |
| R10 | Blocks calls, deleting, logging out, WhatsApp payments, and granting Android permissions |
| R11 | Blocks a tap whose point is covered by a Pay / Order / Send control (found on device: a floating checkout bar over a "−" button) |
| Extra | Cart never gets more than you asked for; "Screen pe kya hai?" can't change anything; a cancelled send deletes its own draft |

TalkBack and your keyboard are always restored, even on a crash. Traces with screenshots stay on the laptop (`traces/`, gitignored). OpenAI data sharing is off.

---

## How it works

```
 you ──speak──▶ Sarvam saaras:v4 (STT)
                      │
                      ▼
              agent loop (saathi/agent.py)
     observe screen ─▶ decide (gpt-5-nano, strict JSON) ─▶ SAFETY GATE ─▶ act
          ▲                                                  │
          └──────── Android device (Mobilerun Portal, wireless ADB) ◀┘
                      │
 you ◀──hear── Sarvam bulbul:v4-flash (TTS, Hindi)
```

- **Perception:** the accessibility tree via Mobilerun Portal, normalised to a short element list. Screenshots only when the tree isn't enough.
- **Blinkit** is mostly scripted ([`saathi/blinkit.py`](saathi/blinkit.py)): search deep link, product-card ranking, cart, checkout. The model only handles screens the code doesn't recognise. Blinkit hides its search box from accessibility. That's exactly the kind of app Saathi is for.
- **Prototype topology:** a laptop runs the brain and controls the phone over wireless ADB. The production path is an on-device Android app ([SYSTEM_DESIGN §15](SYSTEM_DESIGN.md)).

---

## Run it

Requirements: macOS/Linux, Python 3.12, [`uv`](https://docs.astral.sh/uv/), `adb`, an Android phone with Developer options.

```bash
uv sync
uv tool install mobilerun && mobilerun setup -d <serial>      # installs the Portal accessibility service
# create .env with OPENAI_API_KEY, SARVAM_API_KEY, ADB_SERIAL (keys listed below)
uv run pytest -q                                              # all tests must pass

uv run python -m saathi.main --text "WhatsApp kholo aur Mummy ka last message padho"
uv run python -m saathi.main                                  # voice: Enter to talk, q to quit, Ctrl+C stops a task
uv run python eval/run_flows.py --flow all --n 10             # success table → eval/results.md (never orders/sends)
```

`.env` keys: `OPENAI_API_KEY`, `OPENAI_MODEL` (default `gpt-5-nano`), `SARVAM_API_KEY`, `ADB_SERIAL`, `MAX_AMOUNT_INR` (10), `ALLOW_ORDERS` (false), `SAATHI_LANG` (hi-IN), optional `OPENAI_BASE_URL` (e.g. Ollama).

**Walk mode:** `python3 -m http.server 8790 -d walk`. See [`walk/README.md`](walk/README.md) (the camera needs https on a real phone).
**Website:** `python3 -m http.server 8765 -d site`.

---

## Repository

| Path | What |
|---|---|
| `saathi/` | The agent: voice, device, agent loop, actions, safety, Blinkit helper, prompts |
| `eval/` | Flow definitions, runner, results |
| `tests/` | Unit tests (safety, actions, normalizer, Blinkit, agent, eval scoring) |
| `scripts/` | Device experiments and utilities (screen dumps, TalkBack test, Sarvam test, model probe, cart cleanup) |
| `walk/` | Walk mode (beta) |
| `site/` | Project website and app-screen prototypes (sample data, not connected) |
| `PRD.md` · `SYSTEM_DESIGN.md` · `SCOPE.md` · `PROJECT.md` | Product, architecture, scope and decision log |

---

## Device safety checklist (after testing)

- Turn **Google Play Protect** back on (Play Store → Play Protect → ⚙️ → Scan apps).
- Developer options → turn off **Wireless debugging** and tap **Revoke USB debugging authorisations**.
- Xiaomi/POCO: turn off **Install via USB** and **USB debugging (Security settings)** if you enabled them.
- Uninstall **Mobilerun Portal** if you're done (it can read and tap the screen).
- Delete local personal data: `tests/fixtures/raw/`, `traces/`, `scratch/`, `cache/` (all gitignored).

---

## Third-party tech and licences

- [Mobilerun](https://github.com/droidrun/mobilerun) (MIT): Android control
- [Sarvam AI](https://docs.sarvam.ai): speech-to-text (Saaras), text-to-speech (Bulbul), transliteration
- [OpenAI](https://platform.openai.com): reasoning + vision (`gpt-5-nano`)
- Walk mode: YOLOv8 weights by Ultralytics (**AGPL-3.0**), onnxruntime-web (MIT), COCO and Open Images class lists
- Website: GSAP (cdnjs), Google Fonts, Pexels stock photos (credited on the page)
