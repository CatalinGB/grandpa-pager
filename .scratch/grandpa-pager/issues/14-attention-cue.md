# 14 — New-message attention cue

Type: prototype
Status: resolved
Blocked by: 13

## Question

How do we draw an elderly, low-tech user's attention to a newly arrived message
— and for how long?

## Answer

Research: `.scratch/grandpa-pager/research/elderly-attention-alerts.md` (WCAG
2.3.1 flash limits, habituation, multi-modal cues for older adults).

Chosen: a **one-shot tri-modal arrival burst** —
1. a slow **amber screen-glow** pulse (~0.5 Hz, ~6 s) then settle to a static
   high-contrast card (`ReceiverActivity.playArrivalCue`, `glow_border` + a
   `ValueAnimator`);
2. a **notification chime** (confirmed firing on the Mi A1);
3. a **double vibration** (`VIBRATE` permission).

No fast flashing, no looping. The **same message is never re-cued** —
`Config.lastCuedMessageId` persists the last alerted id across restarts. Verified
on device (evidence `docs/evidence/07-attention-glow.png`). Sound + haptics
depend on the phone's volume/DND; field-test with Grandpa.
