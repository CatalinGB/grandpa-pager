# 04 — Receiver: apply / validate / ack

Type: grilling
Status: resolved
Blocked by:

## Question

How does the receiver handle an incoming `grandpa/config`?

- Validate (schema version, field bounds) and **reject** bad configs.
- Apply what can change live (display size, cue toggles/timings, default
  duration); handle what needs the activity (kiosk lock/unlock).
- Persist and survive reboot; keep the **last-good** config as fallback.
- **Self-revert** if a config would harm the device.
- **Ack**: reflect the applied config (and any error) in `grandpa/status`.

## Answer

- **Subscribe & apply**: receiver subscribes to the **retained** `grandpa/config`
  and applies on **every (re)connect** and on each message (so settings survive
  reboot/offline).
- **Validation**: `schema` ≠ 1 → **reject the whole config** (error ack).
  Otherwise **clamp** numerics to the ticket-02 bounds and **ignore**
  wrong-type/unknown fields (fall back to default). Never crash.
- **Apply**: cheap fields (name size, cue toggles/timings, default duration)
  apply **immediately**; `kiosk.locked` is handled by the activity — apply now if
  foreground, else on next resume. `locked:false` = `stopLockTask()` + open the
  launcher; never touches device owner.
- **Persist + reboot**: accepted config stored in `SharedPreferences`; the service
  re-applies it at boot and re-subscribes for any newer retained config.
- **Safety**: config can't touch broker/token (on-device), so it can't cut the
  channel → **no revert timer**; clamp + keep the **last-good** config; a
  malformed config is rejected, never half-applied; log every apply.
- **Ack**: immediately republish status (retained) with
  `config: { schema, applied_at, error }` **and** an echo of the applied
  `display`/`cue`, so the admin sees the effect.
- **Read-back**: `status.config` carries the **effective config** so the admin UI
  prefills the form with current values.
- **Token**: added `subscribe grandpa/config` to `grandpa-phone-receiver` so it
  can hear the admin (verified).

