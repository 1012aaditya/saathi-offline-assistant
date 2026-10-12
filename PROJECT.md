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
- [x] M3 Safety gate (13 Oct AM) — **gate R1–R11 + spoken confirmation done (88 safety tests); WhatsApp send-hold 3/3 on device; GPay part skipped by owner (13 Oct); real send untested**
- [x] M4 Blinkit + screen summary (13 Oct PM) — **screen summary 3/3; Blinkit 10/10 to order read-back (7/7 from an empty cart), median 33 s; never orders**
- [x] M5 Eval + hardening (13 Oct night) — **device eval: WhatsApp 3/3 ×2 flows, summary 3/3, Blinkit 10/10; timeouts, reconnect, loop detection, R11 (see eval/results.md)**
- [ ] M6 Blind / low-vision tester session (14 Oct AM)
- [ ] Feature freeze (14 Oct 12:00)
- [ ] M7 Demo video + README + Devpost submitted (14 Oct night)

---

## Workstreams (synced 13 Oct from 3 parallel Claude sessions)

| Workstream | Where | State | Owner decisions |
|------------|-------|-------|-----------------|
| **Saathi agent** (voice agent, safety gate, eval) | `saathi/`, `eval/`, `tests/`, `scripts/` on `main` | M0–M2, M4 done; M3 gate done (no payments); 155 tests | **Payments skipped for now** (13 Oct) — payment rules stay in code, not demoed; next: make it more autonomous + user-controlled, on the phone |
| **Website** | `site/` on `main` (static HTML/JS, GSAP) | Landing page, settings + activity prototypes (sample data, not connected), shared Web Speech voice commands; `config.js paymentsLive=false` | International audience, English-first; bright/colorful + heavy motion; settings/activity are previews of the future app; braille = roadmap only |
| **Walk mode** (on-device obstacle warnings) | `walk/` on `main` (merged 13 Oct from `feature/walk-mode`) | Phone web page, 2× YOLOv8n via onnxruntime-web, Hindi spoken warnings + vibration + rough distance; 21 node tests; not yet tested on a real phone | Extra feature, not the main demo; free + on-device; warnings only, never "safe to cross". ⚠ YOLOv8 weights are AGPL-3.0 |

Done 13 Oct: walk mode merged; site shows measured results (not targets), walk mode, "Works now" for Blinkit/summary, payments "Coming later"; PRD/SCOPE/SYSTEM_DESIGN/CLAUDE.md/README synced. **Checkpoint tag: `checkpoint-pre-expo`.**

