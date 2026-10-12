# Saathi Android App — Design (Stitch)

Design for the on-phone app specified in `ANDROID_APP.md`. Design only, no app code yet.

- **Stitch project:** [Saathi Android App](https://stitch.withgoogle.com/projects/2747837640608992704) (private) — `projects/2747837640608992704`
- **Claude Design canvas (animated, clickable prototype of all 12 screens):** https://claude.ai/artifact/DBXqZFNNdRfqftd1CoAJb2 (private until shared from its Share menu). Press Play on any frame; every screen links onward and home. Tweaks: light/dark, sound on/off.
- **Design system:** "Saathi — Calm Glass" — `assets/3809964917283612188` (current). The first, flat "Accessible Calm" system (`assets/16296441134583817563`) was rejected by the owner as harsh (13 Oct).

## Design system — Calm Glass

**Style:** stylish, simple, soothing glassmorphism. A soft aurora background (blurred indigo / marigold / mint blobs on pale `#f4f2ff`) behind frosted-glass cards (white 72–80 % + 24–32 px backdrop blur, 1 px white inner border, very soft shadow, 32 dp corners). No thick outlines, no grey text. Text never sits directly on the blobs, only on glass, which keeps AAA contrast.

**Glass fallback:** if blur isn't available (Android < 12, battery saver) or the user has High-contrast text on, glass becomes solid with the same layout and colors.


| Token | Light | Dark | Contrast |
|---|---|---|---|
| Background | `#f4f2ff` + aurora blobs | `#0e0c26` + dim blobs | decoration only |
| Glass card | white 75 % + blur | `#1c1940` 75 % + blur | — |
| Text | ink `#17153b` | `#fbfaff` | 16.8 : 1 / 18.4 : 1 |
| Primary button | deep indigo `#2b22b8` + white | light indigo `#c9c6ff` + ink | 10.4 : 1 / 11.8 : 1 |
| Read-back card | marigold `#ffb627` + ink | same | 9.9 : 1 |
| Success | mint `#19c99a` + ink | same | 8.2 : 1 |
| Stop / danger | `#8e1414` + white | same | 9.3 : 1 |
| Decoration only (rings, glows, focus ring) | indigo `#4338f2` | marigold `#ffb627` focus ring | not used for text |

**Contrast changes from the website palette (measured, WCAG AAA = 7 : 1):**
- Indigo `#4338f2` + white text = 6.8 : 1 → filled buttons use deep indigo `#2b22b8`.
- Stop red `#c62828` + white text = 5.6 : 1 → stop buttons use `#8e1414`; `#c62828` only for borders / edge flash.
- Indigo on dark background = 2.8 : 1 → dark theme uses light indigo `#c9c6ff`.

**Type:** Anek Devanagari (headings: 44 / 32 / 26 px) + Mukta (body 20–22 px, button label 24 px bold, English secondary 18 px). Stitch's font list has neither, so the project tokens fall back to Lexend + Atkinson Hyperlegible Next; the real fonts are named in the design notes and screen prompts. Layouts must survive 200 % font scale.

**Targets:** one primary target per screen ≥ 120 dp; secondary ≥ 72 dp, full width, icon + bilingual text; never icon-only; 20 dp between stacked targets; 24 dp corners; 3 dp focus ring.

**Labels:** Hindi (Devanagari) first and bigger, English second: `बोलिए · Speak`.

## Global motion, sound and haptics (ANDROID_APP.md §6)

| Event | Sound | Vibration |
|---|---|---|
| Start listening | short rising chime | 1 tap (40 ms) |
| Stop listening | short falling chime | 1 tap |
| Working (every ~4 s of silence) | soft tick | — |
| Needs your answer | two-note question chime | 2 taps |
| Done | warm resolve chord | 1 long (120 ms) |
| Cancelled / stopped | low single tone | 2 short |
| Hand-over to you | distinct bell | 3 taps |

**Remove animations** (Android setting, `Settings.Global.ANIMATOR_DURATION_SCALE == 0` / `TRANSITION_ANIMATION_SCALE == 0`): every visual transition becomes instant and loops (pulse, rings) stop; sounds and vibrations stay exactly the same.

## Global TalkBack rules
- Every control has a spoken label in the chosen language (e.g. "हिंदी, Hindi, चुना हुआ").
- Focus order = top to bottom as drawn; the status line is a polite live region (spoken on change).
- No gesture-only actions, no time limits, nothing auto-confirms.
- Confirm screens: Cancel is first in focus order and the default.

## Screens

### 1. Welcome & language
- Glass status pill "साथी बोल रहा है… · Saathi is speaking"; glowing glass orb logo; heading "नमस्ते, मैं साथी हूँ / Hello, I'm Saathi"; "अपनी भाषा चुनिए · Choose your language"; three 120 dp glass cards (हिंदी / English / हिंग्लिश), selected = deep indigo glass + "चुना हुआ · Selected" + check icon (not color alone); glass mic pill "या बस बोलिए: “हिंदी” · Or just say it".
- **Sound/haptics:** greeting spoken on open; start-listening chime + 1 tap when the voice prompt opens the mic; 1 long (120 ms) + resolve chord on choice.
- **Animation:** aurora blobs drift very slowly (20 s loop); the orb glows gently while Saathi speaks; heading + cards fade up 200 ms, ease-out, 60 ms stagger; the selected card fills with indigo glass in 200 ms. Remove animations → blobs and orb are still, everything appears instantly.
- **TalkBack:** focus starts on the heading (greeting already spoken, not repeated); each choice reads "हिंदी, Hindi, बटन, चुना हुआ". Saying the language works too.
- Stitch screen: `projects/2747837640608992704/screens/be16712006674ab59afd9884619dacf0`

### 2. Permissions walkthrough (step 1 of 4: Accessibility)
- One permission per step, in this order: **Accessibility service → Microphone → Camera (walk mode) → Notifications**. Each step: glass status pill, "चरण 1 / 4 · Step 1 of 4" (text is the real indicator; dots are decoration), glass orb with the permission's icon, heading "फ़ोन चलाने की अनुमति / Let Saathi use your phone", a glass card with three plain reasons (reads the screen and taps only when asked · always asks before money or messages · never reads PINs or passwords), an honest privacy note, 120 dp "अनुमति दें · Allow · opens Settings", 72 dp "अभी नहीं · Not now".
- **Privacy note must stay truthful:** "इतिहास और सेटिंग्स सिर्फ़ आपके फ़ोन पर · History and settings stay on your phone" + "काम के समय स्क्रीन का टेक्स्ट AI को भेजा जाता है, सेव नहीं होता · Screen text is sent to AI only while working, never stored". (Stitch first wrote "your data stays on your phone", which is false: screen text goes to OpenAI and voice to Sarvam via the proxy, ANDROID_APP.md §3.) The proxy must actually not store screen text, or this line changes.
- "Not now" skips that step; Saathi says what won't work without it (e.g. no mic → text only) and offers it again later from Settings. Saathi never grants a permission itself (R10). Accessibility opens Android's own Settings page; Saathi speaks the path there.
- **Sound/haptics:** the explanation is spoken on open; "Allow" → leaves for Settings; on return, a granted permission gives the done chord + 1 long vibration (120 ms), a refused one gives the low tone + 2 short, then plainly says what is limited.
- **Animation:** the step content slides left 250 ms ease-out between steps; the dot fills 200 ms. Remove animations → instant swap.
- **TalkBack:** order = status → step "चरण 1 / 4" → heading → each reason → privacy note → Allow → Not now. No swipe-only paging; the next step comes only from the buttons. Coming back from Settings, focus lands on the new step heading.
- Stitch screen: `projects/2747837640608992704/screens/12316828465f44ebad0f9ba425674e30`. Known mockup glitches to fix in code: 24 dp side margins on the bottom buttons; the privacy note's English line was rendered at 16 px (must be 18 px).

## Screens 3–12 (both versions follow the same spec)

Claude canvas file per screen in brackets. Stitch screen IDs are listed under "Stitch screens" below.

### 3. Home [Home.dc.html]
- Status "तैयार हूँ · Ready", small orb + greeting, "सेटिंग्स · Settings" glass pill, giant 260 px mic "बोलिए · Speak", hint "Volume-down दबाकर भी बोल सकते हैं", tiles "चलो · Walk mode" and "गतिविधि · Activity".
- Motion: mic breathes (2.4 s) with two offset rings; items fade up in sequence; aurora drifts. Sound: tap mic → rising chime + 40 ms tap.
- TalkBack: status → Settings → greeting → mic → hint → Walk → Activity. Volume-down works anywhere.

### 4. Listening [Listening.dc.html]
- "सुन रहा हूँ… · Listening" → "सुन लिया · Got it"; indigo mic orb with 3 expanding rings, 28-bar waveform, transcript appears word by word; "बस, हो गया · Done" (120 px), "रोकें · Stop" (72 px).
- Sound: start chime + tap on open; Done → falling chime + tap; Stop → low tone + 2 short.
- Remove animations: rings hidden, bars still, full transcript at once.

### 5. Working [Working.dc.html]
- Progress ring around the operated app's badge (slides in); narration lines fade up one by one with mint checks; giant "रोकें · Stop" (120 px). The canvas adds a labelled "Prototype" group to jump to Result / Read-back / Hand-over.
- Sound: soft tick every ~4 s of silence. Narration is a polite live log.

### 6. Read-back / Confirm [Confirm.dc.html]
- Marigold glass card rises (250 ms): "मम्मी को भेज रहे हैं: · Sending to Mummy:" + the exact on-screen text “main 7 baje aaunga” + "Exactly as on screen". **"नहीं · Cancel" first, on top, default, focused**; "हाँ, भेजो · Yes, send" below; voice hint "बोलिए ‘हाँ’ या ‘नहीं’ — unclear means I'll ask again, then cancel".
- Never auto-confirms; no timers. Sound: two-note question chime + 2 taps on open; Cancel → low tone + 2 short, card sinks, "रद्द किया · Cancelled, nothing sent".

### 7. Result [Result.dc.html]
- Mint orb, check draws itself; answer card quotes Mummy's message verbatim with time; "फिर से सुनो · Repeat", "और कुछ? · Something else" (120 px), "होम · Home".
- Sound: done chord + 120 ms buzz on open; Repeat replays speech.

### 8. Hand-over [Handover.dc.html]
- "आपकी बारी · Your turn" — Saathi stopped at a PIN screen: "मैं PIN न पढ़ता हूँ, न डालता हूँ"; mint pill "TalkBack फिर से चालू है"; numbered next steps; "मैं कर चुका · I'm done", "साथी को बुलाएँ · Call Saathi".
- Motion: bell swings, marigold ripple. Sound: distinct bell + 3 taps.

### 9. Walk mode [Walk.dc.html]
- Always dark; camera-like scene, obstacle box with icon + word level (ध्यान / सावधान / रुकिए) and distance, depth heat-map overlay (toggle), red edge flash at "stop", left-arrow hint "बाईं ओर कम रुकावट", giant Stop/Start (124 px), note "सिर्फ़ चेतावनी — सड़क पार करने के लिए नहीं · Warnings only, not for crossing roads".
- Haptics: 80 ms / 200-100-200 / 400-100-400-100-400 per level, only while running. Remove animations: still frame, haptics unchanged.

### 10. Activity [Activity.dc.html]
- Today's summary "4 काम, 1 सुरक्षा रोक" + "सुनाओ · Read aloud"; rows with icon + outcome word (हो गया / रद्द किया / ऑर्डर से पहले रुका / आपको सौंपा / नहीं हो पाया) — never colour alone; "इतिहास मिटाएँ · Delete history" → in-place confirm, Cancel first and default.

### 11. Settings [Settings.dc.html]
- Per-app level (पढ़ना ही / पूछकर / बंद) for WhatsApp, Blinkit, GPay (GPay off by default; "PIN हमेशा आप ही डालेंगे"); money limit ₹500, raising needs confirmation (Cancel default); trusted contacts; voice speed + sample; walk sensitivity; honest privacy notes + "सारा डेटा मिटाएँ" (confirm, Cancel default); link to Safety centre.

### 12. Safety centre [Safety.dc.html]
- Shield orb, "ये नियम हमेशा चालू हैं · Always on — can't be turned off"; 10 rules as rows with lock icons, each expands a one-line "क्यों? · Why"; no toggles anywhere.

## Stitch screens (final, all phone size)

All in [the Stitch project](https://stitch.withgoogle.com/projects/2747837640608992704); IDs are `projects/2747837640608992704/screens/<id>`.

| # | Screen | Stitch screen id |
|---|---|---|
| 1 | Welcome & language | `be16712006674ab59afd9884619dacf0` |
| 2 | Permissions (step 1) | `12316828465f44ebad0f9ba425674e30` |
| 3 | Home | `3fb16b53ce4a469787fa79f183d344d4` |
| 4 | Listening | `368c398d85944946824c8a5163876684` |
| 5 | Working | `01db352f17604801824184fd2511c3b7` |
| 6 | Read-back / Confirm | `ce91f3b8a2ef44f9a5d45a3de6c499a5` |
| 7 | Result | `217a87b7fe4e4b189d83f6bdfc6010f5` |
| 8 | Hand-over | `98b955d039a64e76b2ffd74355b927dc` |
| 9 | Walk mode | `56241d5b5ec3452c896b74261542a27e` |
| 10 | Activity | `f5330eb6e9614c5583536ea8242f4429` |
| 11 | Settings | `fd380bb5a8684650b2efcf6ab9b8807d` |
| 12 | Safety centre | `8704a4870d0c47a58620a53e3584f672` |

Superseded drafts still in the project (kept until the owner says to delete): the first flat Welcome, three pastel variants, desktop-size Welcome / Permissions / Settings, and the first Confirm (it had a wrong "हो गया · Done" bar and a washed-out Yes button). Stitch's default model timed out for ~30 min on 13 Oct; screens 5–9, 11, 12 were made with the faster `GEMINI_3_5_FLASH_LITE` model.

## Open items from the design pass
- Check before shipping: the camera-permission copy says obstacle detection runs on the phone and frames aren't saved; the mic copy says Saathi listens only while the button is held. Both must stay true in the build.
- The proxy must not store screen text (the onboarding privacy line depends on it).
- Exact spacing on Stitch mockups is approximate; the Claude canvas is the reference for layout and motion.
- Stitch mockups have small leftovers: Activity's app labels look under 18 px; the Walk arrow card overlaps the obstacle box. Fix in the build; the canvas is correct.
