# 13 — Receiver layout v2 (variant C)

Type: prototype
Status: resolved
Blocked by: 07

## Question

How should the message and idle screens look, given "show the name only", "text
as big as possible", and "date on one row, clock 30% bigger"?

## Answer

Chosen from a throwaway UI prototype (`android/prototype/receiver-ui.html`,
`?variant=C`, the primary source of all three variants).

- **Message**: sender name centred above the message; message centred and
  auto-sized to the maximum that fits; countdown bottom.
- **Idle**: one-row date = **UPPERCASE weekday + `dd/MM/yyyy`** (e.g.
  `SÂMBĂTĂ 10/10/2026`), clock below at exactly **1.3×** the date size
  (`ReceiverActivity.sizeIdleViews` picks the date size to fit one row, then
  scales the clock).
- Localized weekday; numeric date fixed dd/MM/yyyy. Verified on the Mi A1
  (evidence `docs/evidence/08-idle-connected.png`, `07-attention-glow.png`).
