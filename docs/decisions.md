# Decision Log — Grandpa Pager

Autonomous decisions taken 2026-10-10 with the owner unavailable ("you will
remain alone… answer as good as possible"). Each entry: decision, why, what
would change it. See `../.scratch/grandpa-pager/` for the map and tickets and
`.scratch/grandpa-pager/research/` for the underlying research.

## D1 — Architecture: pure MQTT + a single custom Android app
Rejected Home Assistant (owner: no HA), self-hosted web kiosk, and commercial
kiosk. Chosen: **one Kotlin codebase, one APK, a role switch**
(`receiver` | `sender_admin` | `sender_family`). Rationale: owner wanted exactly
"two types of phone apps (might be a single app with a config switch)". Single
codebase shares schema/MQTT code and ships one artifact.
*Change if*: family onboarding proves to need a radically simpler surface.

## D2 — Broker: EMQX Cloud Serverless (primary), flespi (EU runner-up)
Free-forever, spend-limit 0 (cannot bill), TLS-only on 8883, username/password,
per-topic ACLs, retained + LWT, MQTT 3.1.1/5. Caveat: US data residency.
flespi is the EU alternative (`mqtt.flespi.io:8883`, token auth) but its free
tier is labelled dev/test. HiveMQ's free Serverless tier is being retired
(EOL 2026-12-31) — a warning to keep the broker layer swappable.
*Local e2e does not use a cloud broker*: the host runs **Aedes** over TLS.

## D3 — MQTT client library: Eclipse Paho `mqttv3` (core)
Research recommended `hannesa2/paho.mqtt.android`, but that ships via JitPack
and is a one-maintainer fork. For a reproducible build we use the stable
upstream **`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`** (Maven
Central) and own the connection lifecycle in a foreground `Service`. TLS,
retained, LWT, persistent session and `automaticReconnect` are all supported.
*Change if* the Android-service convenience outweighs build fragility.

## D4 — Message & diagnostics schema
JSON, Gson-serialised. Mandatory **`sender`** (owner requirement: sender must
appear). Application-level **`expires_at`** (never trust broker TTL), default
240 min. Retained message topic so the latest message survives reconnect. Status
topic retained so senders can show the last known state.
(Full schema in `architecture.md`.)

## D5 — Lockdown: device owner + lock task mode, provisioned over ADB
Mi A1 is Android One / Android 9, single user, **no accounts** → `dpm
set-device-owner` works without a factory reset. Use true kiosk Lock Task Mode
with `setLockTaskFeatures(NONE)` to remove the power menu / status bar /
recents, pin the app as persistent HOME so it re-arms on boot, plus
stay-on-while-charging and doze whitelist.
*Accepted risk*: stock-recovery factory reset (vol+power) still wipes the
device; documented in the runbook.

## D6 — Minimum SDK / build target
Kotlin, `minSdk 24` (Android 7) to leave headroom, `targetSdk 28` to match the
device, `compileSdk 34`. No AndroidX (plain `android.*` widgets) to keep the
build light and conflict-free; `compileOptions`/`kotlinOptions` at Java 17.

## D7 — Expiry model
Persist `expires_at` (epoch millis) in `SharedPreferences`; a 1-second UI
ticker and a `setExactAndAllowWhileIdle` alarm both recompute remaining time
from wall-clock, so expiry is correct across reboot and Doze. On expiry the
receiver clears state and returns to the idle screen; it also clears the
retained message topic.

## D8 — Topics (see architecture.md for payloads)
- `grandpa/message` — retained, QoS 1. Latest message JSON (`{}` / empty clears).
- `grandpa/command` — QoS 1. `clear` · `set_connection` · `restart_app` · `reboot_device`.
- `grandpa/config` — retained, QoS 1. Admin remote config (v1).
- `grandpa/status` — retained, QoS 1. Receiver diagnostics + config ack.

## D9 — Admin mode: remote config over MQTT
An **admin console** in the Pages app (gated by an admin token) tunes the device
by publishing a **versioned config** to `grandpa/config`; the receiver
**validates, clamps, persists, applies and acks** it in `status.config`. Scope
was originally display, new-message cue, default duration and the kiosk lock
(`locked:false` → stop lock task + open the launcher; never touches device
owner). It now also covers the extended layout knobs and the **device-owner
restriction toggles** (see D14/D16). The retained config still cannot change
broker/credentials or device-owner status, so it cannot cut the channel.
Rationale: the owner wanted the pager configurable without a rebuild or USB.
*Change if* per-field audit/history is needed.

## D10 — Admin token provisioning
A dedicated **ACL** token `grandpa-admin` (publish+subscribe `grandpa/config`,
subscribe `grandpa/status`), **origin-locked** to the Pages site, held in
`sessionStorage`. Never the Master token (which must never live in a browser).
Possession of the token equals its authority — keep it narrow and rotatable.

## D11 — No Last Will; offline via staleness
Flespi rejects a CONNECT that carries a Last Will for a scoped (ACL) token, so
the receiver sets **no Will**. Offline detection is **status staleness**:
senders flag a `status` older than the derived threshold (below), not a fixed
90 s. (Supersedes the LWT plan in the original D4 text.)

## D12 — Idle screen-off with a guaranteed wake
The display sleeps when idle (device-owner `lockNow()`, no clock) to cut heat and
panel wear; a new message wakes it with a **full-screen-intent** notification on
an `IMPORTANCE_HIGH` channel plus `setTurnScreenOn`/`setShowWhenLocked`, while a
`PARTIAL_WAKE_LOCK` + `WifiLock` keep the socket alive. Rationale: on a
permanently-powered pager the backlight is the dominant idle draw; the socket
must stay up for immediate delivery, so the screen is the only thing that can
safely sleep. It is admin-toggleable (`power.screen_off_when_idle`). *Change if*
the device is ever used off-mains, where a permanent wake lock would matter.
(Research: `.scratch/grandpa-pager/research/power-and-metrics.md`.)

## D13 — Configurable metrics cadence (default 15 min)
Status is published on `telemetry.interval_minutes` (1–1440, default 15) plus
immediately on connect/show/clear/apply. The status timer is decoupled from the
30 s reconnect watchdog, because the MQTT keepalive — not the status publish —
keeps the socket up. Both web clients derive their "stale" threshold from the
config (`2×interval + 60 s`, min 90 s) instead of a hard-coded 90 s. Added as an
optional field under `schema: 1` (backward-compatible).

## D14 — In-app device hardening (device owner)
When device owner the app applies a **reversible** set of user restrictions
(no account changes, no Bluetooth, no location, no printing, no mobile-network
config, no tethering, no roaming), disables master sync and turns off Bluetooth,
to cut background activity and attack surface on a 24/7 device. The set is driven
by the `device_owner` remote-config block (each toggle adds **or clears** its
restriction; relaxing Bluetooth re-enables the radio). The network restrictions
(mobile config, tethering, roaming) default on but are configurable like the
rest. Cleared by the `RELEASE_KIOSK` gesture. Deliberately **not**
applied: factory-reset blocking or anything irreversible.
(Battery/heat guidance lives in `device-hardening.md`.)

## D15 — Delivery-reliability hardening
Four small guards so a message is not silently lost or duplicated:
- **Late arrival:** a never-seen message that arrives up to 10 min after its TTL
  is shown once for 60 s (see `architecture.md`), so a brief offline blip does not
  erase it.
- **Battery exemption:** the app warns on start if the doze whitelist is missing
  and asks once outside kiosk; Android 9 has no device-owner API for it, so the
  deterministic path (`adb shell dumpsys deviceidle whitelist +<pkg>`) is
  documented in `device-hardening.md`/`runbook.md`.
- **One duration default:** the web sender adopts `defaults.duration_minutes` from
  `status.config` (until the user overrides it), and the Android sender does the
  same — the admin default now governs all senders instead of only the app.
- **Silent wake channel:** the `pager_alert` channel is silent and non-vibrating so
  the notification that carries the full-screen intent does not double the app's
  own chime/vibration.

## D16 — Remote broker change: a guarded command, never retained config
The in-situ phone is hard to reach, so the broker (host/port/TLS/username/password)
and the MQTT tuning (keepalive, timeout, auto-reconnect, QoS, max inflight) must be
changeable from the admin console. But they carry a secret and can strand the
device, so they are **not** part of the retained `grandpa/config` (which is echoed
in status and cannot cut the channel, per D9). Instead a `set_connection` command
carries a **candidate** plus a revert window; the receiver runs a pure
`ConfigGuard` state machine — probe, switch, commit on a stable link, else
**auto-revert** to the last working connection within the window — and persists the
pending state so a reboot resumes (and, if unconfirmed, reverts). The password is
device setup only and is never echoed (`status` reports `password_set`). Topics and
the client-id scheme stay constants (changing them would strand the web clients).
*Change if* concurrent-connection broker limits make the probe unreliable.

## D17 — Device-control commands: restart and reboot
`restart_app` restarts the foreground `PagerService` for real (schedule a fresh
start, then `stopSelf()`) and brings `ReceiverActivity` back; `reboot_device`
uses the device-owner `DevicePolicyManager.reboot()`. Both are
acknowledged in `status.command` (`last`, `received_at`, `result`). Reboot is only
attempted when the app is device owner; otherwise it is reported, never silently
ignored. The device owner is never removed remotely.



