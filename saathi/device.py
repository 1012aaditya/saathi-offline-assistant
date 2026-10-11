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

import json
import re
from pathlib import Path
from typing import Any

from mobilerun_core_local import AndroidDriver
from pydantic import BaseModel

from saathi.config import get_settings

MAX_ELEMENTS = 150

# Icon fonts put glyphs in the Unicode private use area (e.g. Blinkit's "").
_PRIVATE_USE = re.compile("[-]")

_driver: AndroidDriver | None = None


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

    def to_line(self) -> str:
        """One line per element for the model: [12] Button "Pay" desc="" rid=pay_btn (540,1820)"""
        flags = "".join(f for f, on in ((" edit", self.editable), (" pwd", self.password)) if on)
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


def _has_clickable_descendant(node: dict[str, Any]) -> bool:
    return any(
        _flag(c, "isClickable", "clickable") or _has_clickable_descendant(c)
        for c in node.get("children", [])
    )


def normalize(raw: dict[str, Any], max_elements: int = MAX_ELEMENTS) -> list[Element]:
    """Flatten the raw tree into visible, useful elements in screen-reading (pre-)order."""
    out: list[Element] = []

    def walk(node: dict[str, Any]) -> None:
        if len(out) >= max_elements:
            return
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
                        rid=(node.get("resourceId") or "").rsplit("/", 1)[-1],
                        clickable=clickable,
                        editable=editable,
                        password=_flag(node, "isPassword", "password"),
                        bounds=bounds,
                        center=((x1 + x2) // 2, (y1 + y2) // 2),
                    )
                )
        for child in node.get("children", []):
            walk(child)

    tree = raw.get("a11y_tree")
    for root in tree if isinstance(tree, list) else [tree] if tree else []:
        walk(root)
    return out


def foreground_package(raw: dict[str, Any]) -> str:
    """From phone_state (Portal and the uiautomator fallback both set it)."""
    return (raw.get("phone_state") or {}).get("packageName", "")


def to_prompt(elements: list[Element]) -> str:
    return "\n".join(e.to_line() for e in elements)


async def connect(serial: str | None = None, portal_mode: str = "auto") -> AndroidDriver:
    """Connect once and reuse the driver. Serial defaults to ADB_SERIAL from .env.

    portal_mode="required" raises instead of silently falling back to uiautomator,
    whose tree uses different keys (clickable vs isClickable, no isVisibleToUser).
    use_tcp: Portal over an ADB port-forward (localhost only). The default content-provider
    channel takes ~5 s to answer the startup probe over Wi-Fi and fails it about half the time.
    """
    global _driver
    if _driver is None:
        _driver = AndroidDriver(
            serial=serial or get_settings().adb_serial, use_tcp=True, portal_mode=portal_mode
        )
        await _driver.connect()
    return _driver


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
