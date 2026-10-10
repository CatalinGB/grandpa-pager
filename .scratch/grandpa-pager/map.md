# Grandpa Pager — Wayfinder Map

> Canonical artifact for this effort. Open tickets are child issues under
> `issues/`, found by query; they are not listed here. Decisions live in their
> ticket; this map only gists and links them.

## Destination

A working, **locked-down, remotely-managed full-screen message pager, built and
deployed on the real Xiaomi Mi A1 (Android 9 / API 28)**: the admin and family
members publish full-screen, sender-attributed, auto-expiring messages over a
free private MQTT broker; the phone displays them, Grandpa cannot escape the
app, and the phone publishes diagnostics back to the senders.

(The destination is *deployed reality*, not a plan — see Notes. This effort
carries execution.)

## Notes

- **Effort type: EXECUTE.** Overrides wayfinder's plan-only default at the
  owner's explicit request ("fully implement, test, and e2e test"). Tickets below
  therefore mix decisions with build/test tasks.
- **Domain**: Android/Kotlin app development, MQTT over TLS, ADB kiosk lockdown.
- **Skills each session should consult**: `android-adb-control` (driving the
  phone), `research`, `prototype`, `grilling`, `domain-modeling`.
- **Standing constraints** (from `initial-prompt.md`, confirmed by owner):
  free/low-cost, EU/Romania-friendly, **private** TLS broker (never a public
  broker), application-level `expires_at` (default 4 h, per-message override),
  retained messages, automatic reconnect + reboot recovery, no public-internet
  exposure of the compose surface, low maintenance.
- **Target device**: Xiaomi Mi A1 (`tissot`, Android One), Android 9 / API 28,
  single user, **no accounts** (device-owner provisioning viable), currently
  `192.168.50.15`, USB serial `329708ee0804`.
- **Local e2e strategy**: run a private TLS broker (Node **Aedes**) on the host;
  reach it from the phone over USB with `adb reverse tcp:8883 tcp:8883` (host and
  phone are on different subnets, so USB reverse is the reliable path).
- **Repo is not a git repo**; wayfinder uses the local-markdown tracker.

## Decisions so far

<!-- index: one line per closed ticket; zoom the link for detail -->

- [01 Architecture: pure MQTT + custom Android app](issues/01-architecture-pure-mqtt-custom-app.md): Single Kotlin codebase, one APK, role switch (receiver / admin-sender / family-sender); no Home Assistant, no web kiosk, no commercial kiosk.
- [02 Broker: EMQX Cloud Serverless](issues/02-broker-selection.md): Primary = EMQX Cloud Serverless (free-forever, spend-limit 0, TLS-only 8883, per-topic ACLs, retained+LWT); runner-up = flespi (EU). Local Aedes broker for e2e.
- [03 MQTT client library](issues/03-mqtt-client-library.md): Eclipse Paho `mqttv3` core from Maven Central wrapped in a foreground service (chosen over the hannesa2 Android fork for build reliability).
- [04 Message & diagnostics schema](issues/04-message-schema.md): JSON schema with mandatory `sender`; application-level `expires_at`; retained message topic; status topic + LWT for offline.
- [05 Lockdown: device owner + lock task](issues/05-lockdown-device-owner.md): Provision device owner via ADB, true kiosk Lock Task Mode, BOOT_COMPLETED autostart, stay-on + doze whitelist.
- [06 Build toolchain + app scaffold](issues/06-build-toolchain-and-scaffold.md): User-space JDK 21 + Gradle 8.14 + Android SDK 34; Kotlin 2.2.20, AGP 8.7.3; APK builds, 8 JVM tests pass.
- [07 Receiver app](issues/07-receiver-app.md): Full-screen display with sender + countdown, local expiry, diagnostics + LWT, reboot recovery — verified on device.
- [08 Sender app](issues/08-sender-app.md): Compose/publish retained + status monitor; admin vs family roles — verified on device.
- [09 Local TLS broker for e2e](issues/09-local-tls-broker.md): Private Aedes broker over TLS + bundled CA, reached via `adb reverse`.
- [10 E2E test suite on the device](issues/10-e2e-tests.md): All scenarios pass on the real Mi A1; evidence in `docs/evidence/`.
- [11 Lockdown provisioning verified](issues/11-lockdown-verified.md): Device owner + lock task confirmed; plus an admin `RELEASE_KIOSK` control to undo it.
- [12 Localization](issues/12-localization.md): Resource-based UI; en + ro + top-10 languages; localized date (`Saturday 21st of August 2026` / `17:30`); message text stays the sender's responsibility.
- [13 Receiver layout v2](issues/13-receiver-layout-v2.md): Variant C — name centred above the message (max auto-sized); idle = one-row `SÂMBĂTĂ 10/10/2026` + clock 1.3×.
- [14 New-message attention cue](issues/14-attention-cue.md): One-shot tri-modal burst — slow amber glow (~6 s) + chime + double vibration; never re-cue the same message.
- [15 Connection problem in place](issues/15-connection-warning.md): Amber `Probleme cu conexiunea` on the idle clock screen when the link is down; never hides a live message.

## Not yet specified

- **Administrative surface for the family**: how non-technical relatives get the
  sender app and their own credentials (`sender` name + per-user MQTT account)
  without exposing the broker. Needs design once the core works.
- **Remote update path after deployment** (phone lives at Grandpa's, no USB):
  ADB-over-Wi-Fi, or MQTT-driven config/OTA. Deferred; local maintenance assumed
  for now.
- **Broker account provisioning**: EMQX/flespi signup needs credentials the agent
  cannot create; production cut-over is owner-driven (see runbook).
- **Alerting**: whether the sender app should escalate offline alerts beyond an
  in-app status line + local notification (email/webhook).

## Out of scope

- **Home Assistant-centred approach** (owner chose MQTT-only, no HA).
- **Self-hosted web-dashboard + kiosk-browser approach** (owner chose custom app).
- **Commercial/MDM kiosk (Fully Kiosk Browser, etc.)** as the product (device
  owner + our own app gives us the same control, free).
- **Public/test MQTT brokers** (privacy).
