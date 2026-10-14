"""Demo driver: the laptop speaks a scene's lines to the tablet, one each time Saathi's mic opens.

    python3 scripts/demo_drive.py scene2            # Blinkit shopping (see SCENES)
    python3 scripts/demo_drive.py scene3

It taps the Saathi bubble once to start, then waits for "speech in: mic open" in Saathi's log,
speaks the next line with macOS `say` (an Indian English voice) and waits for "heard". A line
that isn't caught is spoken again at the next mic open (at most 3 times). Saathi's own safety
rules are untouched: nothing here answers a Confirm sheet with yes, and checkout stops at payment.
The video should say the voice is a demo voice. Logs carry timings only, never words.
"""

from __future__ import annotations

import subprocess
import sys
import threading
import time

VOICE = "Rishi"  # en_IN
BUBBLE = (1140, 1949)  # the bubble's centre on the Tab S6 Lite (portrait)

SCENES: dict[str, list[str]] = {
    # Blinkit (open on its home page first). Stops at payment: Saathi never pays.
    "scene2": [
        "help me here",
        "search for milk",
        "which one is the cheapest",
        "add the cheapest one to my cart",
        "the first one",
        "open my cart",
        "checkout",
    ],
    # From the home screen: whole jobs in other apps (YouTube must be approved once).
    "scene3": [
        "open YouTube and play a lofi song",
    ],
}


def serial() -> str:
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    s = [ln.split("\t")[0] for ln in out if ln.endswith("\tdevice")]
    if not s:
        sys.exit("No tablet connected.")
    return s[0]


class Log:
    """Saathi's voice events from logcat, as they happen."""

    def __init__(self, s: str) -> None:
        subprocess.run(["adb", "-s", s, "logcat", "-c"])
        self.events: list[tuple[float, str]] = []
        self.p = subprocess.Popen(["adb", "-s", s, "logcat", "-v", "brief", "SaathiVoice:I", "SaathiTask:I", "*:S"],
                                  stdout=subprocess.PIPE, text=True)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        for line in self.p.stdout:  # type: ignore[union-attr]
            self.events.append((time.time(), line.strip()))

    def wait(self, needles: tuple[str, ...], since: float, timeout: float) -> str | None:
        end = time.time() + timeout
        while time.time() < end:
            for t, line in self.events:
                if t >= since and any(n in line for n in needles):
                    return line
            time.sleep(0.1)
        return None


def say(text: str) -> None:
    print(f"  🔊 {text}", flush=True)
    subprocess.run(["say", "-v", VOICE, "-r", "160", text])


def main() -> None:
    scene = sys.argv[1] if len(sys.argv) > 1 else "scene2"
    lines = SCENES[scene]
    s = serial()
    log = Log(s)
    time.sleep(1)
    print(f"{scene}: tapping the bubble", flush=True)
    subprocess.run(["adb", "-s", s, "shell", "input", "tap", str(BUBBLE[0]), str(BUBBLE[1])])
    since = time.time()
    for i, line in enumerate(lines, 1):
        for attempt in range(3):
            opened = log.wait(("speech in: mic open",), since, 60)
            if opened is None:
                print(f"  [{i}] mic never opened; tapping the bubble to talk", flush=True)
                subprocess.run(["adb", "-s", s, "shell", "input", "tap", str(BUBBLE[0]), str(BUBBLE[1])])
                since = time.time()
                continue
            time.sleep(0.5)
            spoke = time.time()
            say(line)
            got = log.wait(("speech in: heard", "speech in: nothing usable", "result discarded"), spoke, 15)
            since = time.time()
            if got and "heard" in got and "discarded" not in got:
                print(f"  [{i}] heard ✓", flush=True)
                break
            print(f"  [{i}] not caught, trying again", flush=True)
        else:
            print(f"  [{i}] gave up on this line", flush=True)
        time.sleep(1.0)
    # Let the last answer finish.
    log.wait(("guide: hold", "agent: step Done", "finished"), since, 25)
    time.sleep(6)
    print("scene done", flush=True)


if __name__ == "__main__":
    main()
