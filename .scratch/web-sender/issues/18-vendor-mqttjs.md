# 18 — Vendor MQTT.js + harden the page

Type: task
Status: resolved
Blocked by: 03

## Question

Remove the CDN dependency (a common cause of "page does nothing") and make the
static page fail loudly if its library is missing.

## Answer

MQTT.js **vendored** into the repo (`mqtt.min.js`, v5.16.0) and referenced as
`./mqtt.min.js`; `sender.js` shows a clear error if `mqtt` is undefined. Verified
with the page against the local WSS broker (`SELFTEST_OK`).
