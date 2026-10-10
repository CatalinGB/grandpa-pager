# 20 — Flespi token scopes (publish-only can't receive) + no Last Will

Type: task
Status: resolved
Blocked by:

## Question

The web sender shows "connected" and the receiver connects, yet no message is
delivered. Why?

## Answer

**Root cause: the `.env` token's ACL was the untouched Flespi template.**
`GET /auth/info` returned an ACL (type 2) granting only:

```json
{ "uri":"mqtt", "topic":"replace/this/topic/with/your/own/#", "actions":["publish","subscribe"] }
{ "uri":"mqtt", "topic":"you/can/add/multiple/topics/#",     "actions":["subscribe"] }
```

Those literals match nothing in `grandpa/*`, so the broker accepted the CONNECT
and pub-acked publishes but **delivered nothing**. (Also: Flespi rejects any
CONNECT carrying a **Last Will** for a scoped token → the receiver sets no LWT;
offline detection uses status staleness.)

**Resolved** by creating two scoped ACL tokens (with the account's Master token):

- `grandpa-phone-receiver` — subscribe `grandpa/message` + `grandpa/command`,
  publish `grandpa/status`; `origins: []`.
- `grandpa-web-sender` — publish `grandpa/message`, subscribe `grandpa/status`;
  `origins: ["https://catalingb.github.io"]`.

Both `ttl: 31536000, expire: 0` (1 y, rolling). The secret is the token object's
**`key`** field; the display name field is **`info`**. Saved locally to
`.env.tokens` (gitignored). Verified end-to-end on Flespi: the sender token's
publish reached the phone (receiver token) and the sender received the phone's
status.

### Confirmed ACL schema
```json
{ "uri": "mqtt", "topic": "grandpa/message", "actions": ["publish"] }
```

