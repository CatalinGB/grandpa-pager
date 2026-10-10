# 23 — Web sender toggles connected/disconnected (clientId collision)

Type: task
Status: resolved
Blocked by:

## Question

The Pages sender's status pill kept flicking between "conectat" / "neconectat".

## Answer

**Root cause: a persisted `clientId`.** `sender.js` stored `gp_client` in
`localStorage` and reused it for every connection. Any two instances that share
`localStorage` — two tabs, or the installed PWA plus a tab — therefore CONNECT
with the *same* clientId. MQTT brokers drop the older session on a duplicate
clientId, and with `reconnectPeriod: 5000` the two evict each other forever.

Because the sender uses a **clean session** (`clean: true`), a stable clientId
buys nothing. Fixed by generating a **unique clientId per connection** (removed
`gp_client`). Reproduced against Flespi: two clients sharing an id produce a
continuous CONNECT/CLOSE loop; with unique ids both stay connected (no CLOSE
events over 20 s).