Next: **Expo phone app** — owner will design it first (not started). Still open: deploy site for the Devpost link; real WhatsApp send test; blind tester (M6); demo video + Devpost (M7); payments later.

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
| 12 Oct | GPay deferred | Owner has no SIM-linked device with GPay yet; R1/R5/R6/R8 tested on synthetic screens only. SCOPE cut line (13 Oct 14:00): switch to PhonePe/BHIM if GPay stays unreadable |
| 12 Oct | Safety read-backs only; the model must never ask "should I send/pay?" itself | Dry run showed the model self-confirming with an ungrounded question, bypassing the R2 read-back |
| 12 Oct | On a cancelled send, Saathi deletes its own draft (key presses) | A blind user wouldn't know an unsent draft is sitting in the box; Portal's clear didn't stick |
| 12 Oct | adb subprocesses get `stdin=DEVNULL` | `adb shell` swallowed typed answers to the confirmation prompt |
| 12 Oct | Blinkit stops at the order read-back unless `ALLOW_ORDERS=true`; eval refuses to run if it is true | Hard rule 6: eval must never place real orders |
| 12 Oct | "Screen pe kya hai?" is enforced read-only in code (any non-done action is blocked) | F4 must never change anything on the phone |
| 12 Oct | Narrate `say` only for actions the gate allows | Model said "Send button dabaya" before the user had confirmed |
| 12 Oct | One automatic reconnect on ADB/Portal errors, then halt | SYSTEM_DESIGN §11; Wi-Fi hotspot link dropped Portal probes during M0 |
| 12 Oct | eval/results.md holds no message text and no device serial | It is committed to a public repo |
| 12 Oct | Never grant Android permissions on the user's behalf (R10) | Model tapped "While using the app" for Blinkit location despite the prompt; permission revoked |
| 12 Oct | Blinkit search: surface the hidden focused EditText, verify typed text, submit with Enter (Blinkit only) | Blinkit hides its search field from accessibility — a real-world example of the problem Saathi solves |
| 12 Oct | Cart guard: no ADD/+ beyond the asked quantity; auto-open cart after a blocked extra ADD | Model kept adding upsell items; one run built a 2-product cart |
| 12 Oct | Read-outs must quote on-screen text verbatim (R4 extended to `done`) | One run paraphrased Mummy's message |
| 12 Oct | Bounded device calls (30 s) + network retries for the model | Eval froze 16 min on one device call; hotspot internet dropped mid-run |
| 12 Oct | Blinkit flow is mostly deterministic (`saathi/blinkit.py`): search deep link, card ranking, ask once on a close call, size picker, cart, checkout; the model only handles unknown screens | Model-driven Blinkit was 0–2/3; scripted path 10/10 |
| 12 Oct | Safety R11: block a tap whose point is covered by an order/pay/send control; a target inside such a control gets that control's hold | In landscape, Blinkit's floating "Place Order" bar covered a "−" button; the gated tap landed on the bar (no order placed) |
| 12 Oct | Order read-back lists the cart items verbatim and flags "Shipment of N items" when some are off-screen | Old fallback could read a recommended item's price as the total |
| 13 Oct | Skip payments for now; focus on autonomy + user control for blind users, on the phone | Owner choice: bank-SIM setup + privacy trade-offs vs. ~1 day to feature freeze; WhatsApp send-hold + Blinkit order-hold already demo the confirm-before-irreversible design |
| 13 Oct | One working session from here (`3-days-43`); website and walk-mode sessions stopped editing | Three sessions shared one folder; `git add -A` had swept `site/` into agent commits |
| 13 Oct | **Android-first native app**; the agent runs on the phone via an AccessibilityService (no laptop); API keys behind a small proxy; walk mode native + on-device depth. Spec: `ANDROID_APP.md`; screens to be designed in Google Stitch (MCP `stitch`, project-local config) | Laptop tether, browser latency and iOS limits; Android is ~92% of Indian phones and the only platform that allows operating other apps |
| 13 Oct | Walk mode: depth candidate = Depth Anything V2 Small int8 (Apache-2.0, 27 MB): 324 ms on iPhone with 1 thread, MiDaS small (64 MB) failed to load in Safari | Benchmark `walk/bench.html`; multi-thread result pending (Safari needs real COOP/COEP headers: `scripts/serve_walk.py`) |
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
- First device eval (3 runs/flow): whatsapp_read 3/3 (after verbatim-quote fix), whatsapp_reply_hold 3/3, screen_summary 3/3, blinkit_milk ~2/3. GPay skipped (not installed).
- Blinkit cut-line check (needs >= 5/10): passed early with 10/10 (7/7 from an empty cart). F3 stays.
- M3–M5 built (owner asked to build first, test later): confirmation + R5/R6/R8 + draft cleanup; Blinkit card + order hold; F4 read-only summary; reconnect; eval harness (flows.yaml, run_flows.py, results.md). 112 unit tests. **Device test pending** for M4/M5 and the real WhatsApp send.
- M2 done: voice.py (Enter push-to-talk, saaras:v4 STT, bulbul:v4-flash TTS, phrase cache, transliteration), narrator.py, controls.py (interrupt). Bug found by owner: read the user's own "achaa" as Mummy's message → fixed with sender tagging; verified she gets her last received message.

### 13 Oct
- Owner: skip payments for now; make Saathi more autonomous + controlled for blind users, on the phone; Expo app next (owner designs it first).
- Synced 3 parallel sessions (agent, website, walk mode) into one; merged walk mode; site + docs updated to measured results. Checkpoint `checkpoint-pre-expo`.

### 14 Oct
-