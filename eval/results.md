# Eval results

Generated 2026-10-12 19:07 · model `gpt-5-nano` · 3 runs per flow

| Flow | Success | Rate | Median steps | Median time | Median cost | Failures |
|------|---------|------|--------------|-------------|-------------|----------|
| whatsapp_read | 3/3 | 100% | 2 | 13 s | $0.0007 | — |
| whatsapp_reply_hold | 3/3 | 100% | 3 | 26 s | $0.0010 | — |
| screen_summary | 3/3 | 100% | 1 | 10 s | $0.0006 | — |
| blinkit_milk | 0/3 | 0% | 11 | 107 s | $0.0044 | reached halt (halt) ×3 |
| gpay_send_1 | skipped | — | — | — | — | com.google.android.apps.nbu.paisa.user not installed |

Success criteria and setup per flow: `eval/flows.yaml`. Step-by-step traces (local only, contain personal screens): `traces/<run_id>/`.
