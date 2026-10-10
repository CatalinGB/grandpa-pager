# 17 — Receiver does not auto-recover after a long outage (bug)

Type: task
Status: resolved
Blocked by:

## Question

During e2e, after the local broker was down for a stretch, the Android receiver
did **not** reconnect on its own (`automaticReconnect` had stopped retrying); it
only recovered after the app was restarted. On a real Flespi outage Grandpa's
screen could stay stale. Make reconnection self-healing (periodic reconnect
attempt independent of Paho's backoff, e.g. an alarm/heartbeat watchdog).

## Answer

`PagerService` now runs a 30 s **watchdog** for both roles. On each tick it
calls `ensureConnected()`, which — instead of only creating a client when it is
`null` — tears down and rebuilds a client that is present but not connected
(closing the old socket first, so the stale auto-reconnecting client cannot
share the clientId and thrash). The receiver also keeps publishing diagnostics
from the watchdog, so senders can see it recover. Verified: the release/debug
builds compile and the JVM unit tests pass.

