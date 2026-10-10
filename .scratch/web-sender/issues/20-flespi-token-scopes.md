# 20 — Flespi token scopes (publish-only can't receive) + no Last Will

Type: task
Status: open
Blocked by:

## Question

The web sender shows "connected" and the receiver connects, yet no message is
delivered. Why?

## Answer

Two independent causes were found and confirmed against Flespi:

1. **Broker mismatch (fixed).** The web app was on **Flespi**; the phone was on
   the **local** test broker (`ssl://127.0.0.1:8883`). Moved the phone to Flespi
   (`mqtt.flespi.io:8883`, TLS).
2. **The `.env` FLESPI_TOKEN is publish-only.** Publishing returns a QoS1 puback,
   but a subscriber (even `#`, even same token) receives **nothing** — the ACL
   does not grant subscribe/delivery. So the receiver can never get a message
   with this token.
3. **Flespi rejects a CONNECT that carries a Last Will** for a scoped token
   ("Not authorized (5)"), for *any* will topic/QoS. **Fixed** by removing the
   LWT from the receiver (`MqttController`); offline detection now relies on
   status staleness (~90 s).

## What the owner must do (accounts only they can touch)

Create two scoped tokens in Flespi (`docs/flespi-setup.md`):

- **Receiver** — subscribe `grandpa/message` + `grandpa/command`, publish
  `grandpa/status`. Put it on the phone.
- **Sender** — publish `grandpa/message`, subscribe `grandpa/status` (so the web
  status panel works). The current `.env` token can publish, so it can already
  send; it just can't read status.

Then the phone (with the receiver token) will receive messages and both sides
work on Flespi.
