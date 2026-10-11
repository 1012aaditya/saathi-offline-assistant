# Saathi

Hindi + English voice agent for Android apps. See PRD.md and SYSTEM_DESIGN.md.

## Device security checklist (after development / the hackathon)

- Turn **Google Play Protect** back on (Play Store → Play Protect → ⚙️ → Scan apps).
- Developer options → turn off **Wireless debugging** and tap **Revoke USB debugging authorisations**.
- Xiaomi/POCO: turn off **Install via USB** and **USB debugging (Security settings)** if you enabled them.
- Uninstall **Mobilerun Portal** if you won't use Saathi again (it is an accessibility service that can read and tap the screen).
- Delete local personal data: `tests/fixtures/raw/`, `traces/`, `scratch/` (all gitignored).
