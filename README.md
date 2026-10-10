# Grandpa Pager

Turn an old Android phone (Xiaomi Mi A1, Android 9) into an **always-on,
locked-down, remotely-controlled full-screen message pager** for an elderly
relative, using a **free private MQTT broker**, a **zero-backend static web
sender**, and a single custom Kotlin app.

- Messages appear **full-screen** in large high-contrast text, attributed to the
  **sender** (`Maria`).
- Messages **auto-expire** (default 4 h, per-message override) and the phone
  returns to a neutral clock.
- Grandpa **cannot escape** the app (device-owner kiosk / lock task mode).
- The phone publishes **diagnostics** (online/offline, battery, charging, Wi-Fi,
  MQTT, current message, last seen) so family can see the device at a glance.
- An **admin console** remotely tunes the display, the new-message cue
  (glow / chime / vibration / torch), the default duration and the kiosk lock.

Design and decisions:

- [`docs/architecture.md`](docs/architecture.md) — components, topics, payloads, expiry, security.
- [`docs/decisions.md`](docs/decisions.md) — why each choice was made.
- [`docs/runbook.md`](docs/runbook.md) — build, test, deploy, undo, troubleshooting.
- [`docs/flespi-setup.md`](docs/flespi-setup.md) — broker + token setup.
- `.scratch/` — the wayfinder maps, tickets and research for the
  [receiver](.scratch/grandpa-pager/), [web sender](.scratch/web-sender/) and
  [admin mode](.scratch/admin-mode/).

## User guide

### 1. Send a message (family)

Open **https://catalinggb.github.io/grandpa-pager/**, type your name and the
family token **once** (both stay in your browser only). Then type the message,
choose how long it should stay on screen, and tap **Trimite pe ecran**. A banner
warns you if a message is already showing (sending replaces it), and the status
panel shows the phone's battery, Wi-Fi, MQTT link and when it was last seen.

![Web sender](docs/screenshots/sender.png)

### 2. Configure the pager (admin)

Open the **Admin** link at the bottom of the page (or `…/admin.html`) and enter
the admin token. Tune the text size, orientation and countdown; the new-message
cue (glow, chime, vibration, torch); the default duration; and the kiosk lock.
**Aplică pe telefon** publishes the config, which the phone validates, applies and
confirms back in the status line. **Reset** restores the recommended defaults.

![Admin console](docs/screenshots/admin.png)

### 3. On Grandpa's phone

A new message fills the screen with the sender's name above it and a one-shot
attention cue (a slow amber glow, a chime, a double vibration and a torch pulse).
When the message expires — or is cleared — the screen returns to a calm
date-and-clock idle view. A soft *„Probleme cu conexiunea"* line appears on idle
if the link drops.

![Message on Grandpa's phone](docs/screenshots/receiver-message.png)
![Idle clock](docs/screenshots/receiver-idle.png)

### 4. Monitor and control

Signals appear on the sender's **Starea dispozitivului** panel: `state`
(`idle` / `showing` / `offline`), `mqtt`, battery + charging, Wi-Fi, the current
sender and how long ago the phone was last seen. **Șterge mesajul de pe ecran**
clears the display remotely.

## Status

Implemented and e2e-tested on the real Mi A1: TLS messaging, full-screen display
with sender + auto-sized text, arrival cue (glow/chime/vibration/torch), remote
clear, expiry, reboot recovery, online/offline diagnostics, the browser sender
and admin console over WSS, and device-owner kiosk provisioning. 8 JVM unit tests
pass. Evidence in [`docs/evidence/`](docs/evidence/), screenshots in
[`docs/screenshots/`](docs/screenshots/).

## Quick start

```bash
# 1. test broker (TLS + WSS, local)
cd tools/broker && npm install
setsid node server.js </dev/null >/tmp/broker.log 2>&1 &

# 2. build + install
cd ../../android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleDebug
S=$(adb devices | awk 'NR==2{print $1}')
adb -s $S install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $S reverse tcp:8883 tcp:8883

# 3. configure as receiver (see tools/scripts/prefs-receiver.xml)
adb -s $S push ../tools/scripts/prefs-receiver.xml /data/local/tmp/pager.xml
adb -s $S shell "run-as com.grandpapager cp /data/local/tmp/pager.xml shared_prefs/pager.xml"
adb -s $S shell am start -n com.grandpapager/.ReceiverActivity

# 4. send a message
cd ../tools && NODE_PATH=$PWD/broker/node_modules node scripts/mqtt.js send "Lunch is ready" 120 "Maria"
```

## Layout

```
index.html, sender.js         web sender   (GitHub Pages, WSS → broker)
admin.html, admin.js          web admin    (token-gated remote config)
mqtt.min.js                   vendored MQTT.js (no CDN dependency)
sw.js, manifest.webmanifest   PWA shell + icons
android/                      single-module Kotlin app (roles: receiver/admin/family)
  app/src/main/java/…         PagerService, MqttController, RemoteConfig, activities
  app/src/test/…              JVM unit tests
docs/                         architecture, decision log, runbook, evidence, screenshots
tools/broker/                 local Aedes TLS/WSS broker (test)
tools/certs/                  test CA + server cert (local only — do not commit keys)
tools/scripts/                e2e MQTT client + example prefs
.github/workflows/            CI (build + tests) and tagged releases
```

## Releases

Tag a version (`git tag v1.0 && git push --tags`) and the release workflow builds
`app-release.apk` and attaches it to a GitHub Release. By default it is signed
with the debug key; provide the `KEYSTORE_B64` / `KEYSTORE_PASSWORD` /
`KEY_ALIAS` / `KEY_PASSWORD` repo secrets (or a local
`android/keystore.properties`) for a real signing key. See
[`docs/runbook.md`](docs/runbook.md) §5b.

## Production note

Point the app at a managed broker (recommended: **EMQX Cloud Serverless**;
EU alternative: **flespi**) with per-topic ACLs, then provision device owner for
the kiosk. Details in [`docs/runbook.md`](docs/runbook.md) and
[`docs/flespi-setup.md`](docs/flespi-setup.md).

> Security: never use a public MQTT broker for family messages. Never commit the
> test CA private key, the keystore, or production credentials.

## License

[MIT](LICENSE).
