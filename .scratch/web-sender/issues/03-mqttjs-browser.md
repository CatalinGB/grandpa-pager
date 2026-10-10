# 03 — MQTT.js in the browser

Type: research
Status: resolved
Blocked by:

## Question

What is the correct, current way to load MQTT.js in a plain static HTML page
(pinned CDN URL / ESM import; is `unpkg.com/mqtt/dist/mqtt.min.js` still valid in
v5?), connect over `wss://`, authenticate with a token, publish retained QoS1
messages, and subscribe for diagnostics — including any CORS/Origin constraints
brokers impose on browser WebSocket clients.

## Answer

Research: `.scratch/web-sender/research/mqttjs-browser.md`.

- Pin the version; the unpinned URL tracks `latest`. Working:
  `<script src="https://unpkg.com/mqtt@5.16.0/dist/mqtt.min.js"></script>`
  (jsDelivr mirror identical; ESM alt `.../mqtt@5.16.0/dist/mqtt.esm.js`).
- The API the proposal uses is **unchanged in v5** (`connect`, `publish(topic,
  payload, {qos:1, retain:true})`, empty-payload retained clear, `subscribe`,
  `username`/`password`/`clientId`/`reconnectPeriod`/`clean`, `will`). The one v5
  break (`new` for `MqttClient`) doesn't affect `connect()`.
- Must use **`wss://`** (GitHub Pages is HTTPS; `ws://` is blocked as mixed
  content).

