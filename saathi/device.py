"""AndroidDriver wrapper: UI tree -> Element list, screenshots, observe/execute/wait_for_change.

All device I/O goes through this module (CLAUDE.md hard rule 8).

Raw tree shape (verified on real dumps, Portal 0.7.25, tests/fixtures/raw/):
    {"a11y_tree": <node>, "phone_state": {"packageName", "currentApp", "activityName",
     "keyboardVisible", "isEditable", "focusedElement"}, "device_context": {...}}
    node = {"className", "text", "contentDescription", "hint", "resourceId",
            "boundsInScreen": {left, top, right, bottom}, "isClickable", "isEditable",
            "isPassword", "isVisibleToUser", "isShowingHintText", ..., "children": [node]}
The uiautomator fallback (Portal unreachable) uses plain keys instead: "clickable",
"password", no "isVisibleToUser"/"isEditable". normalize() accepts both.
"""

import asyncio
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mobilerun_core_local import AndroidDriver
from pydantic import BaseModel

from saathi.actions import Action, Back, Home, OpenApp, Scroll, Tap, TypeText
from saathi.config import APP_ALIASES, get_settings

MAX_ELEMENTS = 150

# Icon fonts put glyphs in the Unicode private use area (e.g. Blinkit's "").
_PRIVATE_USE = re.compile("[-]")

PORTAL_IME = "com.mobilerun.portal/.input.MobilerunKeyboardIME"

_driver: AndroidDriver | None = None
_user_ime: str | None = None  # keyboard the user had before Portal swapped in its own


class Element(BaseModel):
    index: int
    cls: str  # "Button", "EditText", "TextView", ...
    text: str
    desc: str  # content-description
    rid: str  # short resource-id
    clickable: bool
    editable: bool
    password: bool
    bounds: tuple[int, int, int, int]
    center: tuple[int, int]
    sender: str = ""  # chat messages only: "me" (sent by the user) or "them" (received)

    def to_line(self) -> str:
        """One line per element for the model: [12] Button "Pay" desc="" rid=pay_btn (540,1820)"""
        flags = "".join(f for f, on in ((" edit", self.editable), (" pwd", self.password)) if on)
        if self.sender:
            flags += f" from={self.sender}"
        return (
            f'[{self.index}] {self.cls} "{self.text}" desc="{self.desc}" rid={self.rid}'
            f" ({self.center[0]},{self.center[1]}){flags}"
        )


def _clean(s: str | None) -> str:
    return " ".join(_PRIVATE_USE.sub("", s or "").split())


def _flag(node: dict[str, Any], portal_key: str, adb_key: str) -> bool:
    return bool(node.get(portal_key, node.get(adb_key, False)))


def _descendant_text(node: dict[str, Any], limit: int = 80) -> str:
    """Label for an unlabeled clickable container, borrowed from the texts inside it."""
    parts: list[str] = []
    stack = list(node.get("children", []))
    while stack and len(" · ".join(parts)) < limit:
        child = stack.pop(0)
        label = _clean(child.get("text")) or _clean(child.get("contentDescription"))
        if label and label not in parts:
            parts.append(label)
        stack[:0] = child.get("children", [])
    return " · ".join(parts)[:limit]


# WhatsApp message rows: a row containing a delivery-status tick (rid=status: Sent / Delivered /
# Read) is the user's own message; rows without one were received. Verified on real chats.
_MESSAGE_ROW_RIDS = {"conversation_row_text"}
_MESSAGE_RIDS = {"message_text"}


def _has_rid(node: dict[str, Any], rid: str) -> bool:
    return (node.get("resourceId") or "").rsplit("/", 1)[-1] == rid or any(
        _has_rid(c, rid) for c in node.get("children", [])
    )


def _has_clickable_descendant(node: dict[str, Any]) -> bool:
    return any(
        _flag(c, "isClickable", "clickable") or _has_clickable_descendant(c)
        for c in node.get("children", [])
    )


