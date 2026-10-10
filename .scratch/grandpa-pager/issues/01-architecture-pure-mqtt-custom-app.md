# 01 — Architecture: pure MQTT + custom Android app

Type: grilling
Status: resolved
Blocked by:

## Question

Which of the five architectures in `initial-prompt.md` do we build, given the
owner's constraints (MQTT-only, no Home Assistant, "a sender type and a receiver
app, maybe one app with a config switch")?

## Answer

**Pure MQTT with a custom Android app.** One Kotlin codebase, one APK, a role
switch: `receiver`, `sender_admin`, `sender_family`.

Rejected: Home Assistant-centred (owner: no HA); self-hosted web dashboard +
kiosk browser (heavier to run, weaker lockdown than a device owner app); the
prompt's commercial-kiosk comparison (device owner + our own app gives equivalent
control, free); hybrid (unnecessary once everything is one app).

Trade-off: we own MQTT lifecycle and Android release engineering, but we gain
total control over display, expiry, sender attribution and lockdown.
