# 09 — Local TLS broker for e2e

Type: task
Status: resolved
Blocked by:

## Question

How do we e2e-test offline, without paid cloud credentials and without exposing
anything publicly?

## Answer

A private **Aedes** broker over TLS on `127.0.0.1:8883` with username/password
auth (`tools/broker/server.js`), mirroring the production broker contract. Certs
generated with OpenSSL (`tools/certs/`); the CA is bundled into the app
(`res/raw/ca.crt`) and trusted alongside the platform anchors. The phone reaches
it over USB via `adb reverse tcp:8883 tcp:8883`. A Node client
(`tools/scripts/mqtt.js`) sends/clears/reads status.
