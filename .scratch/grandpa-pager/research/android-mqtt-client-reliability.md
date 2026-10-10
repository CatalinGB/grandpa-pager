# Android MQTT Pager — Client Library & Reliability Research

**Date:** Oct 2026
**Scope:** Always-on MQTT receiver on an elderly user's phone (Xiaomi Mi A1, Android 9 / API 28, plugged in, home Wi-Fi) that shows full-screen, auto-expiring messages (default 4 h, application-level `expires_at`). Sender (family/admin) publishes retained messages and monitors the receiver's status topic. Must survive reboots, Wi-Fi drops, Doze, app restarts.

All claims below are traced to primary sources (library repos/READMEs, Android developer docs, MQTT spec explainers from the broker vendor). Links are inline. Uncertainties are collected at the end.

---

## Recommendation (short)

- **Library:** `com.github.hannesa2:paho.mqtt.android` (the maintained Kotlin fork of Eclipse Paho Android). It is the only Android-native MQTT library with an actual 2026 release cadence, TLS, automatic reconnect and Android power integration, and it is a drop-in Kotlin library you can host inside your own foreground service.
- **Reliability architecture:** a **foreground service (type `dataSync`)** that owns the MQTT client + a **full-screen kiosk activity**; **persistent MQTT session** (`cleanSession=false`, stable clientId) with **QoS 1**, a **retained last-known-good message** topic, **LWT + retained status heartbeat** for offline detection, `BOOT_COMPLETED` restart, and a **persisted wall-clock `expires_at`** recomputed against `System.currentTimeMillis()` with a `setExactAndAllowWhileIdle` alarm backstop.
- **Existing app:** none is a maintained, purpose-built MQTT pager. WallPanel is archived (2025) and is a web-dashboard shell; mqtt-home is unmaintained (2022). **Build custom**, borrow patterns from WallPanel/mqtt-home.

---

## 1. MQTT client library choice

