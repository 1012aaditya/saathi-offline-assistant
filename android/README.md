# Saathi — Android app

Kotlin + Jetpack Compose. Design: `../DESIGN.md` ("Clarity"). Spec and build order: `../ANDROID_APP.md`.

## Status (step 1 of the build order)
- **Real:** all 12 Clarity screens, navigation with no dead ends, light/dark, sounds + vibrations, Android "Remove animations", TalkBack labels and focus order, speech in/out via Android's built-in recognizer and text-to-speech (no API keys, no INTERNET permission), runtime permissions, settings and activity history stored only on the phone, Volume-down long-press = talk.
- **Safety gate:** `safety/` is a 1:1 Kotlin port of `saathi/safety.py` (R1–R11) with all 88 Python safety tests ported, plus parity and config-lock tests. The protected app lists can't be overridden.
- **Not yet (labelled "Practice" in the app):** operating other apps (needs the AccessibilityService + agent loop + proxy, step 2), real walk-mode camera detection (step 4). Practice runs use sample data and never send, order or pay anything.

## Build
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :safety:test            # safety gate tests (must pass before any commit)
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
safety/                  pure-Kotlin safety gate + JUnit 5 tests
```
