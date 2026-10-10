# EMQX Cloud Serverless — MQTT over WebSocket (WSS) for browser clients

Research date: 2026-10-10. Primary sources: EMQX Cloud docs (`docs.emqx.com/en/cloud/latest/…`)
and EMQX broker docs (`docs.emqx.com/en/emqx/latest/…`). Facts below are quoted/derived from
those pages unless marked **INFERRED** or **UNVERIFIED**.

## Answer up front

**YES — EMQX Cloud Serverless supports MQTT over WebSocket, and because Serverless is
TLS-only it is specifically MQTT over *secure* WebSocket (WSS).** The plans comparison
table lists **"MQTT over WebSocket ✓"** for Serverless, and the Serverless overview says
**ports `8883` (mqtts) and `8084` (wss) are enabled by default**. The web sender can stay
on the existing broker; no switch to Flespi is required.

**URL format:**

```
wss://<deployment-address-from-console>:8084/mqtt
```

- `<deployment-address-from-console>` = the **Address** on the deployment *Overview* page.
- port **8084** = WSS (fixed for Serverless).
- path **/mqtt** = EMQX's default WebSocket MQTT path (`websocket.mqtt_path`, default `/mqtt`).

> Caveat on the host suffix: EMQX Cloud docs do **not** publish the exact DNS suffix of the
> Serverless Address (the example in the docs uses the public broker `broker.emqx.io`).
> Prior repo research saw `*.emqxsl.com` in the wild but never confirmed it from a primary
> source — **UNVERIFIED**; treat the console Address as the source of truth.

## Facts table

| Item | Serverless | Source |
|---|---|---|
| MQTT over WebSocket supported | **Yes ✓** | Plans comparison, `price/plans.html` |
| MQTT over WSS (TLS WebSocket) | **Yes — port 8084, enabled by default** | `create/serverless.html`; `deployments/port_guide_serverless.html` |
| Plaintext WebSocket (ws:8083) | **No** — "Connections to ports 1883 and 8083 are not supported" | `deployments/port_guide_serverless.html` |
| Plaintext MQTT (tcp:1883) | **No** — "MQTT over TCP ✗" | `price/plans.html`; port guide |
| Secure MQTT (mqtts:8883) | Yes, enabled by default | `create/serverless.html` |
| WebSocket path | **`/mqtt`** (`websocket.mqtt_path` default) | EMQX `configuration/listener.html`; Cloud `js_sdk`/`nodejs_sdk` examples use `wss://broker.emqx.io:8084/mqtt` |
| Host | Per-deployment **Address** from console; CNAME to your own domain **not supported** on Serverless | `create/serverless.html`; port guide |
| Auth method | **Username/password** (Default Authentication, built-in DB) | port guide; plans |
| Anonymous access | **Not supported** | port guide ("does not support anonymous authentication") |
| Token/JWT auth | **No** — "Authentication with Extended Data Sources ✗" on Serverless (JWT/HTTP/Redis/MySQL/PG are extended auth) | `price/plans.html` |
| MQTT versions | **v3.1, v3.1.1, v5.0** | `price/plans.html`; EMQX MQTT-over-WS doc |
| QoS | 0/1/2 | `price/plans.html` |
| Max message size | 1 MB | `price/plans.html` |
| TLS | One-way TLS; server CA `https://assets.emqx.com/data/emqxsl-ca.crt`; SNI required | port guide |
| Origin / CORS | Not a documented/configurable setting on Serverless. WebSocket is not subject to CORS preflight; EMQX's WS origin check (`check_origin_enable`) defaults to **disabled**, so any browser origin can connect. Serverless-specific behavior **INFERRED** from EMQX defaults. | EMQX broker config; no Serverless doc found |
| Max concurrent sessions | **1000** | `price/plans.html`; `create/restriction.html` |
| Max deployment TPS | 1000 | `price/plans.html` |
| Max single-client publish TPS | 10 | `price/plans.html` |
| Max subscriptions / client | 10 | `price/plans.html` |
| Free quota | **1,000,000 session-minutes/month**, **1 GB traffic/month**, **1,000,000 rule actions/month** | `price/plans.html` |
| Idle shutdown | No active client connections for **30 consecutive days → deployment stopped**; if not reactivated within 30 more days → **deleted** | `create/serverless.html` |
| Uptime SLA | None for Serverless | `price/plans.html` |

## Free-tier fit (always-on receiver + occasional browser sender)

- Billing unit: "1 session minute represents 1 client connection … for a duration of 1 minute."
- Our load (from `mqtt-broker-options.md`): ~2–4 clients 24/7 ≈ **~173k session-minutes/month**
  ≈ **~17% of the 1M free quota**. A browser sender connected only while sending adds
  negligible session-minutes. Traffic (a few text msgs/day) is far under 1 GB.
- A 24/7 receiver keeps at least one active connection, so the 30-day idle stop/deletion
  never triggers — which is exactly what Serverless needs.
- Set the monthly **Spend Limit = 0** to guarantee only the free quota is consumed (no card
  required); default is already 0.

## Tier caveat

- **Serverless: WSS only** — port **8084** with TLS, path **`/mqtt`**. Plaintext `ws://:8083`
  and `mqtt://:1883` are refused. No custom domain / CNAME.
- **Dedicated Flex / BYOC** additionally expose plaintext **8083 (ws)** and **1883 (mqtt)**,
  support CNAME custom domains, and allow you to enable 8883/8084 via TLS config. If you ever
  *needed* non-TLS or a custom domain, that is the tier — but for a browser over GitHub Pages
  you only need WSS, which Serverless already provides.

## Browser client notes (cross-check)

- MQTT.js in the browser connects with `mqtt.connect('wss://<address>:8084/mqtt', {clientId, username, password})`.
  EMQX Cloud ships JS/Node.js SDK guides doing exactly this (against `broker.emqx.io`, same `/mqtt` path).
- mTLS / two-way TLS is **not supported in-browser** (MQTT.js limitation, noted in EMQX Cloud JS SDK page) —
  irrelevant here since Serverless is one-way TLS with username/password.

## Sources

1. Serverless Connection Guide — `https://docs.emqx.com/en/cloud/latest/deployments/port_guide_serverless.html`
2. Create a Serverless Deployment (ports 8883/8084, idle stop/delete) — `https://docs.emqx.com/en/cloud/latest/create/serverless.html`
3. Plans comparison (protocols/quotas/features tables) — `https://docs.emqx.com/en/cloud/latest/price/plans.html`
4. Pricing & free quota — `https://docs.emqx.com/en/cloud/latest/price/pricing.html`
5. Quotas and Limits — `https://docs.emqx.com/en/cloud/latest/create/restriction.html`
6. Dedicated Flex & BYOC Connection Guide (ws:8083 vs wss:8084 by tier) — `https://docs.emqx.com/en/cloud/latest/deployments/port_guide_dedicated.html`
7. MQTTX connection guide (Serverless = TLS-only) — `https://docs.emqx.com/en/cloud/latest/connect_to_deployments/mqttx.html`
8. JavaScript / Node.js SDK guides (`wss://…:8084/mqtt`) — `.../connect_to_deployments/js_sdk.html`, `.../nodejs_sdk.html`
9. EMQX Listener Configuration (`websocket.mqtt_path` default `/mqtt`; wss 8084) — `https://docs.emqx.com/en/emqx/latest/configuration/listener.html`
10. EMQX MQTT over WebSocket (v3.1/3.1.1/5.0, QoS 0/1/2, auth) — `https://docs.emqx.com/en/emqx/latest/develop/connect-emqx/mqtt-over-websocket.html`
