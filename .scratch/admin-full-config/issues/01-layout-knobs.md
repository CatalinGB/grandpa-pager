# 01: Extend remote layout knobs

**What to build:** The admin can tune the parts of the receiver's presentation that
are currently hardcoded — message auto-size bounds, sender/message/warning/countdown
colours, idle clock scale, idle date format, and the connection-warning text —
publish them as retained config, and see the phone apply them.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] The retained config schema gains the extended `display` fields as optional keys under `schema: 1`; absent fields fall back to today's values.
- [x] Colours validate as `#AARRGGBB` (invalid → default); numeric fields clamp; `idle_date_format` accepts a small enum plus `auto`; `warn_text: null` keeps the localized string.
- [x] The receiver applies name colour, message colour, message auto-size min/max, warning colour, countdown colour, idle clock scale and date format.
- [x] The admin console exposes a control for each new field and round-trips it through `grandpa/config`.
- [x] Unit tests cover parse, clamp, colour fallback, enum fallback, and absent-field defaults.
