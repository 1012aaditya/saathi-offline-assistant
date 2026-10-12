"""Suspend / restore TalkBack via adb shell settings. Always restore on exit.

The only module besides device.py allowed to talk to adb (CLAUDE.md hard rule 8),
and only to toggle accessibility settings.
"""

import asyncio
import atexit
import signal
import subprocess

from saathi.config import get_settings

_saved: str | None = None  # enabled_accessibility_services before suspend()


def _adb_args(*args: str) -> list[str]:
    serial = get_settings().adb_serial
    return ["adb", *(["-s", serial] if serial else []), "shell", *args]


async def _shell(*args: str) -> str:
    proc = await asyncio.create_subprocess_exec(
        *_adb_args(*args),
        stdin=asyncio.subprocess.DEVNULL,  # adb shell would otherwise eat the user's typed input
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    out, err = await proc.communicate()
    if proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {err.decode().strip()}")
    return out.decode().strip()


def _parse(value: str) -> list[str]:
    return [s for s in value.split(":") if s and s != "null"]


async def enabled_services() -> list[str]:
    return _parse(await _shell("settings", "get", "secure", "enabled_accessibility_services"))


def find_talkback(services: list[str]) -> str | None:
    return next((s for s in services if "talkback" in s.lower()), None)


async def is_on() -> bool:
    return find_talkback(await enabled_services()) is not None


async def suspend() -> None:
    """Remove TalkBack from the enabled services, keeping everything else (e.g. Portal)."""
    global _saved
    services = await enabled_services()
    if find_talkback(services) is None:
        return  # nothing to suspend; leave _saved unset so restore() is a no-op
    _saved = ":".join(services)
    rest = ":".join(s for s in services if s != find_talkback(services))
    await _shell("settings", "put", "secure", "enabled_accessibility_services", rest)


async def restore() -> None:
    """Put back exactly what suspend() removed. Safe to call any number of times."""
    global _saved
    if _saved is None:
        return
    await _shell("settings", "put", "secure", "enabled_accessibility_services", _saved)
    await _shell("settings", "put", "secure", "accessibility_enabled", "1")
    _saved = None


def _restore_sync() -> None:
    """For atexit / signal handlers, where no event loop may be running."""
    global _saved
    if _saved is None:
        return
    for args in (
        ("settings", "put", "secure", "enabled_accessibility_services", _saved),
        ("settings", "put", "secure", "accessibility_enabled", "1"),
    ):
        subprocess.run(_adb_args(*args), check=False, timeout=15, stdin=subprocess.DEVNULL)
    _saved = None


def install_guards() -> None:
    """Restore TalkBack on normal exit, Ctrl+C and kill (hard rule 7)."""
    atexit.register(_restore_sync)

    def handler(signum, _frame):
        _restore_sync()
        raise SystemExit(128 + signum)

    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, handler)
