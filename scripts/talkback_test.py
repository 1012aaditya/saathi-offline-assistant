"""M0 TalkBack experiment (PROJECT.md open question 1).

Setup on the device: TalkBack ON, Settings -> About phone/tablet open.
  1. Record the enabled accessibility services (Portal + TalkBack names).
  2. With TalkBack ON, tap one harmless row and check whether the screen changed.
  3. Suspend TalkBack, verify it's gone, tap the same row again, check again.
  4. Restore TalkBack and verify it's back.
Only ever taps inside com.android.settings. TalkBack is restored in `finally` + atexit.

Usage: uv run python scripts/talkback_test.py {services|tap-on|suspend}
"""

import asyncio
import re
import sys

from saathi import device, talkback

SETTINGS = "com.android.settings"
# Harmless rows on About phone/tablet that just open an info sub-page.
TARGET = re.compile(r"(?i)^(software information|status information|status|hardware information)$")


async def screen() -> tuple[str, list[device.Element]]:
    raw = await device.get_raw_tree()
    return device.foreground_package(raw), device.normalize(raw)


def fingerprint(els: list[device.Element]) -> str:
    return "|".join(sorted(e.text or e.desc for e in els if e.text or e.desc))


async def tap_target(label: str) -> bool:
    """Tap the harmless row; return True if the screen changed (i.e. it clicked)."""
    pkg, els = await screen()
    if pkg != SETTINGS:
        raise SystemExit(f"Aborting: foreground is {pkg!r}, expected Settings. Nothing tapped.")
    target = next((e for e in els if TARGET.match(e.text or e.desc)), None)
    if target is None:
        raise SystemExit("Aborting: no 'Software/Status information' row visible. Nothing tapped.")
    before = fingerprint(els)
    print(f"  {label}: tapping {target.text or target.desc!r} at {target.center}")
    await device.tap(*target.center)
    await asyncio.sleep(2.5)
    pkg_after, els_after = await screen()
    changed = pkg_after != pkg or fingerprint(els_after) != before
    print(f"  {label}: screen changed -> {changed}")
    return changed


async def main(step: str) -> None:
    talkback.install_guards()
    await device.connect(portal_mode="required")

    services = await talkback.enabled_services()
    tb = talkback.find_talkback(services)
    portal = next((s for s in services if "mobilerun" in s), None)
    print(f"enabled services: {services}")
    print(f"PORTAL_SERVICE={portal}\nTALKBACK_SERVICE={tb}")
    if step == "services":
        return
    if tb is None:
        raise SystemExit("TalkBack is OFF. Turn it on, open Settings -> About, and rerun.")

    if step == "tap-on":
        print("\n[A] TalkBack ON")
        await tap_target("TalkBack ON")
        return

    # step == "suspend": suspend, verify, tap, then ALWAYS restore + verify
    try:
        print("\n[B] Suspending TalkBack")
        await talkback.suspend()
        await asyncio.sleep(1.5)
        print(f"  TalkBack still enabled after suspend? {await talkback.is_on()}")
        print(f"  Portal still enabled? {portal in await talkback.enabled_services()}")
        await tap_target("TalkBack SUSPENDED")
    finally:
        print("\n[C] Restoring TalkBack")
        await talkback.restore()
        await asyncio.sleep(1.5)
        after = await talkback.enabled_services()
        print(f"  services now: {after}")
        print(
            f"  TalkBack back? {talkback.find_talkback(after) is not None}  "
            f"Portal kept? {portal in after}"
        )


if __name__ == "__main__":
    steps = ("services", "tap-on", "suspend")
    if len(sys.argv) != 2 or sys.argv[1] not in steps:
        sys.exit(f"usage: talkback_test.py {{{'|'.join(steps)}}}")
    asyncio.run(main(sys.argv[1]))
