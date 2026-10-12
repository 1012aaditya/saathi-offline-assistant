# Eval results

Device runs on a Samsung Galaxy Tab S6 Lite (Android 13) · model `gpt-5-nano` · 12 Oct 2026.
Each row is the latest run of that flow after the fixes it prompted (see PROJECT.md decision log).

| Flow | Runs | Success | Median steps | Median time | Median cost | Notes |
|------|------|---------|--------------|-------------|-------------|-------|
| whatsapp_read | 3 | 3/3 | 2 | 15 s | $0.0008 | Answer must quote the contact's last *received* message verbatim |
| whatsapp_reply_hold | 3 | 3/3 | 3 | 33 s | $0.0012 | Stops at the R2 read-back; nothing is sent in eval |
| screen_summary | 3 | 3/3 | 1 | 9 s | $0.0006 | Read-only (any non-`done` action is blocked) |
| blinkit_milk | 10 | 10/10 | 6 | 33 s | $0.0004 | Stops at the R3 read-back; never orders. 7 runs started from an empty cart (7/7); in 3 the cleanup left milk in the cart |
| gpay_send_1 | — | skipped | — | — | — | GPay not set up on the test device |

Success criteria per flow: `eval/flows.yaml`. Re-generate with
`uv run python eval/run_flows.py --flow all --n 10`. Step-by-step traces (local only, contain
personal screens): `traces/<run_id>/`.
