# Eval results

Generated 2026-10-12 20:10 · model `gpt-5-nano` · 3 runs per flow

| Flow | Success | Rate | Median steps | Median time | Median cost | Failures |
|------|---------|------|--------------|-------------|-------------|----------|
| whatsapp_read | 2/3 | 67% | 2 | 16 s | $0.0008 | summary does not quote the last received message ×1 |
| whatsapp_reply_hold | 3/3 | 100% | 3 | 33 s | $0.0012 | — |
| screen_summary | 3/3 | 100% | 1 | 9 s | $0.0006 | — |
| blinkit_milk | 1/3 | 33% | 25 | 235 s | $0.0104 | reached halt (halt) ×2 |
| gpay_send_1 | skipped | — | — | — | — | com.google.android.apps.nbu.paisa.user not installed |

Success criteria and setup per flow: `eval/flows.yaml`. Step-by-step traces (local only, contain personal screens): `traces/<run_id>/`.
