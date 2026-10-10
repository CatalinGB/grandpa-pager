# Architecture — Grandpa Pager

A custom Android MQTT pager. One Kotlin codebase, one APK, three roles chosen at
setup. Senders publish full-screen, sender-attributed, auto-expiring messages;
the receiver (Grandpa's phone) displays them, enforces expiry locally, and
publishes diagnostics. No Home Assistant, no web server.

## Components

```
   Admin / family phone(s)                 Grandpa's phone (Mi A1, Android 9)
   ┌────────────────────┐                  ┌──────────────────────────────┐
   │  SenderActivity     │                  │  ReceiverActivity (kiosk)    │
   │  PagerService ──────┼─ publish ──┐     │  PagerService (foreground)   │
   │   (role: sender)    │            │     │   (role: receiver)           │
   └────────────────────┘            ▼     └──────────────┬───────────────┘
                          ┌──────────────────────────┐     │
                          │  MQTT broker (TLS 8883)   │◄────┘  publish status
                          │  prod: EMQX Cloud        │        + Last Will
                          │  test: Aedes (this repo)  │
                          └──────────────────────────┘
```

Foreground `PagerService` owns the MQTT connection in both roles. The Eclipse
**Paho `mqttv3`** client runs on a single-thread executor; UI is notified through
the in-process `PagerBus` and by re-reading `SharedPreferences` state.

### Modules

| File | Responsibility |
|---|---|
| `PagerCore.kt` | Pure logic: topics, roles, payloads, ISO time, expiry, JSON, change bus. No Android UI — unit-tested on the JVM. |
| `Config.kt` | SharedPreferences config + persisted receiver state. |
| `TlsUtil.kt` | Trust store = bundled CA (`res/raw/ca.crt`) **and** platform anchors. |
| `MqttController.kt` | Paho wrapper: TLS, persistent session, auto-reconnect, LWT, subscribe, publish. |
| `PagerService.kt` | Foreground service: connect, receive, show/clear, expiry alarm, heartbeat, diagnostics. |
| `ReceiverActivity.kt` | Variant-C display: sender centred above the message (auto-sized to the max that fits), countdown below; idle = one-row date + clock (1.3×) with an in-place connection warning; one-shot glow+chime+vibration arrival cue; kiosk entry; admin exit-kiosk. |
| `DateLabels.kt` | Locale-aware idle labels: `SÂMBĂTĂ 10/10/2026` (uppercase weekday + dd/MM/yyyy) and the device time format. |
| `SenderActivity.kt` | Compose + monitor UI. |
| `SetupActivity.kt` | Role + broker configuration. |
| `BootReceiver.kt` | Restart service after reboot / update. |
| `DeviceOwnerManager.kt` | Device-owner kiosk: lock task, persistent home, status bar. |

## Topics

| Topic | Retain | QoS | Direction | Purpose |
|---|---|---|---|---|
| `grandpa/message` | yes | 1 | senders → receiver | Latest message (empty payload clears). |
| `grandpa/command` | no | 1 | senders → receiver | `{"action":"clear"}`. |
| `grandpa/status` | yes | 1 | receiver → senders | Diagnostics; also the **Last Will** (`state:"offline"`). |

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

`sender` is **mandatory** and rendered as the yellow `From: …` line.

Diagnostics — `grandpa/status`:

```json
{
  "state": "showing | idle | offline",
  "sender": "Maria", "battery": 68, "charging": true,
  "wifi": true, "mqtt": true,
  "current_message_id": "8f2c…", "expires_at": "2026-10-10T16:54:45Z",
  "last_seen": "2026-10-10T14:54:45Z", "app": "receiver"
}
```

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

Default duration is **240 minutes**, overridable per message (sender field).

## Reliability

- `cleanSession=false` + stable clientId → persistent session; queued QoS1
  messages survive brief disconnects.
- Paho `automaticReconnect` + a 5 s retry loop for the initial connect.
- `BootReceiver` restarts the service on `BOOT_COMPLETED`.
- 30 s diagnostics heartbeat; Last Will marks the device offline on abrupt loss.
- Foreground service (low-importance, ongoing notification) resists being killed;
  battery-optimization exemption requested at setup.
- Echo-loop guard: an empty retained clear is republished only when a message
  was actually held, and never in response to receiving an empty clear.

## Security

- TLS only (`ssl://`, port 8883). Trust store accepts the bundled test CA **and**
  public CAs (so the production broker needs no repackaging).
- Username/password MQTT auth. Production broker should use **per-topic ACLs**
  and per-role credentials.
- The compose surface is the app itself over MQTT — never a public web page.
- Lockdown: device-owner lock task removes the power menu, status bar and
  recents; persistent home re-arms the app at boot.
- No secrets are committed for production; the test CA/key under `tools/certs`
  is local-only (see `.gitignore` note in the runbook).

## Known limitations / caveats
- **Hostname verification**: Paho 1.2.5 relies on the supplied `SSLSocketFactory`
  and does not enable endpoint identification by default. The test cert matches
  `127.0.0.1`; for production use a broker cert and consider pinning.
- **Free-tier risk**: HiveMQ retired its free Serverless tier (EOL 2026-12-31) →
  keep the broker swappable (host/port/user/pass are runtime config).
- **Factory reset** via stock recovery (vol+power) wipes the device regardless of
  device owner — an accepted physical-escape risk.
- **Family onboarding** and **remote OTA after deployment** are not yet specified
  (see the map's "Not yet specified").
- The sender UI has no "family" PIN gating yet beyond role selection.
