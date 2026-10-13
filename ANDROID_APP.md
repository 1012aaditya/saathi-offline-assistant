# Saathi Android App — Spec & Design Brief

**Status:** design phase (13 Oct 2026). Decision: **Android-first native app; the agent runs on the phone (no laptop)**. The hackathon demo still uses the laptop prototype; this app is the post-hackathon product.
**Design tool:** Google Stitch (MCP `stitch`, configured for this project). Generate screens from §5–§7 of this brief.

---

## 1. Who it's for
- **Primary:** blind users who already use TalkBack (e.g. Ramesh, 34, Lucknow, Hindi + some English, ₹12k Android phone).
- **Secondary:** low-vision and elderly users who never learned TalkBack gestures.
- Design must work **with eyes closed first**, then look clear and calm for low vision and for family members helping with setup.

## 2. What the app does (everything built so far)

| Feature | Today (prototype) | In the app |
|---|---|---|
| **Read messages** — "Mummy ka last message padho" | Laptop agent, 3/3 on device | On-phone agent |
| **Reply** with read-back + "haan" | 3/3 to the read-back | Same, sends after a clear yes |
| **Unread summary** — "Kiske message aaye hain?" | Scripted, 0 model calls | Same |
| **Screen summary** — "Screen pe kya hai?" | Read-only, 3/3 | Same |
| **Blinkit order** to the read-back, never orders without yes | 10/10 | Same, real orders behind a setting |
| **Payments (GPay/PhonePe)** | Rules built + tested, not run | Later; PIN always entered by the user |
| **Walk mode** — obstacles, walls/approach, direction hints | Web, ~260 ms/frame | Native, real-time on the phone's AI chip; depth heat map |
| **Voice controls** — repeat, slower/faster, help, bas, follow-ups | Laptop | Same + volume-key push-to-talk |
| **Safety gate R1–R11** | 88 tests | Ported to Kotlin with the same tests |
| **Settings, activity history** | Website prototypes (sample + real anonymised data) | Real, stored on the phone |

## 3. Architecture (no laptop)

```
Phone (Kotlin)
├── SaathiAccessibilityService   reads screens + taps (replaces ADB + Mobilerun Portal)
├── Agent loop + SafetyGate      R1–R11 ported 1:1 from saathi/safety.py, same unit tests
├── Scripted flows               WhatsApp unread, Blinkit (from saathi/whatsapp.py, blinkit.py)
├── Voice                        mic / speaker / earbuds; volume-key push-to-talk
├── Walk mode                    CameraX → detector (NNAPI/GPU) + depth (Depth Anything V2 S)
│                                + looming; ARCore Depth where available
└── Local store                  settings, activity, traces (private, deletable)
        │  https
        ▼
Small proxy server (holds API keys; never in the app)
├── OpenAI (decide next step)    ├── Sarvam STT / TTS / transliterate
```

- Walk mode works **offline**; the agent needs internet (model + speech).
- **Models:** replace AGPL YOLOv8 with a permissive detector (e.g. RT-DETR / SSD-MobileNet, Apache/MIT) before a closed-source release. Depth Anything V2 **Small** is Apache-2.0 (Base/Large are non-commercial).
- **Play Store:** apps using AccessibilityService must meet Google's accessibility-tool policy — verify current requirements before submission.
- **iPhone:** no agent (iOS forbids operating other apps); walk mode + voice only, later.

## 4. Design principles
1. **Voice and touch-free first.** Every action reachable by voice or the volume keys; the screen is a bonus.
2. **One clear thing per screen.** One primary action (≥ 64 dp tall, full width; Home mic 200 dp; Stop 72 dp), never small icon-only buttons.
3. **TalkBack-perfect.** Every element has a spoken label, logical focus order, no gesture-only actions, no time-limited UI.
4. **Calm, high contrast.** WCAG AAA text contrast (7:1) for body text; large type (body ≥ 20 sp, headings ≥ 32 sp); supports 200 % font scale; dark and light themes.
5. **Universal, follows the phone's language.** One language at a time (no bilingual labels); English mockups; Hindi is one of many languages. Every screen shows "You can say …" phrases; Volume-down is push-to-talk everywhere.
6. **Never surprise with money or messages.** Read-back screens are visually distinct (marigold) and always show Cancel as the default, largest-safe option.
7. **Apple-style minimal ("Clarity", 13 Oct).** White / black backgrounds, iOS-style large titles and inset grouped lists, the system font, one accent (indigo `#2b22b8`; dark `#c9c6ff`), stop `#8e1414`, "needs your answer" yellow `#ffd60a`. No glass, gradients, glows or decorative illustration. Full spec: `DESIGN.md`.

## 5. Screens

