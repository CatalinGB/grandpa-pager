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
- `grandpa/command` — QoS 1. `{"action":"clear"}`.
- `grandpa/config` — retained, QoS 1. Admin remote config (v1).
- `grandpa/status` — retained, QoS 1. Receiver diagnostics + config ack.

## D9 — Admin mode: remote config over MQTT
An **admin console** in the Pages app (gated by an admin token) tunes the device
by publishing a **versioned config** to `grandpa/config`; the receiver
**validates, clamps, persists, applies and acks** it in `status.config`. Scope is
deliberately narrow: display, new-message cue, default duration, and the kiosk
lock (`locked:false` → stop lock task + open the launcher; never touches device
owner). Config cannot change broker/credentials or device-owner status, so it
cannot cut the channel. Rationale: the owner wanted the pager configurable
without a rebuild or USB. *Change if* per-field audit/history is needed.

## D10 — Admin token provisioning
A dedicated **ACL** token `grandpa-admin` (publish+subscribe `grandpa/config`,
subscribe `grandpa/status`), **origin-locked** to the Pages site, held in
`sessionStorage`. Never the Master token (which must never live in a browser).
Possession of the token equals its authority — keep it narrow and rotatable.

## D11 — No Last Will; offline via staleness
Flespi rejects a CONNECT that carries a Last Will for a scoped (ACL) token, so
the receiver sets **no Will**. Offline detection is **status staleness**: senders
flag a `status` older than ~90 s as offline. The 30 s watchdog keeps status
fresh. (Supersedes the LWT plan in the original D4 text.)

