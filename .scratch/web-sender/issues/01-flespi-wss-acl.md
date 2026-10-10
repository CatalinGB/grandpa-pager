# 01 — Flespi WSS + ACL token model

Type: research
Status: resolved
Blocked by:

## Question

Confirm Flespi can serve the browser sender: the exact secure-WebSocket endpoint
and port, the authentication model (how a token is presented over MQTT), free-tier
limits, ACL/token permissions (publish-only, subscribe-only), retained-message
and Last-Will support, MQTT version, data residency, and shutdown/ToS risks.

## Answer

Research: `.scratch/web-sender/research/flespi-wss.md`.

- WSS URL: **`wss://mqtt.flespi.io:443`** (any path works; MQTT.js default `/`).
  `:8883` is raw MQTT/TLS, unusable from browser JS.
- Auth: the **token as MQTT `username`**, password blank. Works over browser WSS.
- ACL: tokens can be scoped **publish-only** vs **subscribe-only** (not on
  `flespi/` topics). Origin allow-list configurable (default: any).
- Retained + LWT + QoS supported; MQTT 3.1.1/5.
- **Free tier is "testing & development only"**; **inactive accounts deleted
  after 60 days**; EU (Lithuania).
- Biggest risk: a token baked into a public GitHub Pages app is effectively
  public — mitigate with a short-TTL, ACL-scoped token + origin/IP allow-list.

