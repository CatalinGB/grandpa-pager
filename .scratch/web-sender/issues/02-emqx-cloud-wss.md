# 02 — EMQX Cloud WebSocket support

Type: research
Status: resolved
Blocked by:

## Question

Does EMQX Cloud Serverless (our existing broker decision) support MQTT over
secure WebSocket for a browser client, and on which host/port/path? If yes, the
web sender can reuse the current broker instead of switching to Flespi.

## Answer

Research: `.scratch/web-sender/research/emqx-cloud-wss.md`.

**Yes.** EMQX Cloud Serverless enables MQTT over WebSocket by default, and being
TLS-only that means **WSS** on port **8084**, path `/mqtt`:

```
wss://<deployment-address>:8084/mqtt
```

Auth is username/password (built-in DB); Serverless is WSS-only (no plaintext
`ws://`, no custom CNAME). **No broker switch required** — the Android receiver's
existing EMQX deployment can serve the browser sender too.

