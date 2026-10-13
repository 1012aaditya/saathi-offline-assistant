# Saathi Android App — Design ("Clarity")

Design for the on-phone app in `ANDROID_APP.md`. Design only, no app code yet.

**Direction (owner, 13 Oct):** Apple-style, minimalist, universal (not India-only), everything voice-enabled and accessible. Customers are blind first; the screen serves low-vision users and sighted helpers. The agent itself runs on Android only (iOS doesn't allow operating other apps); "Apple-style" is the visual language.

## Where the designs live
- **Claude Design canvas — animated, clickable prototype of all 12 screens (the reference for layout and motion):** https://claude.ai/artifact/6TNFrpd5aAKoLGteCzM8z3 (private until shared from its Share menu). Press Play on any frame; every screen links onward and home. Tweaks: light/dark, sound on/off.
- **Google Stitch:** [project](https://stitch.withgoogle.com/projects/2747837640608992704) `projects/2747837640608992704`, design system "Saathi — Clarity" `assets/8447155502394253629`. Screen IDs below.

## Design system
| Token | Light | Dark | Contrast (WCAG AAA ≥ 7:1) |
|---|---|---|---|
| Screen | `#ffffff` | `#000000` | — |
| Grouped rows / cards | `#f2f2f7` (on white screen) | `#1c1c1e` | — |
| Primary text | `#000000` | `#ffffff` | 18.8 : 1 on `#f2f2f7` |
| Secondary text | `#3a3a3c` | `#d1d1d6` | 10.2 : 1 / 11.2 : 1 |
| Accent (one only) | indigo `#2b22b8` + white text | `#c9c6ff` + black text; accent text `#a8a4ff` | 10.4 : 1 / 13.0 : 1 / 7.6 : 1 |
| Stop / destructive | `#8e1414` + white | same; destructive text `#ff8a80` | 9.3 : 1 / 7.5 : 1 |
| "Needs your answer" | `#ffd60a` capsule, black text | same | 14.9 : 1 |
| Separators | `#c6c6c8` hairline, inset | `#38383a` | decoration |

- **Type:** the system font (`-apple-system, "SF Pro", system-ui`; on Android the system font). Large Title 40/700, Title 28/700, Headline 22/600, Body 20, Callout 18; nothing under 17 px; supports 200 % font scale.
- **Layout:** 20 px margins, iOS-style nav row ("‹ Back" text link), large title, inset grouped lists (16 px radius, rows ≥ 64 px), one filled primary button (64 px, full width), secondary = grey fill, tertiary = accent text. Home mic 200 px circle; Stop 72 px.
- **No** glass, gradients, colour blobs, glows, drop shadows or illustrations. Icons are thin SF-Symbols-like strokes, always with a text label.
- **Language:** follows the phone; one language at a time; mockups in English (Hindi is one entry in the language list).

## Voice everywhere
- Every screen ends with **"You can say …"** and 2–3 phrases for that screen, plus a 56 px talk button.
- Volume-down = push-to-talk on every screen. Always-available commands: "Stop", "Repeat", "Slower", "Faster", "Help", "Go home".

## Motion, sound, haptics (ANDROID_APP.md §6)
| Event | Sound | Vibration | Visual |
|---|---|---|---|
| Start listening | rising chime | 1 tap 40 ms | voice orb scales with voice, 5-bar meter |
| Stop listening | falling chime | 1 tap | orb settles |
| Working | soft tick every ~4 s of silence | — | thin progress ring; steps fade in, checkmarks draw |
| Needs your answer | two-note chime | 2 taps | bottom sheet slides up 300 ms over dimmed screen |
| Done | warm chord | 1 long 120 ms | checkmark draws |
| Cancelled | low tone | 2 short | sheet slides down |
| Hand-over | bell | 3 taps | bell icon rings twice |
| Walk notice / careful / stop | short tone | 80 ms / 200-100-200 / 400-100-400-100-400 | box yellow → orange → red, red edge flash on stop |

Screen content fades and rises 12 px over 350 ms with a 40 ms stagger. **Remove animations** (Android) = `prefers-reduced-motion`: all motion off, final states shown; sounds and vibrations unchanged.

## TalkBack
Spoken label on every control; focus order = visual order; status line is a polite live region; no gesture-only actions; no time limits (setup never auto-advances); nothing auto-confirms; confirm sheets put **Cancel first, on top, focused and default**.

## Screens
| # | Screen | What's on it | Stitch screen id |
|---|---|---|---|
| 1 | Welcome | "Hi, I'm Saathi", one-line promise, Language row ("English · From your phone", expands to a list), Continue | `32a0e1e21d864056ba80769a65f9b97d` |
| 2 | Setup (4 steps) | Accessibility → Microphone → Camera → Notifications; step counter + progress bar, reasons list, honest privacy footnote, Allow / Not now (then "what won't work" + Next), done state "You're all set" | `e990127c66eb428eb1846aec48e046c5` (step 1) |
| 3 | Home | "Good evening", "Ready", 200 px Speak circle, "or hold Volume-down", Walk mode ›, Activity ›, Settings link | `b394ad3c31a1496bb55f9b29bdbfe8b8` |
| 4 | Listening | voice orb + meter, transcript word by word, Done / Cancel | `8acc08326de544cbb2d3d8efc349bf7c` |
| 5 | Working | progress ring + app name, step list with checks, big Stop | `87956df53f9e4bdc929b3d626b05b044` |
| 6 | Confirm (sheet) | "Needs your answer", "Send to Mom?", the exact text “I'll be home by 7”, "Exactly as typed on screen", **Cancel (default) above Send**, "Say Yes or No — if I'm not sure I'll ask again, then cancel" | `f4b0638ce2314e0b86cab4fb784e31c1` |
| 7 | Result | drawn checkmark, "Mom's last message" card with time, Something else / Repeat / Home | `d33669d69ed0430f9d3c88a3dc0e850c` |
| 8 | Your turn | "I've stopped. This screen asks for a PIN — I never read or type PINs.", "TalkBack is on again", two steps, I'm done / Call Saathi | `a5f7acb7ee0f457eb5c576b60ce7db4c` |
| 9 | Walk mode | black camera view, obstacle box with icon + word + distance, optional depth heat map, "More space on the left", "Warnings only — not for crossing roads", Stop/Start | `85d4e5b89fc64644a89880fee15e9642` |
| 10 | Activity | "Today: 4 tasks, 1 safety stop" + Read aloud, rows with icon + outcome word + time, Delete history (sheet, Cancel first) | `67d32831fcb5430e9f12821954e10dc8` |
| 11 | Settings | Apps (Read only / Ask first / Off; payments Off by default), spending limit (raising needs confirmation), trusted contacts, voice (language, rate, voice), walk sensitivity, privacy notes + Delete all data, Safety centre › | `ebbb3cf20b65439fab83e28455e01764` |
| 12 | Safety centre | "Always on. These can't be turned off." 10 rules with lock icons, each expands a one-line "Why"; no toggles | `9f9d7d6d73c941ec9bdc3c9c53d2f924` |

Navigation (no dead ends): Welcome → Setup → Home; Home → Listening → Working → (Result | Confirm | Your turn); Home → Walk / Activity / Settings → Safety; every screen has a labelled way home.

## Privacy copy (must stay true in the build)
- "History and settings stay on your phone. While I'm working, screen text is sent to an AI service to decide the next step — it isn't stored." → the proxy must not store screen text.
- "Your voice is sent to a speech service to turn it into text." (Sarvam STT.)
- "Walk mode warnings are made on your phone." (on-device detector + depth).

## Open items
- Stitch mockups: Setup shows step 1 only; Settings still has a faint shadow on two segments; Activity's delete sheet exists only in the canvas. The canvas is the reference.
- Superseded Stitch screens (flat, pastel, Calm Glass, early Clarity drafts) are still in the project; delete only when the owner says so.

## History
- 13 Oct: flat high-contrast look → rejected as harsh. "Calm Glass" glassmorphism, Hindi-first bilingual (canvas https://claude.ai/artifact/DBXqZFNNdRfqftd1CoAJb2, Stitch design system `assets/3809964917283612188`) → rejected: owner wants Apple-style minimal and universal. Replaced by Clarity the same day.
