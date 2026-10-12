# Saathi — Product Requirements Document (PRD)

**Version:** 0.1 · **Date:** 12 Oct 2026 · **Owner:** Aadi · **Status:** Building for HACK47: OFFGRID (deadline 15 Oct 2026, 09:30 IST)

---

## 1. One-liner

**Saathi is a Hindi + English voice agent that operates everyday Indian Android apps for blind and low-vision users — including apps their screen reader can't handle — with a hard safety layer for money.**

User speaks: *"Rahul ko 1 rupaya bhejo."* Saathi opens GPay, finds Rahul, enters the amount, reads back *"₹1 Rahul Sharma ko bhej rahe hain. Confirm?"*, waits for *"haan"*, and hands over for the UPI PIN.

---

## 2. Problem

- India has ~40M people with visual impairment, ~4.8M of them fully blind.
- TalkBack (Android's screen reader) only works when apps are built accessibly. Many Indian banking and UPI apps use unlabeled icons, dynamic menus and gesture navigation that screen readers can't interpret; CAPTCHAs lack audio alternatives and OTP timers expire mid-entry. Users are formally included but functionally excluded ("partial inclusion").
- Accessibility fixes regress: apps that get fixed often break again with the next update.
- Existing agents don't fit this user:
  - AGI Inc's phone agent (iOS/Android) shipped a verbose narration mode for blind users — but it's general-purpose and English-first.
  - RealSAM Pocket is a voice-only closed system (calls, texts, books) — it doesn't operate the user's normal apps.
- Agents are unreliable for blind users today: in a 3-week study with 8 blind users, the best model (GPT-5) completed only **52.5%** of commands, and users wanted more than automation — they want understanding and control.

---

## 3. Target users

**Primary (illustrative persona):** Ramesh, 34, blind since birth, lives in Lucknow. Uses a ₹12k Android phone with TalkBack, speaks Hindi and some English. Handles calls and WhatsApp voice notes himself, but asks his nephew to make UPI payments and order groceries because those app screens are confusing with TalkBack.

**Secondary:** people who lost vision later in life, and elderly users with low vision who never learned TalkBack gestures.

---

## 4. Goals (for the hackathon)

| # | Goal | Measured by |
|---|------|-------------|
| G1 | Complete 3 real-world flows end to end, by Hindi voice, on a real phone | Demo + eval harness |
| G2 | ≥ 8/10 success per flow | `eval/run_flows.py` table (vs 52.5% in published research) |
| G3 | Zero payments/messages/orders without explicit spoken confirmation; agent never touches the UPI PIN | Safety unit tests 100% pass + trace review |
| G4 | A demo judges remember: blindfolded/blind user sends ₹1 on GPay by voice, with the read-back on camera | Demo video |
| G5 | Feedback from at least one blind or low-vision tester | Session notes + quote |

### Non-goals

- On-device Android app (prototype runs on a laptop controlling the phone over wireless ADB)
- iOS (Apple doesn't let third-party apps operate other apps; Android is ~92% of Indian phones)
- Supporting every app — 3 flows done well beat 10 done badly
- Replacing TalkBack — Saathi works alongside it
- Full physical-world navigation (Seeing AI, Be My Eyes already do this). *Update 13 Oct: a small on-device "walk mode" (beta) was added as an extra — obstacle warnings only, never route guidance or "safe to cross".*
- Solving CAPTCHAs, reading OTPs, or entering PINs/passwords — always handed to the user

---

## 5. Core flows

### MVP (must ship)

**F1 — WhatsApp read & reply**
1. "Mummy ka last message padho" → Saathi opens WhatsApp, finds the chat, reads the latest message(s).
2. "Reply karo: main 7 baje aaunga" → read-back: *"Mummy ko bhej rahe hain: 'main 7 baje aaunga'. Confirm?"* → "haan" → sends → confirms sent.

**F2 — UPI send money (GPay)** — *paused 13 Oct (owner decision); the safety layer for it is built and unit-tested.*
1. "Rahul ko 1 rupaya bhejo" → opens GPay → searches contact → if several Rahuls, asks which one.
2. Enters amount → **safety gate** reads back name, UPI handle / last digits, amount → waits for explicit "haan".
3. Taps Pay → **PIN handoff**: restores TalkBack, says *"Apna UPI PIN daaliye"*, stops acting.
4. After the user enters the PIN, reads the result screen: *"₹1 Rahul Sharma ko bhej diye. Payment successful."*

### Should ship

**F3 — Blinkit order:** "Blinkit se Amul doodh 1 litre mangao" → search → add to cart → read total → confirm → place order (cash on delivery, or stop at the payment screen).

**F4 — Screen summary:** "Screen pe kya hai?" → one sentence in Hindi describing the current screen + the top 3 things the user can do.

---

## 6. Functional requirements

**Voice (FR-V)**
- FR-V1 Push-to-talk (hold SPACE on the laptop for the prototype).
- FR-V2 Speech-to-text handles Hindi, English and Hinglish.
- FR-V3 Text-to-speech in natural Hindi; each narration ≤ 12 words.
- FR-V4 Confirmation parsing: clear yes / clear no / unclear. Unclear → ask once more, then cancel (fail safe).

**Agent (FR-A)**
- FR-A1 Each step: observe screen (UI tree, plus screenshot when needed) → choose exactly one action → execute → verify the screen changed.
- FR-A2 Max 25 steps per command; stop after 3 consecutive failed actions and tell the user plainly.
- FR-A3 Ask the user when ambiguous (two contacts with the same name, two similar products).
- FR-A4 Narrate meaningful progress only ("GPay khol raha hoon", "Rahul mil gaye"), never every tap.

**Control (FR-C)**
- FR-C1 SPACE or ESC (stretch: saying "ruko") halts immediately, mid-flow.
- FR-C2 On halt, handoff or finish, TalkBack is restored so the user can continue themselves.

**Logging & eval (FR-L)**
- FR-L1 Every step traced locally: screen hash, screenshot, element list, model output, action, gate decision, latency.
- FR-L2 Eval harness runs each flow N times and outputs a success-rate table.

---

## 7. Safety requirements (non-negotiable)

| ID | Requirement |
|----|-------------|
| S1 | Any irreversible action (pay, send message, place order) requires a spoken read-back + explicit "yes". |
| S2 | The read-back may only contain text that literally appears on the current screen (no model hallucination of payee or amount). |
| S3 | On-screen payee/recipient must match the name the user said; otherwise ask. |
| S4 | Amount cap per command (config `MAX_AMOUNT_INR`; ₹2,000 default, ₹10 during testing). |
| S5 | Never read, type or store UPI PINs, passwords or OTPs. At PIN screens: stop and hand off. |
| S6 | Never solve or bypass CAPTCHAs — hand off to the user. |
| S7 | The gate is deterministic code with unit tests — never an LLM decision. |
| S8 | Traces contain screenshots of personal apps: stored locally only, gitignored, deletable. |

---

## 8. Success metrics

| Metric | Target |
|--------|--------|
| F1 success rate | ≥ 9/10 |
| F2 success rate (up to PIN handoff) | ≥ 8/10 |
| F3 success rate | ≥ 7/10 |
| Unconfirmed irreversible actions | 0 |
| Safety unit tests | 100% pass |
| Median time per flow | ≤ 60 s |
| Longest silence during a flow | ≤ 5 s (narration keeps the user informed) |

---

## 9. How this maps to the judging criteria

| Criterion | How Saathi scores |
|-----------|-------------------|
| Originality | Hindi-first agent built specifically for inaccessible Indian apps (UPI, quick commerce); existing agents are English-first and general |
| Impact | Millions of blind Indians excluded from UPI and everyday apps |
| Execution | Real phone, real apps, measured success rates |
| Product thinking | Safety gate, PIN handoff, "ruko" control, narration — designed around what blind users said they want |
| Technical depth | UI tree + vision perception, deterministic safety layer, TalkBack coexistence, eval harness |
| Potential | Path to an on-device Android app; Play Store accessibility-tool category; NGO / bank partnerships |

---

## 10. Devpost submission checklist

- [ ] Project name
- [ ] Short description (1–2 sentences)
- [ ] Problem — who experiences it
- [ ] Solution — how it works
- [ ] Demo link (or recorded demo if a live link isn't possible)
- [ ] Source code (GitHub)
- [ ] Demo video
- [ ] Tech stack
- [ ] Build process — what was built during OFFGRID, what's next
- [ ] Disclose third-party tech: Mobilerun (MIT, phone control), Sarvam (speech), OpenAI (reasoning + vision)

---

## Sources

- India's digital payments and accessibility: https://www.findevgateway.org/blog/2026/06/indias-digital-payments-boom-is-leaving-persons-with-disabilities-behind
- Visual impairment figures and app regressions: https://tcd.thecore.in/p/enabling-differently-abled
- Agent study with blind users (52.5%): https://arxiv.org/pdf/2609.00524
- AGI Inc accessibility narration mode: https://theagi.company/blog/building-agi-with-the-accessibility-community
- RealSAM Pocket: https://www.podfeet.com/blog/?p=25605
- India mobile OS share: https://gs.statcounter.com/os-market-share/mobile-/india
- Hackathon: https://hack47-offgrid.devpost.com/