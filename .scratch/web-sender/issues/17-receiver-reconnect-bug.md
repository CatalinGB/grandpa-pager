# 17 — Receiver does not auto-recover after a long outage (bug)

Type: task
Status: open
Blocked by:

## Question

During e2e, after the local broker was down for a stretch, the Android receiver
did **not** reconnect on its own (`automaticReconnect` had stopped retrying); it
only recovered after the app was restarted. On a real Flespi outage Grandpa's
screen could stay stale. Make reconnection self-healing (periodic reconnect
attempt independent of Paho's backoff, e.g. an alarm/heartbeat watchdog).
