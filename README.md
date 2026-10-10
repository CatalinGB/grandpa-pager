# Grandpa Pager

Turn an old Android phone (Xiaomi Mi A1, Android 9) into an **always-on,
locked-down, remotely-controlled full-screen message pager** for an elderly
relative, using a **free private MQTT broker** and a single custom Kotlin app.

- Messages appear **full-screen** in large high-contrast text, attributed to the
  **sender** (`From: Maria`).
- Messages **auto-expire** (default 4 h, per-message override) and the phone
  returns to a neutral clock.
- Grandpa **cannot escape** the app (device-owner kiosk / lock task mode).
- The phone publishes **diagnostics** (online/offline, battery, charging, Wi-Fi,
  MQTT, current message, last seen) and a **Last Will** for offline detection.

See the design and decisions:

- [`docs/architecture.md`](docs/architecture.md) — components, topics, payloads, expiry, security.
- [`docs/decisions.md`](docs/decisions.md) — why each choice was made.
- [`docs/runbook.md`](docs/runbook.md) — build, test, deploy, undo, troubleshooting.
- [`.scratch/grandpa-pager/`](.scratch/grandpa-pager/) — the wayfinder map, tickets and research.

## Status

Implemented and e2e-tested on the real Mi A1: TLS messaging via `adb reverse`,
full-screen display with sender + countdown, remote clear, expiry, reboot
recovery, Last-Will offline, sender-app publishing, and device-owner kiosk
provisioning. 8 JVM unit tests pass. Evidence in
[`docs/evidence/`](docs/evidence/).

## Quick start

```bash
# 1. test broker (TLS, local)
cd tools/broker && npm install
setsid node server.js </dev/null >/tmp/broker.log 2>&1 &

# 2. build + install
cd ../../android && ANDROID_HOME=$HOME/Android/Sdk mise exec -- gradle assembleDebug
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
android/                     single-module Kotlin app (roles: receiver/admin/family)
docs/                        architecture, decision log, runbook, test evidence
tools/broker/                local Aedes TLS broker (test)
tools/certs/                 test CA + server cert (local only — do not commit keys)
tools/scripts/               e2e MQTT client + example prefs
.scratch/grandpa-pager/      wayfinder map, tickets, research
initial-prompt.md            the original brief
```

## Production note

Point the app at a managed broker (recommended: **EMQX Cloud Serverless**;
EU alternative: **flespi**) with per-topic ACLs, then provision device owner for
the kiosk. Details in [`docs/runbook.md`](docs/runbook.md).

> Security: never use a public MQTT broker for family messages. Never commit the
> test CA private key or production credentials.
