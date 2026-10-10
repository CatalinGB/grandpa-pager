# 12 — Localization

Type: task
Status: resolved
Blocked by: 07, 08

## Question

The app must respect the phone's language. Which languages, and how do we
localize the date and durations?

## Answer

All user-facing text moved to string resources (no hardcoded UI strings).
`values/` (English, default) plus `values-ro` and the top-ten most-used
languages: **es, pt, fr, de, ru, ar, hi, bn, ja, zh** (11 locale folders).

- The idle date uses an `idle_date_fmt` pattern with tokens
  `{EEEE} {do} {d} {MMMM} {yyyy}`; only English uses `{do}` (ordinal, e.g.
  `21st`); other languages use `{d}` with their conventional order. Time uses
  `DateFormat` with the device locale. Verified rendering in Romanian (the test
  phone's language): `sâmbătă, 21 august 2026` / `17:30`.
- Durations (`duration_hm`/`duration_m`/`duration_lt_min`) and the sender status
  labels are localized too.
- **Message content is the sender's responsibility** — the app never translates
  message text.
- `app_name`, `broker_host_hint` and `default_duration` are `translatable="false"`.

Adding more languages later is just dropping in another `values-XX/strings.xml`.
The app already declares `supportsRtl="true"`, so `ar` renders RTL.
