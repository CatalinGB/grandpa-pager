# 09 — Does Flespi fully cover all needs?

Type: research
Status: resolved
Blocked by:

## Question

If we run the whole pager on Flespi (one broker for the always-on Android
receiver plus Android and web senders), does Flespi cover every requirement —
and can a token be made non-expiring?

## Answer

Research: `.scratch/web-sender/research/flespi-coverage.md`.

**Functionally, yes.** One broker for all clients; **persistent sessions**
(`cleanSession=false`) with offline **QoS1 queueing (~10 days)**; retained
messages (set + clear), **Last Will**, `grandpa/#` wildcards, QoS 0/1,
MQTT 3.1.1/5; **per-token ACLs with independent revocation**; EU; browser WSS.
No functional gap.

**Two caveats**
1. **A token cannot be literally infinite — "the token MUST expire" (Flespi KB);
   max TTL is 1 year.** But `ttl` is *rolling*: it resets on any MQTT session or
   REST call. Use **one token** with `ttl=1y`, shared by the receiver (always on)
   and the senders → effectively never lapses. Separate per-user tokens each die
   after a year of total silence.
2. **Free tier is labelled "testing & development purposes"** — a ToS caveat for
   real family use, not a technical limit.
