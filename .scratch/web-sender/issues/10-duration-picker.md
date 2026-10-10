# 10 — Friendly expiration-time picker

Type: prototype
Status: open
Blocked by:

## Question

What friendly control should let a sender pick how long the message stays on
screen?

## Answer

Prototype: `.scratch/web-sender/prototype/duration-picker.html`
(`?variant=A|B|C`, `?chrome=0` for stills). Three variants, all with a live
readout and default 4 h:

- **A — Preset buttons** (recommended): big 30 min / 1 h / 2 h / **4 h** / 8 h /
  24 h chips + a ±15-minute fine adjust.
- **B — Stepper**: a large − / value / + control in hours + quick presets.
- **C — Slider**: 15 min–24 h range with tick labels.

**Awaiting the owner's pick** (A is the friendliest for non-technical senders).
