# 04 — Message & diagnostics schema

Type: grilling
Status: resolved
Blocked by:

## Question

What is the wire schema, including the owner's new requirement that the
**sender** appears on the display phone?

## Answer

JSON via Gson. Full definitions in `docs/architecture.md`.

Message (`grandpa/message`, retained, QoS 1):
```json
{ "id": "<uuid>", "sender": "Maria", "text": "Lunch is ready",
  "created_at": "ISO-8601", "expires_at": "ISO-8601",
  "duration_minutes": 240 }
```
`sender` is **mandatory** and rendered as a prominent "From …" line.

Command (`grandpa/command`, QoS 1): `{"action":"clear"}`.

Diagnostics (`grandpa/status`, retained, QoS 1; also the Last Will with
`state:"offline"`):
```json
{ "state": "showing|idle|offline", "sender": "Maria", "battery": 63,
  "charging": true, "wifi": true, "mqtt": true,
  "current_message_id": "<uuid|->", "expires_at": "ISO-8601|",
  "last_seen": "ISO-8601", "app": "receiver" }
```

Expiry is application-level: the receiver recomputes remaining time from
`expires_at` on every tick and after reboot; it never relies on broker TTL.