| # | Screen | Purpose / key elements |
|---|---|---|
| 1 | **Welcome & language** | "नमस्ते, मैं साथी हूँ" spoken on open; Hindi / English / Hinglish big choices |
| 2 | **Permissions walkthrough** | One permission per step, spoken explanation, why it's needed: Accessibility service, microphone, camera (walk mode), notifications. Plain-language privacy note |
| 3 | **Home — "बोलिए"** | One giant mic button (whole lower half), current status line, two secondary tiles: **चलो (Walk mode)**, **गतिविधि (Activity)**. Hint: "Volume-down दबाकर बोलिए" |
| 4 | **Listening** | Live waveform/ring reacting to voice, transcript appears word by word, "Stop" target |
| 5 | **Working** | Step narration ("WhatsApp खोल रहा हूँ…"), progress ring, app icon of the app being operated, big **रोकें (Stop)** |
| 6 | **Read-back / confirm** (send, order, pay) | Marigold card: who/what/amount **exactly as on screen**; huge **नहीं (Cancel)** and **हाँ, भेजो (Yes)**; voice "haan/nahi" works; unclear → asks again → cancels |
| 7 | **Result** | Answer card (e.g. Mummy's message, quoted), "फिर से बोलो" (repeat), "और कुछ?" |
| 8 | **Hand-over** (PIN, CAPTCHA) | "आपकी बारी" — Saathi has stopped; TalkBack restored; what to do next |
| 9 | **Walk mode** | Full-screen camera, live obstacle overlay + **depth heat map** (low vision), giant Start/Stop, status line ("सामने कुर्सी, लगभग 2 मीटर"), direction hint arrows (left/right "कम रुकावट") |
| 10 | **Activity** | Today's tasks spoken summary, list with outcomes (done / cancelled / handed over / didn't work), safety stops count; delete history |
| 11 | **Settings** | Apps & permission levels (read-only / with confirm / off), money limits (confirm-to-raise, default cancel), trusted contacts, voice (speed, voice), walk-mode sensitivity, privacy (delete data) |
| 12 | **Safety centre** | Plain-language list of the always-on rules (R1–R11), cannot be turned off |

## 6. Motion design (sound, touch and screen)
For blind users "motion" is mostly **audio and haptics**; visuals serve low-vision users and sighted helpers.

**Earcons + haptics (consistent everywhere)**
| Event | Sound | Vibration |
|---|---|---|
| Start listening | short rising chime | 1 tap (40 ms) |
| Stop listening | short falling chime | 1 tap |
| Working (every ~4 s of silence) | soft tick | — |
| Needs your answer (read-back) | two-note question chime | 2 taps |
| Done | warm resolve chord | 1 long (120 ms) |
| Cancelled / stopped | low single tone | 2 short |
| Hand-over to you | distinct bell | 3 taps |
| Walk: mention / warn / stop | — | 80 ms / 200-100-200 / 400-100-400-100-400 |

**Visual motion (low vision, sighted helpers)**
- Mic button: breathing pulse when idle (2.4 s loop), ring expands with voice level while listening.
- Working: progress ring + the operated app's icon sliding in; each narration line fades up (200 ms).
- Read-back card: rises from bottom (250 ms ease-out), marigold glow; **never auto-confirms**.
- Walk mode: obstacle boxes color by level (yellow → orange → red), red edge flash on "stop", heat-map overlay fades with distance; left/right hint as a large arrow.
- Transitions: 200–300 ms, ease-out; **respect "Remove animations"** (Android) → instant transitions, all audio/haptics unchanged.

## 7. Stitch prompts (superseded 13 Oct — see DESIGN.md for the Clarity prompts)
1. *Design system:* "Accessible Android app 'Saathi' for blind and low-vision Indian users. Calm, high contrast, WCAG AAA, huge touch targets (≥120dp), Hindi-first bilingual labels in Anek Devanagari + Mukta. Colors: indigo #4338f2, deep indigo #2b22b8, marigold #ffb627, mint #19c99a, stop red #c62828, paper #fbfaff, ink #17153b. Light and dark themes."
2. One prompt per screen in §5, e.g. *Home:* "Home screen: one giant circular mic button filling the lower half labelled 'बोलिए · Speak', status line on top, two large tiles below: 'चलो · Walk mode' and 'गतिविधि · Activity'. Hint text 'Volume-down दबाकर बोलिए'. Dark theme variant."
3. *Read-back:* "Confirmation screen: marigold card showing 'Mummy को भेज रहे हैं: \"main 7 baje aaunga\"', two huge buttons stacked: 'नहीं · Cancel' (default, outlined, top) and 'हाँ, भेजो · Yes, send' (filled indigo)."
4. *Walk mode:* "Full-screen camera view with obstacle boxes (yellow/orange/red), translucent depth heat map overlay, large 'रोकें · Stop' button at the bottom, status bar text 'सामने कुर्सी, लगभग 2 मीटर', left arrow hint 'बाईं ओर कम रुकावट'."

## 8. Build order (after design)
1. Accessibility service + safety gate (Kotlin) + proxy server; port unit tests.
2. Voice loop (push-to-talk, STT/TTS via proxy) + WhatsApp read/unread.
3. Read-back/confirm + reply; Blinkit scripted flow.
4. Walk mode native (detector + looming + depth), haptics.
5. Settings, activity, onboarding; Play Store accessibility declaration.
