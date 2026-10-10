# 02 — Broker selection

Type: research
Status: resolved
Blocked by:

## Question

Which free, private (never public), TLS MQTT broker do we use for the family
channel?

## Answer

Research: `.scratch/grandpa-pager/research/mqtt-broker-options.md`.

**Primary: EMQX Cloud Serverless** — free-forever, spend limit 0 (cannot bill),
TLS-only `mqtts://<id>.emqxcloud.com:8883`, username/password, per-topic ACLs,
retained + LWT, MQTT 3.1.1/5. Caveats: US data residency; idle deployments stop
after 30 days (irrelevant — the receiver is always on).

**Runner-up (EU): flespi** — `mqtt.flespi.io:8883`, token-as-username, EU
(Lithuania); free tier labelled dev/test, deletes inactive accounts after 60 days.

Rejected: public/test brokers (privacy); HiveMQ free Serverless (retiring
2026-12-31); self-hosted Mosquitto as production (owner wanted no server to
maintain) — but Mosquitto-style **Aedes** is used for local e2e.

Broker stays swappable: host/port/user/pass are runtime config in the app.
