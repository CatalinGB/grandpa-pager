# 06 — Broker for the web sender

Type: grilling
Status: resolved
Blocked by: 01, 02

## Question

Pick the broker the web sender uses: stay on **EMQX Cloud Serverless** (if it
offers WSS) or switch to **Flespi** (`wss://mqtt.flespi.io:443`). Weigh browser
WSS support, free-tier longevity, ACL granularity, EU data residency, and the
cost of migrating the already-deployed Android receiver.

## Answer

**Flespi** — the owner chose "move everything to Flespi" (one broker for the
receiver + all senders). Research confirmed it covers every need
(ticket 09); EMQX Serverless also supports WSS, but we standardise on Flespi.
Endpoints: `mqtts://mqtt.flespi.io:8883` (Android), `wss://mqtt.flespi.io:443`
(browser). Hardening: `docs/flespi-setup.md`.
