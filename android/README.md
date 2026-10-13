# Saathi — Android app

Kotlin + Jetpack Compose. Design: `../DESIGN.md` ("Clarity"). Spec and build order: `../ANDROID_APP.md`.

## Status (step 1 of the build order)
- **Real:** all 12 Clarity screens, navigation with no dead ends, light/dark, sounds + vibrations, Android "Remove animations", TalkBack labels and focus order, speech in/out via Android's built-in recognizer and text-to-speech (no API keys, no INTERNET permission), runtime permissions, settings and activity history stored only on the phone, Volume-down long-press = talk.
- **Safety gate:** `safety/` is a 1:1 Kotlin port of `saathi/safety.py` (R1–R11) with all 88 Python safety tests ported, plus parity and config-lock tests. The protected app lists can't be overridden.
- **Offline app tasks (step 2a, no model, no network):** `agent/` is a pure-Kotlin keyword command parser (English, Hindi, Hinglish) + step-by-step WhatsApp flows (read a contact's last message, who has unread messages, reply with a read-back and your yes, open an app, describe the screen you were on) + the agent loop (25 steps max, stop after 3 failures/loops). `SaathiAccessibilityService` reads the screen and taps; every action passes `DeviceBridge` → `SafetyGate.check()` first. Volume-down long-press from any app = talk (short press still changes volume).
- **Switch it on (owner only):** Settings → Accessibility → Installed apps → Saathi → On. Saathi never enables it itself and never toggles TalkBack.
- **Not yet:** model-driven tasks for anything else (Blinkit, payments) — answered honestly as "can't do that offline yet".

## Build
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :safety:test            # safety gate tests (must pass before any commit)
./gradlew :agent:test             # parser, normaliser, flows + loop on a simulated WhatsApp
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug          # Android lint incl. accessibility checks
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
`local.properties` (gitignored) holds `sdk.dir=/opt/homebrew/share/android-commandlinetools`.

## Layout
```
app/src/main/java/app/saathi/
  MainActivity.kt        services, theme, Volume-down push-to-talk
  ui/Nav.kt              the 12 routes, AppNav, global voice phrases
  ui/theme/Theme.kt      Clarity colours (AAA-checked) and type scale
  ui/components/         SaathiScreen, buttons, grouped lists, "You can say" footer
  ui/screens/            one file per screen (+ ScreenParts.kt, SettingsParts.kt)
  data/AppState.kt       settings + history (SharedPreferences, on the phone only)
  feedback/Feedback.kt   earcons (generated tones) + vibration patterns
  voice/Voice.kt         SpeechRecognizer + TextToSpeech
  braille/               braille reader: touch cell view, haptics, OTP via SMS User Consent
  ui/screens/BrailleScreen.kt  Home > Braille (OTP / typed text / practice; rhythm or touch reading)
  ui/screens/BrailleParts.kt   word-by-word rhythm reader (next/repeat/read on/sentences/speed)
  ui/screens/BrailleLearnScreen.kt  Learn braille: 5 lessons (six dots, feel a letter, rhythm, quiz, reading)
  agent/                 SaathiAccessibilityService, DeviceBridge (the gated choke point), TaskRunner, Requests
safety/                  pure-Kotlin safety gate + JUnit 5 tests
agent/                   pure-Kotlin parser, flows, AgentLoop, GatedDevice + tests (synthetic screens only)
braille/                 pure-Kotlin text → braille cells + OTP extraction + tests (./gradlew :braille:test)
```
