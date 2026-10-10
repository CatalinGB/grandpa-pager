# Architecture — Grandpa Pager

A custom Android MQTT pager plus a zero-backend static web client. One Kotlin
codebase, one APK, three roles chosen at setup. Senders publish full-screen,
sender-attributed, auto-expiring messages; the receiver (Grandpa's phone)
displays them, enforces expiry locally, and publishes diagnostics. Admins tune
the device remotely over MQTT. No Home Assistant, no server.

## Components

```
   Family (browser, GitHub Pages)          Grandpa's phone (Mi A1, Android 9)
   ┌────────────────────────────┐          ┌──────────────────────────────┐
   │ index.html + sender.js      │          │  ReceiverActivity (kiosk)    │
   │  publish grandpa/message ───┼──┐       │  PagerService (foreground)   │
   └────────────────────────────┘  │       │   (role: receiver)           │
   ┌────────────────────────────┐  │       └──────────────┬───────────────┘
   │ admin.html + admin.js       │  │                      │
   │  publish grandpa/config  ───┼──┤    ┌──────────────┐  │ publish status
   │  subscribe status/config ←──┼──┼───▶│  MQTT broker │◀─┘ (incl. config ack)
   └────────────────────────────┘  │    │  prod: Flespi │
   ┌────────────────────────────┐  │    │  test: Aedes  │
   │ Android SenderActivity      │  │    │  TLS / WSS     │
   │  publish message / clear ───┼──┘    └──────────────┘
   └────────────────────────────┘
```

Foreground `PagerService` owns the MQTT connection in every Android role. The
Eclipse **Paho `mqttv3`** client runs on a single-thread executor; the UI is
notified through the in-process `PagerBus` and by re-reading `SharedPreferences`.

### Modules

| File | Responsibility |
|---|---|
| `PagerCore.kt` | Pure logic: topics, roles, payloads, ISO time, expiry, JSON, change bus. No Android UI — unit-tested on the JVM. |
| `Config.kt` | SharedPreferences config + persisted receiver state (message and remote config). |
| `RemoteConfig.kt` | Remote-config model (`RemoteConfig`) + defensive codec (`RemoteConfigCodec`): parse, clamp, reject bad schema. |
| `TlsUtil.kt` | TLS socket factory = platform trust anchors (+ bundled test CA **in debug only**), wrapped for Paho's no-arg socket and HTTPS endpoint identification. |
| `MqttController.kt` | Paho wrapper: TLS, persistent session, auto-reconnect, subscribe, publish. |
| `PagerService.kt` | Foreground service: connect, receive, show/clear, apply remote config, expiry alarm, watchdog, diagnostics. |
| `ReceiverActivity.kt` | Variant-C display: sender centred above the message (auto-sized), countdown below; idle = one-row date + clock (1.3×); one-shot glow+chime+vibration+torch cue; kiosk entry; admin exit gesture; applies remote config. |
| `DateLabels.kt` | Locale-aware idle labels: `SATURDAY 10/10/2026` and the device time format. |
| `SenderActivity.kt` | Android compose + monitor UI. |
| `SetupActivity.kt` | Role + broker configuration (+ battery-optimization request). |
| `BootReceiver.kt` | Restart service after reboot / update. |
| `DeviceOwnerManager.kt` | Device-owner kiosk: lock task, persistent home, status bar. |
| `index.html` / `sender.js` | GitHub Pages sender (MQTT.js over WSS). |
| `admin.html` / `admin.js` | GitHub Pages admin console (token-gated remote config). |

## Topics

| Topic | Retain | QoS | Direction | Purpose |
|---|---|---|---|---|
| `grandpa/message` | yes | 1 | senders → receiver | Latest message (empty payload clears). |
| `grandpa/command` | no | 1 | senders → receiver | `{"action":"clear"}`. |
| `grandpa/config` | yes | 1 | admin → receiver | Versioned remote config (see below). |
| `grandpa/status` | yes | 1 | receiver → senders/admin | Diagnostics + config ack. |

There is **no MQTT Last Will**: Flespi rejects a CONNECT carrying a Will for a
scoped (ACL) token. Offline is detected by **status staleness** (senders treat a
status older than ~90 s as offline).

## Payloads

Message — `grandpa/message`:

```json
{
  "id": "8f2c…-uuid",
  "sender": "Maria",
  "text": "Lunch is ready",
  "created_at": "2026-10-10T14:54:45Z",
  "expires_at": "2026-10-10T16:54:45Z",
  "duration_minutes": 120
}
```

`sender` is **mandatory** and rendered as the yellow name line.

Diagnostics — `grandpa/status`:

```json
{
  "state": "showing | idle | offline",
  "sender": "Maria", "battery": 68, "charging": true,
  "wifi": true, "mqtt": true,
  "current_message_id": "8f2c…", "expires_at": "2026-10-10T16:54:45Z",
  "last_seen": "2026-10-10T14:54:45Z", "app": "receiver",
  "config": { "schema": 1, "…": "the effective remote config (echo)" },
  "config_applied_at": "2026-10-10T14:54:45Z", "config_error": null
}
```

Remote config — `grandpa/config` (v1):

```json
{ "schema": 1, "updated_at": "2026-10-10T14:54:45Z",
  "display": { "name_size_sp": 46, "countdown": false, "orientation": "auto" },
  "cue": { "glow_ms": 9000, "chime": true, "vibrate": true,
           "torch": { "enabled": true, "on_ms": 1500, "off_ms": 1500, "pulses": 3 } },
  "defaults": { "duration_minutes": 240 },
  "kiosk": { "locked": true } }
```

The receiver **clamps** numerics to bounds and **ignores** unknown/wrong-type
fields; a `schema` other than `1` rejects the whole config (error acked in
`status.config_error`). Bounds: `name_size_sp` 20–120; `glow_ms` 0–20000;
torch `on_ms`/`off_ms` 200–5000; `pulses` 1–10; `duration_minutes` 5–1440. It
persists the accepted config, re-applies it on every (re)connect and reboot, and
**echoes the effective config** in `status.config` so the admin UI prefills.

## Expiry model (application-level)

The receiver never trusts broker TTL.

1. On `showMessage`, `expires_at` is parsed and persisted (epoch millis).
2. An `AlarmManager.setExactAndAllowWhileIdle` alarm is set for `expires_at`.
3. A one-second UI ticker recomputes the countdown from wall-clock, and a
   safety net clears the message if the alarm was missed.
4. On expiry the receiver clears local state, publishes an empty **retained**
   message to `grandpa/message`, and re-publishes `idle` status.
5. On reboot the service restores the persisted message; if already expired it
   clears instead of redisplaying.

Default duration is **240 minutes**, overridable per message (sender) or per
device via `defaults.duration_minutes` (admin).

## Reliability

- `cleanSession=false` + stable clientId → persistent session; queued QoS1
  messages survive brief disconnects.
- Paho `automaticReconnect` **plus a 30 s watchdog** for both roles that
  recreates the client if the link is dead (Paho's auto-reconnect can give up
  after a long outage) and keeps the receiver's diagnostics heartbeat flowing.
- `BootReceiver` restarts the service on `BOOT_COMPLETED` / app update.
- Foreground service (low-importance, ongoing notification) resists being killed;
  battery-optimization exemption requested at setup.
- Echo-loop guard: an empty retained clear is republished only when a message was
  actually held, and never in response to receiving an empty clear.

## Security

- **TLS only** (`ssl://`, port 8883). `TlsUtil` trusts the platform CAs; the
  bundled test CA is trusted **only in debug builds**. The socket factory is
  wrapped to implement Paho's no-arg `createSocket()` and to request **HTTPS
  endpoint identification**.
- **Scoped ACL tokens** per role (receiver, web sender, admin) — never the
  Master token on any client. The browser tokens are origin-locked to the Pages
  site; the receiver token is usable only from a native client.
- The **web sender** ships a strict **Content-Security-Policy** and loads no
  third-party scripts (MQTT.js is vendored). The **admin token** is kept in
  `sessionStorage`, not `localStorage`.
- Lockdown: device-owner lock task removes the power menu, status bar and
  recents; persistent home re-arms the app at boot. Remote config can only
  toggle kiosk lock, never broker/credentials or device-owner status.
- No production secrets are committed; see [`../SECURITY.md`](../SECURITY.md).

## Known limitations / caveats

- **Hostname verification**: Paho connects via the no-arg socket, so endpoint
  identification may have no hostname to check; the primary protection is
  trusting only public CAs (never the test CA in release).
- **Free-tier risk**: Flespi's free tier is labelled "testing & development";
  keep the broker swappable (host/port/user/pass are runtime config).
- **Factory reset** via stock recovery (vol+power) wipes the device regardless of
  device owner — an accepted physical-escape risk.
- **Sender identity is not authenticated** — the `sender` name is typed; anyone
  holding a token can claim any name (adequate for a family, not identity).
- **Family onboarding / remote OTA** are not yet specified.
