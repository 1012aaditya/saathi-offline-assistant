# Saathi — Project Hub

> Hindi + English voice agent that operates everyday Indian Android apps for blind and low-vision users, with a hard safety layer for money.

---

## Hackathon

| | |
|---|---|
| Event | HACK47: OFFGRID (online, open theme) — https://hack47-offgrid.devpost.com/ |
| Window | 15 Sep – 15 Oct 2026 |
| Deadline | **15 Oct 2026, 00:00 EDT = 09:30 IST** · internal target 14 Oct, 23:00 IST |
| Prize | 1 winner: $10,000 OpenAI credits + Hack47 cohort spot + Red Bull supply |
| Sponsors | OpenAI, Red Bull |
| Judging | Originality · Impact · Execution · Product thinking · Technical depth · Potential |
| Rules | Built during the hackathon window · AI tools allowed · disclose major third-party tech/APIs/models · working prototype + demo video strongly recommended · source code required |
| Team | Aadi (solo) |

---

## Docs

| File | What it covers |
|------|----------------|
| `PRD.md` | Problem, users, goals, flows, requirements, safety rules, metrics, submission checklist |
| `SYSTEM_DESIGN.md` | Architecture, components, agent loop, safety gate, model contract, voice, TalkBack, eval |
| `SCOPE.md` | Must/should/could/won't, milestones, cut lines, risks |
| `CLAUDE.md` | Instructions for Claude Code working in this repo |

---

## Status

- [ ] M0 Setup + experiments (12 Oct AM) — **done except GPay dumps (Q5)**: Portal ping OK over Wi-Fi · WhatsApp + Blinkit trees dumped · TalkBack behavior known · Sarvam STT/TTS works
- [x] M1 Text agent — F1 in text mode (12 Oct PM) — **3/3 from WhatsApp chat list, 12–15 s, ~₹0.06/run (gpt-5-nano)**
- [x] M2 Voice in/out (12 Oct night) — **owner tested by voice: reads Mummy's last message aloud**
- [ ] M3 Safety gate + GPay ₹1 to PIN handoff (13 Oct AM)
- [ ] M4 Blinkit + screen summary (13 Oct PM)
- [ ] M5 Eval + hardening (13 Oct night)
- [ ] M6 Blind / low-vision tester session (14 Oct AM)
- [ ] Feature freeze (14 Oct 12:00)
- [ ] M7 Demo video + README + Devpost submitted (14 Oct night)

---

## Decision log

