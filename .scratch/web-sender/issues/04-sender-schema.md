# 04 — Sender schema (add the sender field)

Type: grilling
Status: resolved
Blocked by:

## Question

Reconcile the web sender's payload with the existing wire schema. The pasted
proposal drops `sender`, but the owner requires the sender to appear on Grandpa's
phone, and it must match everything the Android sender already sends. Decide the
final web-sender payload and clear semantics.

## Interim shape (to confirm)

The web sender must publish the **same schema as the Android sender**, i.e.:

```json
{ "id": "<uuid>", "sender": "Maria", "text": "…",
  "created_at": "ISO-8601", "expires_at": "ISO-8601",
  "duration_minutes": 240 }
```

- `sender` **mandatory** (rendered as the name on the receiver).
- `duration_minutes` from the picker (ticket 10).
- Clear = publish an **empty retained** payload to `grandpa/message`.
- Subscribe to `grandpa/status` for the diagnostics panel.
- Topic + QoS 1 + retained identical to the Android sender.

## Answer

Confirmed and implemented in `sender.js` (see the whole repo). The web sender
publishes the exact Android schema including `sender`; duration comes from the
picker; clear = empty retained. Verified end-to-end (web → broker → phone).
