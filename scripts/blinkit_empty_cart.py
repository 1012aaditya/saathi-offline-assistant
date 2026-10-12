"""Empty the Blinkit cart (owner-requested cleanup between eval runs).

Opens the cart via a search link + "View cart", then taps "Decrease quantity" only on items
listed in the cart (above "You might also like") until the cart bar disappears. Never taps
checkout / payment. Every tap passes safety.check() first.

Usage: uv run python scripts/blinkit_empty_cart.py
"""

import asyncio
import sys

from saathi import blinkit, device, safety
from saathi.actions import Back, Scroll, Search, Tap
from saathi.config import BLINKIT

MAX_TAPS = 30


async def gated_tap(screen: device.Screen, index: int) -> bool:
    """Tap if safety allows. False if the button is covered (R11): caller scrolls and retries."""
    action = Tap(index=index)
    verdict = safety.check(action, screen)
    if verdict.rule == "R11":
        return False
    if verdict.kind != "allow":
        sys.exit(
            f"safety said {verdict.kind}/{verdict.rule} for {screen.elements[index].to_line()}"
        )
    await device.execute(action, screen)
    return True


async def main() -> None:
    s = await device.observe()
    await device.execute(Search(query="milk"), s)
    await asyncio.sleep(1)
    taps = 0
    while taps < MAX_TAPS:
        s = await device.observe()
        if s.package != BLINKIT:
            sys.exit(f"left Blinkit ({s.package}); stopping")
        if blinkit.is_checkout(s):
            items = blinkit.cart_items(s)
            if not items:
                break
            dec = next(
                (e for e in s.elements
                 if e.desc == "Decrease quantity" and blinkit._inside(e, items[0].card)),
                None,
            )  # fmt: skip
            if dec is None:
                sys.exit(f"no − button for {items[0].name}; stopping")
            if not await gated_tap(s, dec.index):
                # Under the floating checkout bar: scroll the list up and look again.
                await device.execute(Scroll(direction="down"), s)
                await asyncio.sleep(1)
                taps += 1
                continue
            print(f"  − {items[0].name} (qty {items[0].qty})")
            taps += 1
            await asyncio.sleep(1.2)
            continue
        n = blinkit.cart_count(s)
        for _ in range(8):  # the cart bar renders ~1 s after the products: don't conclude early
            if n:
                break
            await asyncio.sleep(0.5)
            s = await device.observe()
            n = blinkit.cart_count(s)
        if not n:
            break  # still no cart bar after waiting = empty cart
        bar = next(e for e in s.elements if "View cart" in f"{e.text} {e.desc}")
        await gated_tap(s, bar.index)
        await asyncio.sleep(2)
    s = await device.observe()
    left = blinkit.cart_items(s) if blinkit.is_checkout(s) else []
    print("cart empty" if not left else f"still in cart: {[c.name for c in left]}")
    await device.execute(Back(), s)
    await device.restore_keyboard()


if __name__ == "__main__":
    asyncio.run(main())
