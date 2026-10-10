# 03 — MQTT client library

Type: research
Status: resolved
Blocked by:

## Question

Which Android MQTT client library for reliable always-on use on Android 9?

## Answer

Research: `.scratch/grandpa-pager/research/android-mqtt-client-reliability.md`.

Chosen: **`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`** (upstream
core, Maven Central). The research recommended the `hannesa2/paho.mqtt.android`
fork (actively maintained, Android `Service` integration), but it is distributed
via **JitPack** and is a single-maintainer fork — a reproducibility risk for an
autonomous build. The core client gives TLS, retained, LWT, persistent session
and `automaticReconnect`; we own the lifecycle in a foreground `Service`.

Reliability architecture: foreground `dataSync` service owning the client,
`cleanSession=false` + stable clientId + QoS 1, retained last-message topic,
retained status + LWT + 30 s heartbeat, `BOOT_COMPLETED` restart, persisted
`expires_at` recomputed against `System.currentTimeMillis()` with
`setExactAndAllowWhileIdle` as backstop.
