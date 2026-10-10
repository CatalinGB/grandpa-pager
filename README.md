<div align="center">

# Grandpa Pager

**Turn any old Android phone into an always-on, locked-down message pager for an elderly relative.**

A static web sender, a token-gated admin console, and a custom Android app — all
talking over a **private MQTT broker**. No server to run, nothing to maintain.

![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)
![Platform: Android 7+](https://img.shields.io/badge/platform-Android%207%2B-3ddc84.svg)
![MQTT 3.1.1](https://img.shields.io/badge/MQTT-3.1.1-660066.svg)
![No backend](https://img.shields.io/badge/backend-none-success.svg)

</div>

Family sends a message from any browser in seconds; it appears **full-screen** in
large, high-contrast text, attributed to the sender, and clears itself when it
expires. Grandpa can't quit the app, change settings, or wander into other apps.
The phone reports its battery, Wi-Fi, link state and current message so you always
know it's healthy — and an admin can reconfigure it remotely without touching the
device.

## Features

- **Full-screen, high-contrast messages** with the sender's name, auto-sized to
  fill the screen.
- **Automatic expiry** (default 4 h, per-message override) enforced *on the
  device* — never trusting broker retention.
- **Remote clear** and a non-blocking "a message is already showing" warning.
- **Attention cue** on a new message: a slow amber glow, a chime, a double
  vibration and a torch pulse.
- **Live diagnostics**: online/offline, battery + charging, Wi-Fi, MQTT, current
  message and how long ago it was last seen.
- **Admin console** (browser): text size, orientation, countdown, the arrival
  cue, the default duration, and the kiosk lock.
- **Kiosk lockdown** via device-owner Lock Task Mode, re-armed on every boot,
  with a hidden admin escape gesture.
- **Self-healing**: survives reboots, Wi-Fi loss and long broker outages
  (a 30 s reconnect watchdog).
- **Zero backend** and **MIT** licensed.

## Screenshots

<table>
<tr>
<td align="center" width="50%"><img src="docs/screenshots/sender.png" width="230" alt="Web sender"><br><sub>Send a message</sub></td>
<td align="center" width="50%"><img src="docs/screenshots/admin.png" width="230" alt="Admin console"><br><sub>Admin console</sub></td>
</tr>
<tr>
<td align="center" width="50%"><img src="docs/screenshots/receiver-message.png" width="230" alt="Message on the phone"><br><sub>Message on the phone</sub></td>
<td align="center" width="50%"><img src="docs/screenshots/receiver-idle.png" width="230" alt="Idle clock"><br><sub>Idle screen</sub></td>
</tr>
</table>

## How it works

```
   family / admin (browser)                  Grandpa's phone (Android, kiosk)
   ┌───────────────────────┐                 ┌──────────────────────────────┐
   │ index.html · sender.js │                 │ ReceiverActivity (full screen)│
   │ admin.html · admin.js  │                 │ PagerService (foreground)     │
   │  publish message/config│                 │  subscribe message/command/   │
   │  subscribe status      │                 │            config             │
   └───────────┬───────────┘                 └───────────────┬──────────────┘
               │                                             │
               └──────────────►  MQTT broker  ◄──────────────┘
                        TLS 8883 · WSS 443 · per-topic ACL tokens
                         (Flespi in production, Aedes for tests)
```

Four topics carry everything:

| Topic | Direction | Purpose |
|---|---|---|
| `grandpa/message` | senders → phone | the latest message (retained; empty clears) |
| `grandpa/command` | senders → phone | `{"action":"clear"}` |
| `grandpa/config` | admin → phone | versioned remote config (retained) |
| `grandpa/status` | phone → everyone | diagnostics + config acknowledgement |

The phone validates and clamps anything it receives, so a bad config is rejected
and never half-applied. Details in [`docs/architecture.md`](docs/architecture.md).

## User guide

### 1 · Send a message (family)

Open the sender page, type your name and the family token **once** (they stay in
your browser), then type the message, choose how long it should stay on screen,
and tap **Send to screen**. A banner warns if a message is already showing, and
the status panel mirrors the phone.

### 2 · Configure the pager (admin)

Open **Admin** (the link at the bottom of the sender) and enter the admin token.
Tune the display, the new-message cue, the default duration and the kiosk lock,
then **Apply to phone** — the phone validates, applies and confirms it back.

### 3 · On Grandpa's phone

A new message fills the screen with the sender's name above it and fires the
attention cue. When it expires or is cleared, the phone returns to a calm
date-and-clock idle screen.

### 4 · Monitor &amp; control

The **Device status** panel shows `state` (`idle` / `showing` / `offline`), the
MQTT link, battery + charging, Wi-Fi, the current sender and last-seen age.
**Clear the message** wipes the screen remotely.

## Repository layout

```
index.html · sender.js         web sender      (GitHub Pages, MQTT.js over WSS)
admin.html · admin.js          web admin       (token-gated remote config)
mqtt.min.js · sw.js · manifest vendored lib + PWA shell + icons
android/                       Kotlin app (roles: receiver / admin / family)
  app/src/main/java/…          PagerService, MqttController, RemoteConfig, activities
  app/src/test/…               JVM unit tests
docs/                          architecture · decisions · runbook · flespi-setup · screenshots
tools/broker/                  local Aedes TLS/WSS broker (tests)
tools/scripts/                 e2e MQTT client + example prefs
.github/workflows/             CI (build + test) and tagged releases
.scratch/                      design maps, tickets and research (receiver · sender · admin)
```

## Quick start (developer)

```bash
# 1. local test broker (TLS + WSS)
cd tools/broker && npm install
setsid node server.js </dev/null >/tmp/broker.log 2>&1 &

# 2. build + install the app
cd ../../android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleDebug
S=$(adb devices | awk 'NR==2{print $1}')          # the connected phone
adb -s $S install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $S reverse tcp:8883 tcp:8883                 # phone -> host broker over USB

# 3. configure it as the receiver, then send a message
cd ../tools && NODE_PATH=$PWD/broker/node_modules node scripts/mqtt.js send "Lunch is ready" 120 "Maria"
```

Full build/test/deploy/undo steps and a troubleshooting table are in
[`docs/runbook.md`](docs/runbook.md).

## Deploy to production

1. **Broker** — create a free [Flespi](https://flespi.com) account and mint three
   **scoped ACL tokens** (receiver, web sender, admin); never ship the Master
   token. Exact ACL JSON is in [`docs/flespi-setup.md`](docs/flespi-setup.md).
2. **Receiver** — configure the app on the phone with the receiver token
   (`mqtt.flespi.io:8883`, TLS), then lock it down:
   ```bash
   adb shell dpm set-device-owner com.grandpapager/.PagerDeviceAdminReceiver
   ```
3. **Web** — publish the repo to GitHub Pages. Family open the sender, admins
   open `…/admin.html`; both connect over `wss://mqtt.flespi.io:443`.
4. **Power** — enable *Stay awake* and grant the battery-optimization exemption
   (offered in setup).

## Security

- **TLS only**; the bundled test CA is trusted in debug builds only, and the
  socket factory forces HTTPS endpoint identification.
- **Least-privilege tokens**: receiver, sender and admin each get their own
  per-topic ACL token, origin-locked for the browser ones. The Master token never
  leaves your PC.
- **Hardened web page**: strict Content-Security-Policy, no third-party scripts
  (MQTT.js is vendored), admin token kept in `sessionStorage`.
- **Kiosk**: device-owner Lock Task Mode; remote config can only toggle the lock
  — never credentials or device-owner status.

Full audit and residual risks: [`SECURITY.md`](SECURITY.md).

## Documentation

- [`docs/architecture.md`](docs/architecture.md) — components, topics, payloads, remote config.
- [`docs/decisions.md`](docs/decisions.md) — why each choice was made.
- [`docs/runbook.md`](docs/runbook.md) — build, test, deploy, undo, troubleshooting.
- [`docs/flespi-setup.md`](docs/flespi-setup.md) — broker and token setup.
- [`.scratch/`](.scratch/) — the wayfinder maps, tickets and research.

## Releases

Push a `v*` tag and GitHub Actions builds `app-release.apk` and attaches it to a
Release. It signs with the debug key by default; set the `KEYSTORE_B64` /
`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` repo secrets (or a local
`android/keystore.properties`) for a real key. Every push and PR runs the CI
workflow (debug build + unit tests).

## License

[MIT](LICENSE) © Catalin Bivolaru
