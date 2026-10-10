# 04 — Receiver: apply / validate / ack

Type: grilling
Status: open
Blocked by:

## Question

How does the receiver handle an incoming `grandpa/config`?

- Validate (schema version, field bounds) and **reject** bad configs.
- Apply what can change live (display size, cue toggles/timings, default
  duration); handle what needs the activity (kiosk lock/unlock).
- Persist and survive reboot; keep the **last-good** config as fallback.
- **Self-revert** if a config would harm the device.
- **Ack**: reflect the applied config (and any error) in `grandpa/status`.
