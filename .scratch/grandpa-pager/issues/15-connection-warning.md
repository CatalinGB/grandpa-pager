# 15 — Connection problem shown in place

Type: task
Status: resolved
Blocked by: 13

## Question

How do we tell the user there's no connection, without alarming wording and
without hiding a live message?

## Answer

When the MQTT link is down **and the screen is idle**, the same clock screen
shows an amber line **"Probleme cu conexiunea"** (localized). A message that is
currently showing is **never** replaced — the warning only appears on idle.

Implemented via `Link.connected` (set on `onConnected`/`onConnectionLost`) read
by `ReceiverActivity`; `PagerBus` triggers an immediate refresh. Verified by
killing and restarting the broker (evidence
`docs/evidence/09-idle-connection-warning.png`).