def normalize(raw: dict[str, Any], max_elements: int = MAX_ELEMENTS) -> list[Element]:
    """Flatten the raw tree into visible, useful elements in screen-reading (pre-)order."""
    out: list[Element] = []

    def walk(node: dict[str, Any], sender: str = "") -> None:
        if len(out) >= max_elements:
            return
        rid = (node.get("resourceId") or "").rsplit("/", 1)[-1]
        if rid in _MESSAGE_ROW_RIDS:
            sender = "me" if _has_rid(node, "status") else "them"
        b = node.get("boundsInScreen") or {}
        bounds = (b.get("left", 0), b.get("top", 0), b.get("right", 0), b.get("bottom", 0))
        visible = (
            node.get("isVisibleToUser", True) and bounds[2] > bounds[0] and bounds[3] > bounds[1]
        )
        if visible:
            cls = (node.get("className") or "").rsplit(".", 1)[-1]
            editable = bool(node.get("isEditable")) or cls in ("EditText", "AutoCompleteTextView")
            # When a field shows its hint, "text" is the hint itself, not user input.
            text = "" if node.get("isShowingHintText") else _clean(node.get("text"))
            desc = _clean(node.get("contentDescription")) or (
                _clean(node.get("hint")) if editable else ""
            )
            clickable = _flag(node, "isClickable", "clickable")
            wrapper = False
            if clickable and not text and not desc:
                # A container wrapping other buttons is noise; a leaf card borrows its texts.
                wrapper = _has_clickable_descendant(node)
                if not wrapper:
                    text = _descendant_text(node)
            if not wrapper and (clickable or editable or text or desc):
                x1, y1, x2, y2 = bounds
                out.append(
                    Element(
                        index=len(out),
                        cls=cls,
                        text=text,
                        desc=desc,
                        rid=rid,
                        clickable=clickable,
                        editable=editable,
                        password=_flag(node, "isPassword", "password"),
                        bounds=bounds,
                        center=((x1 + x2) // 2, (y1 + y2) // 2),
                        sender=sender if rid in _MESSAGE_RIDS else "",
                    )
                )
        for child in node.get("children", []):
            walk(child, sender)

    tree = raw.get("a11y_tree")
    for root in tree if isinstance(tree, list) else [tree] if tree else []:
        walk(root)
    return out


def foreground_package(raw: dict[str, Any]) -> str:
    """From phone_state (Portal and the uiautomator fallback both set it)."""
    return (raw.get("phone_state") or {}).get("packageName", "")


def to_prompt(elements: list[Element]) -> str:
    return "\n".join(e.to_line() for e in elements)


async def _adb(*args: str) -> str:
    serial = get_settings().adb_serial
    proc = await asyncio.create_subprocess_exec(
        "adb",
        *(["-s", serial] if serial else []),
        "shell",
        *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    out, _ = await proc.communicate()
    return out.decode().strip()


async def _remember_user_keyboard() -> None:
    """AndroidDriver.connect() switches the IME to Portal's (invisible) keyboard; remember the
    user's so restore_keyboard() can put it back. If Portal's is already active (e.g. after a
    crash), fall back to the first other enabled keyboard."""
    global _user_ime
    current = await _adb("settings", "get", "secure", "default_input_method")
    if current and current != PORTAL_IME and current != "null":
        _user_ime = current
        return
    others = [i for i in (await _adb("ime", "list", "-s")).splitlines() if i and i != PORTAL_IME]
    _user_ime = next((i for i in others if "voice" not in i.lower()), None)


async def restore_keyboard() -> None:
    """Give the user their own keyboard back. Call in `finally` alongside talkback.restore()."""
    if _user_ime:
        await _adb("ime", "set", _user_ime)


async def connect(serial: str | None = None, portal_mode: str = "auto") -> AndroidDriver:
    """Connect once and reuse the driver. Serial defaults to ADB_SERIAL from .env.

    portal_mode="required" raises instead of silently falling back to uiautomator,
    whose tree uses different keys (clickable vs isClickable, no isVisibleToUser).
    use_tcp: Portal over an ADB port-forward (localhost only). The default content-provider
    channel takes ~5 s to answer the startup probe over Wi-Fi and fails it about half the time.
    """
    global _driver
    if _driver is None:
        await _remember_user_keyboard()
        _driver = AndroidDriver(
            serial=serial or get_settings().adb_serial, use_tcp=True, portal_mode=portal_mode
        )
        await _driver.connect()
    return _driver


# -- observe / act ---------------------------------------------------------------------------


@dataclass
class Screen:
    package: str
    elements: list[Element]
    hash: str
    keyboard_visible: bool = False
    screenshot: bytes | None = None  # JPEG, <= 1080 px wide, only when the vision policy asks

    def labeled_clickables(self) -> int:
        return sum(1 for e in self.elements if e.clickable and (e.text or e.desc))


def screen_hash(package: str, elements: list[Element]) -> str:
    """SYSTEM_DESIGN section 5: hash of (package, sorted visible texts)."""
    texts = sorted({t for e in elements for t in (e.text, e.desc) if t})
    return hashlib.sha1(json.dumps([package, texts], ensure_ascii=False).encode()).hexdigest()[:12]


def _to_jpeg(png: bytes, max_width: int = 1080) -> bytes:
    import io

    from PIL import Image

    img = Image.open(io.BytesIO(png)).convert("RGB")
    if img.width > max_width:
        img = img.resize((max_width, round(img.height * max_width / img.width)))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=80)
    return buf.getvalue()


async def observe(vision: bool = False) -> Screen:
    raw = await get_raw_tree()
    pkg = foreground_package(raw)
    els = normalize(raw)
    shot = _to_jpeg(await screenshot()) if vision else None
    kb = bool((raw.get("phone_state") or {}).get("keyboardVisible"))
    return Screen(pkg, els, screen_hash(pkg, els), kb, shot)


async def execute(action: Action, screen: Screen) -> bool:
    """Run one action that already passed safety.check(). Returns False if it couldn't run."""
    d = await connect()
    if isinstance(action, Tap):
        await d.tap(*screen.elements[action.index].center)
    elif isinstance(action, TypeText):
        if action.index is not None:
            await d.tap(*screen.elements[action.index].center)
            await asyncio.sleep(0.6)
        return bool(await d.input_text(action.text, clear=True))
    elif isinstance(action, Scroll):
        w, h = _screen_size(screen)
        x = w // 2
        top, bottom = int(h * 0.3), int(h * 0.75)
        y1, y2 = (bottom, top) if action.direction == "down" else (top, bottom)
        await d.swipe(x, y1, x, y2, duration_ms=400)
    elif isinstance(action, Back):
        await d.press_button("back")
    elif isinstance(action, Home):
        await d.press_button("home")
    elif isinstance(action, OpenApp):
        await d.start_app(APP_ALIASES[action.app.lower().strip()])
    else:
        return False  # look / ask_user / done / fail are handled by the agent, not the device
    return True


def _screen_size(screen: Screen) -> tuple[int, int]:
    """Largest element bounds approximate the screen (the root FrameLayout is filtered out)."""
    w = max((e.bounds[2] for e in screen.elements), default=1080)
    h = max((e.bounds[3] for e in screen.elements), default=1920)
    return w, h


async def wait_for_change(old_hash: str, timeout_s: float = 3.0) -> bool:
    """Poll the tree until its hash differs from old_hash (the action had a visible effect)."""
    deadline = asyncio.get_running_loop().time() + timeout_s
    while True:
        await asyncio.sleep(0.4)
        raw = await get_raw_tree()
        pkg = foreground_package(raw)
        if screen_hash(pkg, normalize(raw)) != old_hash:
            return True
        if asyncio.get_running_loop().time() >= deadline:
            return False


async def get_raw_tree() -> dict[str, Any]:
    return await (await connect()).get_ui_tree()


async def screenshot() -> bytes:
    return await (await connect()).screenshot()


async def tap(x: int, y: int) -> None:
    await (await connect()).tap(x, y)


async def shell(cmd: str) -> str:
    """Read-only helpers only (e.g. dumpsys). Not for actions."""
    d = await connect()
    return await d.device.shell(cmd)


async def dump_fixture(name: str, out_dir: Path = Path("tests/fixtures/raw")) -> tuple[Path, Path]:
    """Save the raw UI tree (JSON) and a screenshot (PNG) of the current screen."""
    out_dir.mkdir(parents=True, exist_ok=True)
    d = await connect()
    tree = await d.get_ui_tree()
    png = await d.screenshot()
    json_path = out_dir / f"{name}.json"
    png_path = out_dir / f"{name}.png"
    json_path.write_text(json.dumps(tree, ensure_ascii=False, indent=2))
    png_path.write_bytes(png)
    return json_path, png_path
