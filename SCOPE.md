# Saathi — Scope

**Version:** 0.1 · **Date:** 12 Oct 2026
**Hard deadline:** 15 Oct 2026, 09:30 IST (00:00 EDT) · **Internal submit target:** 14 Oct 2026, 23:00 IST

---

## 1. MoSCoW

### Must (the demo fails without these)

| ID | Item |
|----|------|
| M1 | Phone control over wireless ADB via Mobilerun `AndroidDriver`; UI tree normalizer |
| M2 | Agent loop working in text mode (`--text`) |
| M3 | Voice in (Hindi/Hinglish STT) and voice out (Hindi TTS) with push-to-talk |
| M4 | Safety gate R1–R10 + unit tests passing |
| M5 | F1 WhatsApp read & reply |
| M6 | F2 GPay send ₹1 → read-back → confirm → PIN handoff — **skipped for now (owner, 13 Oct)**: safety rules R1/R5/R6/R8 built and unit-tested, not run on GPay |
| M7 | TalkBack suspend/restore, always restored on exit |
| M8 | Eval harness with real success rates |
| M9 | Demo video + README + Devpost submission |

### Should

| ID | Item |
|----|------|
| S1 | F3 Blinkit order (stop at order hold / COD) |
| S2 | F4 "Screen pe kya hai?" summary |
| S3 | Session with a blind or low-vision tester |
| S4 | Phrase cache for instant common responses |

### Could

| ID | Item |
|----|------|
| C1 | Saying "ruko" to interrupt (beyond SPACE/ESC) |
| C2 | PhonePe / BHIM support |
| C3 | A second Indian language (e.g. Tamil) |
| C4 | Live trace viewer web page for judges (step list + screenshots) |
| C5 | **Walk mode (beta)**: on-device camera obstacle warnings in Hindi — merged 13 Oct |
| C6 | Project website (`site/`) — built 12 Oct |

### Won't (this hackathon)

- iOS · on-device Android app · solving CAPTCHAs · reading OTPs · entering PINs/passwords
- Account creation, investments, loans, any action beyond the 4 flows
- Real-money transactions above ₹10 during development

---

## 2. Milestones (IST)

| # | Window | Milestone | Definition of done |
|---|--------|-----------|--------------------|
| M0 | 12 Oct 10:00–12:00 | Setup + experiments | `mobilerun ping` OK over Wi-Fi · UI tree dumped to JSON for WhatsApp, GPay, Blinkit · TalkBack + ADB tap behavior known · Sarvam STT/TTS hello-world works |
| M1 | 12 Oct 12:00–18:00 | Text agent | `--text "WhatsApp kholo aur Mummy ka last message padho"` succeeds 3/3 |
| M2 | 12 Oct 18:00–23:00 | Voice | Same flow by Hindi voice end to end |
| M3 | 13 Oct 09:00–14:00 | Safety + GPay | Gate unit tests pass · ₹1 to own second account reaches PIN handoff with correct read-back |
| M4 | 13 Oct 14:00–20:00 | Blinkit + summary | F3 reaches order hold · F4 gives a correct one-line summary on 5 different screens |
| M5 | 13 Oct 20:00–14 Oct 02:00 | Hardening | Eval 10 runs/flow · targets in PRD §8 met or failures documented |
| M6 | 14 Oct 09:00–12:00 | User test | At least one blind/low-vision or blindfolded tester run · notes + quote captured |
| — | 14 Oct 12:00 | **Feature freeze** | Bug fixes and demo only after this |
| M7 | 14 Oct 12:00–23:00 | Ship | Demo video recorded (phone mirrored with scrcpy + webcam) · README with architecture + results · Devpost fields filled · **submitted** |

---

## 3. Cut lines (decide at each checkpoint, don't drift)

| Checkpoint | If… | Then… |
|------------|-----|-------|
| 12 Oct 12:00 | ADB taps don't click with TalkBack on and suspend/restore doesn't work | Demo with TalkBack off; explain the production on-device design in the pitch |
| 12 Oct 18:00 | Own agent loop can't do F1 reliably | Use Mobilerun's built-in agent with custom tools; enforce the safety gate inside the tools |
| 13 Oct 14:00 | GPay screens unreadable (secure / empty tree) | Switch F2 to PhonePe or BHIM; keep the same gate — **superseded: payments skipped by owner (13 Oct)** |
| 13 Oct 20:00 | Blinkit flaky (< 5/10) | Cut F3; polish F1 + F2 + F4 — **checked 12 Oct: 10/10 to order read-back, F3 stays** |
| 14 Oct 12:00 | Anything not working | Cut it. Freeze. Record what works. |

---

## 4. Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| TalkBack blocks injected taps | Medium | High | Suspend/restore strategy; test first thing |
| Payment app blocks screenshots / tree | Medium | High | Tree-first perception; alternate UPI apps |
| Agent reliability (~50% in research) | High | High | 3 flows only; app cards; screen-change checks; ask on ambiguity |
| Hindi TTS sounds robotic | Low | Medium | Test Bulbul voices on day 1; OpenAI fallback |
| Latency feels slow | Medium | Medium | Narration; tree-only steps; smaller model for simple steps |
| No blind tester available | Medium | Medium | Contact NAB / VIT disability cell on 12 Oct; blindfolded testers as fallback |
| API costs / rate limits | Low | Low | Cap steps; cache phrases |
| Demo-day device / Wi-Fi issues | Medium | High | Record the demo video early (13 Oct night); USB cable as backup |