| Date | Decision | Why |
|------|----------|-----|
| 12 Oct | Build Saathi (over Varnan, Backtrack, PriceTwin) | Highest impact + Aadi's own idea; clear India gap |
| 12 Oct | Android only | ~92% of Indian phones; iOS doesn't allow apps to operate other apps |
| 12 Oct | Laptop-tethered prototype over wireless ADB | Fastest path in ~80 h; on-device app is the production path |
| 12 Oct | Own agent loop on Mobilerun `AndroidDriver` (not Mobilerun's agent) | Safety gate must inspect every action before execution |
| 12 Oct | Safety gate = deterministic code + unit tests | Money and messages can't depend on model judgment |
| 12 Oct | Sarvam for Hindi speech, OpenAI for reasoning + vision | Best Hindi voice quality; OpenAI is the sponsor |
| 12 Oct | 3 flows only (WhatsApp, GPay, Blinkit) + screen summary | Reliability over breadth |
| 12 Oct | Dev device: Samsung Galaxy Tab S6 Lite LTE (SM-P615, Android 13) | POCO X5 (HyperOS) refused ADB installs; tablet worked after the owner paused Play Protect for the Portal install |
| 12 Oct | Project depends on `mobilerun-core-local` only; `mobilerun` CLI installed separately as a uv tool | `mobilerun` pulls in llama-index / arize-phoenix we don't use |
| 12 Oct | Portal over TCP (`use_tcp=True`, localhost ADB forward) | Default content-provider channel took ~5 s to answer the probe and failed it ~50% of the time over Wi-Fi |
| 12 Oct | Fixture dumps require Portal (`portal_mode="required"`) | Silent uiautomator fallback has different keys; normalizer still accepts both |
| 12 Oct | `ADB_SERIAL` = mDNS name (`adb-<serial>-<id>._adb-tls-connect._tcp`) | Wireless-debugging port changes on every toggle; mDNS name doesn't |
| 12 Oct | Raw screen dumps in `tests/fixtures/raw/` (gitignored); commit only anonymised fixtures | Dumps contain personal chats / payment screens |
| 12 Oct | Keep TalkBack suspend/restore strategy, but taps don't require it | Taps click even with TalkBack on (Samsung, Android 13); suspend avoids two voices talking at once — re-evaluate by ear in M2 |
| 12 Oct | STT: `saaras:v4` (+ `keyterms` for contact names) | Docs now recommend v4; all v3/v4 variants were correct on the test phrase; only v4 supports `keyterms` |
| 12 Oct | TTS: `bulbul:v4-flash`, default voice | Owner preferred it by ear over `bulbul:v3`/shubh; 0.8 s vs 1.0 s |
| 12 Oct | LLM client provider-neutral (OpenAI SDK + configurable base URL); pick model by benchmark in M1 | Owner's "Hermes" is Hermes Agent (wraps Claude), not a callable model; local Ollama `qwen2.5vl:7b` is a free fallback |
| 12 Oct | Model: OpenAI `gpt-5-nano` (paid, data sharing OFF); Ollama `qwen3:4b` as offline backup only | Probe on real WhatsApp screen + screenshot: gpt-5-nano / gpt-6-luna / gpt-5.4-nano / gpt-5.4-mini all chose the right tap with strict JSON + Devanagari; nano cheapest (~₹0.03/step, 6.9 s). Local qwen3:4b was right but ~35–40 s/step on the 8 GB Mac; qwen2.5vl:7b doesn't fit in RAM. Free "shared traffic" tokens rejected: would send personal chats/payment screens to training |
| 12 Oct | Until M3, a safety `hold` (send / pay / order) cancels instead of asking | No spoken confirmation yet; confirmation defaults to cancel (hard rule 5) |
| 12 Oct | Saathi restores the user's keyboard on exit (`device.restore_keyboard()` in `finally`) | `AndroidDriver.connect()` silently switches the IME to Portal's keyboard, which has no visible keys — left the tablet without a usable keyboard during M0 |
| 12 Oct | Safety R10 also blocks calls and "Payments on WhatsApp"; R2 blocks newlines typed into WhatsApp | Calls are outward actions; "Enter is send" can turn a newline into a send that bypasses the R2 hold |
| 12 Oct | `open_app` waits up to 8 s for a screen change (taps 3 s) | WhatsApp cold start took ~3.5 s and was miscounted as a failed step |
| 12 Oct | Push-to-talk defaults to Enter (press / press); SPACE-hold only if macOS Input Monitoring is already granted | Input Monitoring would let the editor read every keystroke system-wide; not worth it for a prototype (security first) |
| 12 Oct | Narrator transliterates Roman-only lines to Devanagari (Sarvam `/transliterate`) before TTS | Bulbul docs: romanized Hindi "significantly reduces output quality"; gpt-5-nano ignores the Devanagari rule ~50% of the time |
| 12 Oct | WhatsApp messages tagged `from=me` / `from=them` in the normalizer (row has a `status` tick = sent by user) | First voice run read the user's own reply as "Mummy's last message" |
| 12 Oct | `.env` uses `SAATHI_LANG` (not `LANG`) | macOS `LANG` overrides `.env` in pydantic-settings |
| 12 Oct | Rename `.env` `LANG` → `SAATHI_LANG` (when voice.py lands) | macOS shell already sets `LANG` (e.g. `en_US.UTF-8`), which overrides `.env` in pydantic-settings |

---

## Open questions (resolve during M0)

1. ~~Do ADB-injected taps click or just move focus when TalkBack is on? Does suspend/restore work cleanly?~~ **Answered 12 Oct:** taps **click** with TalkBack on (tap on Settings → About → "Status information" opened the page; Samsung TalkBack, Android 13). Suspend/restore **works**: TalkBack removed, Portal kept, tap clicked, TalkBack restored and re-bound (`touchExplorationEnabled=true`). Service names: Portal `com.mobilerun.portal/com.mobilerun.portal.service.MobilerunAccessibilityService`, TalkBack `com.samsung.android.accessibility.talkback/com.samsung.android.marvin.talkback.TalkBackService`. Caveat: one device; Google TalkBack untested.
2. ~~Exact shape of `get_ui_tree()`; foreground package?~~ **Answered:** `{a11y_tree: node, phone_state: {packageName, currentApp, activityName, keyboardVisible, isEditable, focusedElement}, device_context: {screen_bounds, display_metrics, ...}}`. Nodes: `className, text, contentDescription, hint, resourceId, boundsInScreen, isClickable, isEditable, isPassword, isVisibleToUser, isShowingHintText, children…`. Foreground package = `phone_state.packageName` (no dumpsys needed). Modal sheets hide the screen behind them (only the popup is in the tree). Icon fonts leak private-use glyphs (Blinkit) — normalizer strips them.
3. ~~Is `mobilerun_core_local` part of `mobilerun`?~~ **Answered:** separate PyPI package `mobilerun-core-local`; `mobilerun` depends on it. `from mobilerun_core_local import AndroidDriver` works.
4. ~~Sarvam endpoints / model IDs~~ **Answered (docs.sarvam.ai, 12 Oct):** STT `POST https://api.sarvam.ai/speech-to-text` (multipart `file`, `model` = `saaras:v4` default | `saaras:v3`, `mode` v3-only, `language_code` incl. `unknown`, `keyterms` v4-only). TTS `POST https://api.sarvam.ai/text-to-speech` (JSON `text`, `language_code`, `model` = `bulbul:v3` | `bulbul:v4-flash`, `speaker`; returns base64 WAV in `audios[0]`). Header `api-subscription-key`. Measured: STT ~2.1–2.5 s for 5 s audio, all variants transcribed "राहुल को एक रुपया भेजो।" correctly; TTS 0.8–1.0 s.
5. **Pending.** GPay not installed on the tablet (needs bank-linked SIM). Dump `gpay_home` + `gpay_amount` on a device with GPay before M3 (13 Oct AM).
6. **Pending (owner).** Contact NAB / VIT disability cell today.

## Key facts for the pitch (with sources)

- ~40M visually impaired Indians, ~4.8M fully blind — https://tcd.thecore.in/p/enabling-differently-abled
- UPI/banking apps: unlabeled icons, dynamic menus, gesture navigation, no audio CAPTCHAs, OTP timers — https://www.findevgateway.org/blog/2026/06/indias-digital-payments-boom-is-leaving-persons-with-disabilities-behind
- Best agent for blind users: 52.5% success (GPT-5) — https://arxiv.org/pdf/2609.00524
- Android 92.4% of Indian mobile OS share (Jul 2026) — https://gs.statcounter.com/os-market-share/mobile-/india
- Closest products: AGI Inc narration mode (general, English-first), RealSAM Pocket (closed voice phone)

---

## Daily log

### 12 Oct
- M0: repo scaffolded (uv, Python 3.12), git init. adb + Mobilerun CLI installed.
- POCO X5 5G paired but blocked ADB installs (Install via USB off); cleaned up and switched to Galaxy Tab S6 Lite.
- Portal 0.7.25 installed after Play Protect paused by owner; `mobilerun ping` OK over Wi-Fi hotspot.
- Dumped: WhatsApp home, WhatsApp chat, WhatsApp notification popup, Blinkit search (+ a uiautomator-format dump). Normalizer + 15 tests pass.
- Latency over hotspot: tree ~1.7 s, screenshot ~1.2 s (budget says observe ≤ 1 s) — re-measure on home Wi-Fi / USB in M1.
- TalkBack: taps click with it on; suspend/restore verified. Sarvam STT + TTS hello-world works.
- Model: gpt-5-nano chosen (paid, no data sharing); local Ollama too slow on the 8 GB Mac.
- M1 done: actions + safety (R1–R4, R7–R10, 60 tests) + agent loop + traces. "WhatsApp kholo aur Mummy ka last message padho" 3/3 from the chat list (open_app → tap chat → done), answer verified against the lowest message_text on screen.
- M2 done: voice.py (Enter push-to-talk, saaras:v4 STT, bulbul:v4-flash TTS, phrase cache, transliteration), narrator.py, controls.py (interrupt). Bug found by owner: read the user's own "achaa" as Mummy's message → fixed with sender tagging; verified she gets her last received message.

### 13 Oct
- 

### 14 Oct
-