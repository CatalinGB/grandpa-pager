# 02 — Config schema (what "everything" means)

Type: grilling
Status: resolved
Blocked by:

## Question

Define the **versioned config** the admin publishes to `grandpa/config`, and the
fields v1 covers. Proposed shape:

```json
{
  "schema": 1,
  "updated_at": "ISO-8601",
  "display": { "name_size_sp": 46, "countdown": false, "orientation": "auto" },
  "cue": { "glow_ms": 9000, "chime": true, "vibrate": true,
           "torch": { "enabled": true, "on_ms": 1500, "off_ms": 1500, "pulses": 3 } },
  "defaults": { "duration_minutes": 240 },
  "kiosk": { "locked": true }
}
```

Decide: which fields, sensible bounds, what is read-only vs settable, and how a
`schema` mismatch is handled (reject vs migrate).

## Answer

Terms: **remote config** (admin → `grandpa/config`: display/cue/defaults/kiosk)
vs **device setup** (role/broker/token, on-device only — never remote).

Canonical v1 config (retained JSON on `grandpa/config`):

```json
{ "schema": 1, "updated_at": "ISO-8601",
  "display": { "name_size_sp": 46, "countdown": false, "orientation": "auto" },
  "cue": { "glow_ms": 9000, "chime": true, "vibrate": true,
           "torch": { "enabled": true, "on_ms": 1500, "off_ms": 1500, "pulses": 3 } },
  "defaults": { "duration_minutes": 240 },
  "kiosk": { "locked": true } }
```

**Bounds — the receiver CLAMPS within range, it does not reject:**
- `display.name_size_sp` 20–120; `countdown` bool; `orientation` `auto|portrait|landscape`
- `cue.glow_ms` 0–20000; `chime`/`vibrate` bool
- `cue.torch.enabled` bool; `on_ms`/`off_ms` 200–5000; `pulses` 1–10
- `defaults.duration_minutes` 5–1440
- `kiosk.locked` bool

**Versioning:** integer `schema` (start 1). Unknown/older schema → **reject** and
report an error; **unknown fields ignored** (forward-compatible); the receiver
echoes the applied `schema` in status.

**Kiosk:** `locked:true` → ensure lock task armed; `locked:false` →
`stopLockTask()` + open the launcher. **Never removes the device owner.** Each
change is logged.

**Split:** `grandpa/config` owns display/cue/defaults/kiosk; device setup
(role/broker/token) stays on-device and is shown **read-only** in the admin UI.

**Ack:** `grandpa/status` gains `config: { schema, applied_at, error }` plus an
echo of the applied display/cue values.

