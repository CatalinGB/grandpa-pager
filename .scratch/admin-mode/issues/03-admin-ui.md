# 03 — Admin UI shape (Pages app)

Type: prototype
Status: resolved
Blocked by:

## Question

How does the admin section look and behave in the GitHub Pages app? Cover the
entry point (a tab / `?admin=1`), the token unlock, the form for the config
fields, a preview, Apply/Reset, and the live device status. Produce a throwaway
UI prototype to react to.

## Answer

Prototype: `.scratch/admin-mode/prototype/admin-ui.html`, three variants
(`?variant=A|B|C`, `?chrome=0` for stills). **Owner picked A — “Secțiuni”.**

Chosen shape:
- Entry: an **Admin** section in the Pages app, gated — shown via `?admin=1` (or a
  small link) and unlocked by pasting the `grandpa-admin` token.
- **One scroll, grouped sections**: Afișare · Semnal la mesaj nou · Mesaje ·
  Kiosk.
- A **live device status** line (connected / battery / wifi / mqtt / current
  message) at the top, and a **JSON preview** of exactly what will be published to
  `grandpa/config`.
- **Reset** (to the recommended defaults) + **Aplică pe telefon** (publish) actions.

Rejected: B (tabs) and C (switch-first cards).

