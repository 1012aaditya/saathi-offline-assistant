"""Halt controls: SPACE / ESC set the interrupted flag (FR-C1).

Thread-safe: the keyboard listener runs on its own thread, the agent loop polls interrupted().
"""

import threading

_interrupted = threading.Event()


def interrupt() -> None:
    _interrupted.set()


def interrupted() -> bool:
    return _interrupted.is_set()


def reset() -> None:
    _interrupted.clear()