### Eclipse Paho Android Service — `eclipse-paho/paho.mqtt.android`
- Official Eclipse project, ~3.1k stars, Java. Features listed: MQTT 3.1 & 3.1.1, automatic reconnect, offline buffering, LWT, WebSocket, SSL/TLS, standard TCP, message persistence. (README: <https://github.com/eclipse-paho/paho.mqtt.android>)
- The README still advertises release **1.1.1** and "you will need the SDK for 24" — i.e. the Android service module is effectively frozen; the repo now sits under the `eclipse-paho` org and development has moved/stopped. Treat as legacy.
- It historically used an Android `Service` plus `AlarmManager` (the reason the fork was created).

### `hannesa2/paho.mqtt.android` — the maintained Kotlin fork
- ~624 stars, **Apache-2.0**, Kotlin. README states **"Project status: maintenance mode — Issues are ignored, but pull requests are not."** (<https://github.com/hannesa2/paho.mqtt.android>)
- **Active releases in 2026:** latest tag **4.5.1 (released 05 Oct 2026)**; version history shows regular 4.x tags through 2024–2026 and a steady dependabot/PR cadence. (<https://github.com/hannesa2/paho.mqtt.android/releases>)
- **v4.x design:** "doesn't need a foreground service" and "doesn't use `android.permission.SCHEDULE_EXACT_ALARM`"; it uses **`androidx.work:work-runtime-ktx` to receive messages during device sleep**. v3.x used an Android O foreground service (deprecated). (README)
- Gradle coordinate is via JitPack: `implementation "com.github.hannesa2:paho.mqtt.android:$latestVersion"`.
- Build targets are current: minSdk 23, code bumped to **API 36**, Kotlin 2.x, Gradle 9.x. TLS is inherited from the Paho client and there is a `test/tls-testing` module.
- 4.6-beta introduces its own fork of the underlying Java client ("Using most recent MQTT lib with an own fork"), so the client core is also under the maintainer's control.

### HiveMQ MQTT Client — `hivemq/hivemq-mqtt-client`
- ~1.1k stars, **Apache-2.0**, Java 8+. MQTT **5.0 and 3.1.1**, Netty-based. (<https://github.com/hivemq/hivemq-mqtt-client>)
- Feature-rich and explicitly production-grade: all MQTT 3.1.1/5 features, **TLS up to 1.3 incl. mutual auth, SNI, session resumption**, WebSocket, SOCKS/HTTP proxy, three API flavours (reactive Rx/Reactor, async futures/callbacks, blocking), **automatic reconnect + message redelivery, automatic resubscribe if the session expired**, backpressure, lifecycle listeners (connected / disconnected or connection failed). Latest release **1.4.0** (`com.hivemq:hivemq-mqtt-client`).
- **Caveat:** it is a pure JVM library. It has no Android service/lifecycle/power integration; you must run it inside your own service and manage Wi-Fi/wake/process lifetime yourself. It is also heavier (Netty). It is not marketed as an Android library (no Android-specific docs), so Android compatibility is "works on the JVM/Android" rather than vendor-supported.

### Others
- The native Android client is ultimately Paho's Java client (`eclipse-paho/paho.mqtt.java`, ~2.3k stars, latest **1.2.5**, MQTT v3 + v5 clients), which both Paho Android variants build on.
- There is no other broadly used, maintained Android-native MQTT library with these guarantees.

**Recommendation:** `hannesa2/paho.mqtt.android` (4.5.x) for an Android-native, Kotlin, service-integrated client. Use **HiveMQ** instead only if you specifically need MQTT 5 features / reactive backpressure and are willing to write and harden your own foreground service around a JVM library.

---

## 2. Keeping an MQTT connection alive on Android

### Foreground services & types
- Android 10 introduced `android:foregroundServiceType`; **apps targeting Android 14 (API 34) must declare a type** and the matching `FOREGROUND_SERVICE_*` permission, or `startForeground()` throws `MissingForegroundServiceTypeException`. (<https://developer.android.com/about/versions/14/changes/fgs-types-required>)
- Relevant types for this app: **`dataSync`** ("transfer data between a device and the cloud over a network", no runtime prerequisites), `connectedDevice` (needs a network/wifi permission such as `CHANGE_WIFI_STATE`), or **`specialUse`** (free-form justification; reviewed by Play). `remoteMessaging` is about "transfer text messages from one device to another" and `shortService` is capped at ~3 min, so neither suits an always-on pager. (same doc)
- On the actual target (Android 9 / API 28) the type attribute is not enforced (no `foregroundServiceType` requirement existed yet), but declare `dataSync` + `FOREGROUND_SERVICE_DATA_SYNC` anyway so the app is future-proof and clean on later OSes.
- An app with a **foreground service is not considered idle** by App Standby, and plugging into power "releases apps from the standby state." (<https://developer.android.com/training/monitoring-device-state/doze-standby>) This is exactly the pager's situation: always charging ⇒ normally **not in Doze and not in App Standby**.

### Doze / App Standby effects (and why the pager has an easy time)
- Doze is entered when the device is **unplugged, stationary and screen-off**. In Doze the system **suspends network access, ignores wake locks, defers standard alarms** (`setExact`/`setWindow`) and **does not run jobs/`WorkManager`** (`WorkManager` uses `JobScheduler`). (`doze-standby` doc)
- Escape hatches from the docs:
  - `AlarmManager.setAndAllowWhileIdle()` / `setExactAndAllowWhileIdle()` fire in Doze, but **not more than once per 9 minutes per app**.
  - A **partial battery-optimization exemption** lets the app "use the network and hold partial wake locks during Doze and App Standby"; check with `PowerManager.isIgnoringBatteryOptimizations()`; request via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play policy restricts this to cases where Doze breaks the app's core function — not a concern for a sideloaded app). (`doze-standby` doc)
- Being **plugged in** is the dominant mitigation: Doze and App Standby both relax when charging. So the design should still work unplugged, but the always-charging setup means the hard Doze cases are the exception, not the norm.

### Concrete pattern
1. **Foreground service** (`android:foregroundServiceType="dataSync"`, `START_STICKY`) that owns the MQTT client and posts a low-importance persistent notification ("Pager connected"). It keeps the app out of App Standby and out of the process-kill first tier. Re-call `startForeground()` on config/net changes.
2. **Battery-optimization exemption** requested at first run (and re-checked), so if someone unplugs the phone the socket and a partial wake lock are still allowed during Doze.
3. **WakeLocks:** hold a **`WifiLock`** (`WIFI_MODE_FULL_HIGH_PERF`, plus a multicast lock if you ever use mDNS) to keep the Wi-Fi radio awake with the screen off; acquire a **short partial `WakeLock`** around "message received → render full-screen + vibrate/sound", then release. Avoid a permanent partial wake lock — it is unnecessary while the FGS is alive and is a battery/Play-quality smell.
4. **Alarms vs WorkManager vs bound service:**
   - `WorkManager` is **deferred in Doze** and has a 15-minute periodic floor — use it only as a coarse watchdog/reconnect tick or for retrofit (that is how hannesa2 4.x does sleep-time polling). Not for the expiry moment.
   - **`setExactAndAllowWhileIdle()`** for the message expiry backstop and a periodic re-arm (respect the 9-minute floor). On Android 9 no `SCHEDULE_EXACT_ALARM` permission is needed; if you ever target API 31+ you must request it.
   - A **bound service** alone does not keep the process alive; bind + FGS together is the robust combo (UI binds to the same service instance that owns the client).

---

## 3. Reliable reconnect

- **Keepalive:** negotiated in CONNECT (seconds). The client must send a PINGREQ if it has sent nothing for the keepalive interval; the broker disconnects after **1.5 ×** keepalive without traffic, then publishes the LWT. Max keepalive is 18 h 12 m 15 s; `0` disables it. Too short drains battery, too long delays offline detection. For a plugged-in pager, **30–60 s** is a good balance; 120 s if you want to be gentle. (<https://www.hivemq.com/blog/mqtt-essentials-part-10-alive-client-take-over/>)
- **Clean vs persistent session:** `cleanSession=false` (a.k.a. `cleanStart=false` in MQTT 5) makes the broker store the client's subscriptions and queue QoS 1/2 messages while it is offline, keyed by clientId; CONNACK's *session present* flag tells the client whether state was restored. Use a **stable clientId** so the session is preserved across restarts. (<https://www.hivemq.com/blog/mqtt-essentials-part-7-persistent-session-queuing-messages/>)
  - Note **client takeover**: a second connection with the same clientId disconnects the first. Keep clientId unique to this physical device and never run two instances.
  - Reconciling with retained: a **retained command message** already guarantees the receiver shows the latest command even after a long offline gap; a persistent session additionally replays *queued* QoS 1 commands. Use both (persistent session + retained last-known-good), but understand retained is the stronger "last state" guarantee for a single-message pager.
- **QoS:** publish/display messages at **QoS 1** ("at least once"). QoS 2 is unnecessary for a display that is idempotent; QoS 0 risks silent loss. Deduplicate on `message_id` (QoS 1 may redeliver).
- **`onConnectionLost` / connection-lost callbacks:** both Paho Android and HiveMQ expose disconnect/failure listeners. On loss: mark UI "reconnecting", schedule reconnect with **exponential backoff + jitter** (e.g. 1s → 2s → 4s … cap 30–60s), and reset backoff on success. Rely on the library's built-in auto-reconnect for the common case; your backoff handles when it gives up.
- **Retained messages:** the broker stores **one** retained message per topic and delivers it immediately on subscribe; publish a zero-byte retained message to delete. This is the mechanism for "last-known-good" state. (<https://www.hivemq.com/blog/mqtt-essentials-part-8-retained-messages/>)
- **LWT:** set in CONNECT (topic + payload + QoS + retain flag). The broker publishes it on I/O error, keepalive timeout, client close without DISCONNECT, or protocol error; a graceful DISCONNECT discards it. Combining **LWT + retained status** is the documented pattern for online/offline state. (<https://www.hivemq.com/blog/mqtt-essentials-part-9-last-will-and-testament/>)

### Recommended topic layout (example)
| Topic | Retained | QoS | Purpose |
|---|---|---|---|
| `pager/<id>/message` | yes | 1 | The command/message. Payload = `{text, message_id, expires_at, sender, priority}`. Retained ⇒ reconnect instantly re-shows last message. |
| `pager/<id>/status` | yes | 1 | Receiver state. On connect publish `online`; **LWT** retained = `offline`. Sender subscribes here. |
| `pager/<id>/heartbeat` | yes | 0 | Optional periodic timestamp (e.g. every 60–120 s) so the sender can detect "stale" even if LWT is unreliable. |
| `pager/<id>/ack` | no | 1 | Receiver confirms display/dismiss for audit. |

---

## 4. Surviving reboot

- Declare `RECEIVE_BOOT_COMPLETED` and a receiver for `ACTION_BOOT_COMPLETED` (also handle `ACTION_MY_PACKAGE_REPLACED` for app updates, and consider `ACTION_LOCKED_BOOT_COMPLETED` for direct-boot if you ever need pre-unlock). From that receiver, start the foreground service.
- **Background FGS-start restriction:** apps targeting Android 12+ cannot start an FGS from the background **except** in listed exemptions, and receiving `ACTION_BOOT_COMPLETED` / `ACTION_LOCKED_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED` in a receiver **is an exemption**. (<https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>) So boot → start FGS is allowed.
  - Caveat for the future: **Android 15+ restricts launching certain FGS types from a `BOOT_COMPLETED` receiver** (the doc explicitly flags this). This does not affect the Android 9 target, and `dataSync` is the kind of type to double-check if the app ever runs on API 35+. A `specialUse` or `connectedDevice` type may be needed there. Verify at implementation time if you ever raise targetSdk/OS.
- On boot: re-create the client with the **same clientId and persistent session**, re-subscribe (or rely on session), and **publish `online` retained** on the status topic (also re-register the LWT, since a reconnect that does not set a new will clears the previous one).
- **Re-apply state from disk, not memory:** persist the last message payload + `expires_at` (DataStore/Room). After reboot, read it, and only display if `expires_at > now`. Do not rely on the broker's retained delivery arriving before you render — render from local persistence immediately, then reconcile when the retained message arrives (it should be identical).
- Kiosk option: make the app the **Home/launcher** and keep a full-screen activity with `FLAG_KEEP_SCREEN_ON` (or a wake lock) so it is always visible and quickly restored. WallPanel used exactly this "set as Home screen" trick.

---

## 5. Application-level expiry

- **Persist an absolute wall-clock `expires_at` (epoch millis)** derived from the sender's message, not a relative duration. This is what lets expiry survive reboot and process death, and keeps sender and receiver agreeing.
- **Recompute remaining time on every entry point:** on message receive, on service start, on boot, on `ACTION_TIME_CHANGED`/`ACTION_TIMEZONE_CHANGED`. `remaining = expires_at - now`.
- **Timer strategy (layered):**
  1. While the process is alive (FGS), an in-process coroutine/`Handler` scheduled for `remaining` ms clears the display.
  2. Backstop for process death / reboot: schedule `AlarmManager.setExactAndAllowWhileIdle(expires_at)` so expiry fires even in Doze (once per 9 min floor applies; acceptable for a 4 h message). Re-arm on boot and on each new message.
  3. A periodic `WorkManager` tick is acceptable **only** as a coarse re-evaluation (15-min floor, deferred in Doze) — not as the primary expiry mechanism.
- **Time-source concerns:**
  - `System.currentTimeMillis()` is wall-clock and can jump (user sets time, NTP correction, timezone). `SystemClock.elapsedRealtime()` is monotonic but **resets on reboot** and carries no absolute reference, so it cannot represent a cross-reboot `expires_at` by itself.
  - Recommended: store **wall-clock `expires_at`**, and additionally keep an in-process monotonic anchor (`elapsedRealtime` + `currentTimeMillis` pair) for drift detection while running. Listen for `ACTION_TIME_CHANGED` and recompute. Tolerate a small window of incorrectness right after boot before NTP sync; if the clock is wrong at boot you may over- or under-display briefly — a heartbeat/retained re-delivery corrects it once connected.
- Display a countdown / progress if useful, but always derive it from `expires_at`, never from a decrementing counter alone.

---

## 6. Sender side

- **Publish:** publish the command to `pager/<id>/message` with **retain=true, QoS 1**, payload containing `expires_at` (absolute), `message_id`, `sender`, and optionally a priority (e.g. override "do not disturb"). Retained means the receiver shows it immediately on (re)connect. Updating a message = publish a new retained message on the same topic (broker keeps one). To clear remotely, publish a zero-byte retained payload.
- **Monitor status:** subscribe to `pager/<id>/status` (QoS 1) and, ideally, `pager/<id>/heartbeat`.
- **Detect offline:**
  - The **retained status** gives the current state immediately on subscribe (`online` vs `offline` LWT). 
  - **LWT** flips it to `offline` on ungraceful disconnect; detection latency ≈ 1.5 × keepalive, so keep the receiver's keepalive short-ish (30–60 s) if you want fast offline alerts.
  - **Heartbeat staleness** is the belt-and-braces check: if no heartbeat within `N × interval`, mark offline in the UI even if LWT never arrived (LWT depends on the broker noticing a timeout).
- **Sender-side local notification on offline:** to detect offline while the sender app is backgrounded, the sender also needs a live connection — run a small **foreground service (or at least a bound service + FGS)** holding the status subscription; on transition to `offline`, post a **notification** via `NotificationManager` (create a dedicated high-importance channel; request `POST_NOTIFICATIONS` on API 33+). A plain backgrounded UI cannot be trusted to stay subscribed, so the FGS is what makes the offline alert reliable. (FCM would be an alternative but is unnecessary here and adds a backend.)
- Debounce transitions (require the status to be offline for one heartbeat interval) to avoid alert spam on brief Wi-Fi blips.

---

## 7. Existing open-source apps worth reusing

| App | License | Last update | Fit for this project |
|---|---|---|---|
| **TheTimeWalker/wallpanel-android** | Apache-2.0 | **Archived 2025-05-05** | Web-dashboard/kiosk shell. Has MQTT remote control (url, brightness, wake), MQTT→TTS speak, sensor reporting, set-as-Home. Not a message-display pager; archived/read-only. Useful as a *reference* for MQTT-command + kiosk patterns. (<https://github.com/TheTimeWalker/wallpanel-android>) |
| **and3rson/mqtt-home** | **GPL-3.0** | ~Oct 2022 (unmaintained) | Generic MQTT dashboard widgets (text/switch/graph…), TLS with CA & client certs, config stored in MQTT. Not full-screen/auto-expiring; stale. GPL may be a licensing concern if you copy code. (<https://github.com/and3rson/mqtt-home>) |
| ray0711/homeDash | (small, Java) | 2019 | Web dashboard + MQTT; abandoned. |
| Vetru "MQTT Dashboard" | (Play Store app) | docs repo 2021 | General MQTT dashboard, not a pager. |
| Home Assistant Android companion | Apache-2.0 | active | Active and excellent, but purpose-built for Home Assistant (not a generic MQTT pager). |

**Conclusion:** there is **no maintained, purpose-built full-screen MQTT pager** to adopt wholesale. Build the receiver (and the sender) custom. Reuse is limited to patterns: WallPanel's kiosk/Home + MQTT-command service, and mqtt-home's TLS-with-client-cert config approach. Note the archive/unmaintained/GPL status of the candidates before borrowing code.

---

## Proposed receiver state machine (summary)

```
BOOT_COMPLETED / app start
   └─ start ForegroundService(dataSync)          [FGS keeps app out of App Standby]
        └─ build MQTT client (stable id, cleanSession=false, TLS, keepalive 30–60s)
             ├─ LWT: retained "offline" → pager/<id>/status
             ├─ CONNECT → publish retained "online" → status
             ├─ SUBSCRIBE pager/<id>/message (QoS1)   [retained ⇒ last msg replays]
             └─ on message:
                  persist {payload, expires_at} to disk
                  render full-screen activity (wake/vibrate/sound)
                  schedule expiry (in-proc timer + setExactAndAllowWhileIdle backstop)
        └─ on change: publish heartbeat (optional)
        └─ on connectionLost: UI "reconnecting" + exponential backoff w/ jitter
        └─ on boot / process restart: reload persisted msg, recompute remaining, re-arm alarm
```

---

## Uncertainties / open questions

1. **Fork sustainability.** `hannesa2/paho.mqtt.android` is a one-maintainer "maintenance mode" fork (issues ignored, PRs only) and 4.6-beta moves to yet another self-maintained client fork. The official Eclipse Paho Android module looks frozen at 1.1.1. Long-term this is the biggest dependency risk; pin a version and be ready to vendor it.
2. **HiveMQ on Android.** HiveMQ's docs state Java 8+ and JVM frameworks but do not claim first-class Android support; its Netty core and JVM threading may need ProGuard/R8 rules and testing on API 28. Verify before committing if you want MQTT 5 / reactive APIs.
3. **Vendor/OEM behaviour.** Xiaomi devices (even Android One on the Mi A1) are widely reported to be aggressive about background/task killing and to have an "Autostart"/battery manager. This is vendor behaviour, not AOSP-documented, so plan to instruct the family to whitelist the app (Autostart on, battery optimization off). Not captured by the AOSP Doze docs.
4. **Android 15+ boot restrictions.** The FGS-from-`BOOT_COMPLETED` exemptions for certain types (flagged in the Android docs) do not affect the Android 9 target but must be revisited if the OS or `targetSdk` is ever raised.
5. **Broker semantics.** Persistent-session expiry, queue limits, retained-message size/limits and whether retained/session survive broker restarts are **broker-specific**; pick and configure the broker deliberately (this research did not fix a broker).
6. **Clock correctness after boot.** Wall-clock `expires_at` is the right cross-reboot representation, but a wrong device clock before NTP sync can mis-time a display; the heartbeat/retained re-delivery corrects it only once connected.
7. **Exact-alarm floors.** `setAndAllowWhileIdle`/`setExactAndAllowWhileIdle` cannot fire more than once per 9 minutes per app — fine for 4 h messages, a constraint for short expiry windows.
