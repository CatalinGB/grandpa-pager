# Flespi coverage — can the whole grandpa-pager run on Flespi?

Research for running the **entire** grandpa-pager on one Flespi broker: an always-on
Android receiver (persistent session, retained message, Last Will, publishes
diagnostics) plus several senders (Android app + static GitHub Pages web app) that
publish retained QoS1 messages and subscribe to a status topic, authenticated by a
token minted to never expire and stored in browser `localStorage`.
Every claim is traced to a **Flespi-owned primary source** (docs, KB, official API
spec, forum answers by Flespi staff). Date of research: **Oct 2026**.

## Verdict

**Flespi covers every functional requirement** (one broker, persistent sessions with
offline QoS1 queueing, retained messages, LWT, wildcards, QoS 0/1, MQTT 3.1.1/5,
per-token ACLs, browser WSS). **One requirement is impossible as literally stated:**
a token with *no expiry / infinite TTL* cannot be created — Flespi tokens **must**
expire. The practical workaround (max 1-year TTL that auto-refreshes on activity,
kept alive by the always-on receiver) makes a token *effectively* non-expiring.
The free tier is also officially labelled **"testing & development purposes"**,
which is a terms-of-use caveat for real family use. These two are the only unmet
needs.

## Requirements → Yes/No

| Requirement | Met? | Basis |
|---|---|---|
| One broker serves all clients (receiver + senders) | **Yes** | Single public broker, isolated per-account namespace — MQTT broker page |
| Always-on receiver over persistent session (`cleanSession=false`) | **Yes** | "Non-clean session is **persistent**" — MQTT API page |
| Queued QoS1 messages delivered to an offline client on reconnect | **Yes** | Keeping undelivered messages/subscriptions "until the new connection is established (**up to 10 days**)" — MQTT API page |
| Retained message (set) | **Yes** | Retained supported, storage-limited — MQTT broker page |
| Clear retained message (empty payload) | **Yes** | Empty-payload publish to a wildcard mask bulk-deletes matching retained messages — MQTT broker page |
| Last Will & Testament (+ will delay) | **Yes** | LWT supported; MQTT5 "Will delay interval" = done — MQTT broker page |
| Receiver publishes diagnostics | **Yes** | Publish to any non-`flespi/` topic — MQTT broker page |
| Senders publish retained QoS1 | **Yes** | QoS 1 supported; retain supported — MQTT broker page |
| Senders subscribe to a status topic | **Yes** | Subscribe ACL action — API spec `token.ace.mqtt` |
| Wildcard subscriptions (`grandpa/#`) | **Yes** | Standard MQTT wildcards; `#`/`+` supported (also used for retained deletion) — MQTT broker page |
| QoS 0 / 1 / 2 | **Partial** | QoS 0 & 1 supported; QoS 2 **published** is accepted but new subscriptions are downgraded to **max QoS 1** (effective ceiling = 1) — MQTT broker page + forum changelog |
| MQTT 3.1.1 and 5.0 | **Yes** | 3.1, 3.1.1, 5.0; MQTT 5.0-compliant — MQTT broker page |
| Unique clientId per concurrent connection | **Yes (required)** | "ClientID should be unique for each connection"; duplicates disconnected **round-robin** — MQTT broker page |
| Several tokens on one account, each with own ACL | **Yes** | Free = **100 tokens**; ACL per token — pricing KB + ACL blog |
| Revoke one token without affecting others | **Yes** | Block/disable or delete a single token; "does not affect other entities" — tokens KB |
| Many clients share one token | **Yes** | No per-token connection cap documented; only account-wide session limits apply — pricing KB |
| Browser WSS client | **Yes** | WSS on `mqtt.flespi.io:443`; origin allow-list (default **any**) — tokens KB |
| Token creatable with **no expiry / infinite TTL** | **No** | "You cannot create a token without time limitations. The token **MUST expire**" — tokens KB |
| Free tier allowed for real family use | **Partial** | Free = "**For testing & development purposes**"; best-effort SLA — pricing page |
| Always-connected client prevents 60-day inactivity deletion | **Yes** | "Activity includes: **MQTT connections**, REST requests, and active device connection" — forum (Flespi staff) |

## Token TTL / "no expiry" — the definitive answer

**Short version: a truly non-expiring token is not possible on Flespi.** But a
1-year rolling TTL, refreshed by the always-on receiver, is non-expiring *in
practice*. This is the single most important design constraint.

1. **No infinite TTL.** The KB is explicit: *"You cannot create a token without
   time limitations. The token **MUST expire** at some point in time."* (tokens KB)
2. **Default TTL:** Flespi publishes **no numeric default**. Token creation
   *requires* you to supply either `expire` or `ttl` — the official create schema
   is `anyOf` over those two fields and errors with *"'expire' or 'ttl' properties
   are required"* (official API spec, `token.post`). The panel offers templates
   instead of a silent default.
3. **Maximum TTL = 31,536,000 s (365 days).** The API spec caps `token.ttl` at
   31536000 (unit labelled "min."). (official API spec, `token.ttl`)
4. **`ttl` is rolling; `expire` is a hard cap.**
   - `expire` — a fixed UNIX timestamp; if non-zero the token dies at that instant
     regardless of activity (`token.expire`).
   - `ttl` — *"If not zero, specifies interval that is used to automatically update
     token expiration time with value of `current-time + ttl`, whenever you are
     doing any REST request or **have active MQTT session** with this token."*
     (official API spec, `token.ttl`; echoed by tokens KB and forum reply 1333:
     *"if you configure ttl, then with each request, the lifetime of your token
     will be extended by ttl seconds"*.)
5. **How to set / "unset":** set `ttl = 31536000` and **leave `expire` at 0 / unset**
   (never pin a fixed date). "Unsetting" expiry entirely is impossible; the rolling
   TTL is the closest. `ttl = 0` means no rolling refresh (then a fixed `expire`
   governs), which is the opposite of what we want.
6. **Practical non-expiry recipe:** the always-on Android receiver holds a live
   MQTT session 24/7, so any token it uses is refreshed on every activity and never
   lapses. **If the receiver and the browser sender share one token, that token is
   kept alive by the receiver indefinitely.** If instead you mint separate scoped
   tokens, the browser sender's token is refreshed only when a sender actually
   connects/publishes — after one year of total silence it would lapse.
7. **Caveat for `localStorage`:** the token stored in the browser is effectively
   public (the GitHub Pages app is static). It expires after at most 1 year of no
   use and cannot be renewed without re-minting; design for re-provisioning.
   (tokens KB + existing `flespi-wss.md` risk section)

## Answers by question

### 1. Tokens
- **No expiry:** impossible (see above). Default TTL: not published; `expire` **or**
  `ttl` required. Max TTL 1 year.
- **Shared vs one-per-client:** many clients may authenticate with the same token;
  there is no documented one-token-per-client rule and no per-token connection cap
  (only the account-wide "Active MQTT sessions" limit applies). Flespi *recommends*
  ACL tokens "by providing to the application only the access level it may need",
  which favours per-role tokens on security grounds, not a technical restriction.
- **Several tokens, independent ACLs / revocation:** yes. ACL tokens expose an
  **`mqtt`** ACE with a `topic` mask and separate `actions: ["publish"]` /
  `["subscribe"]` (official API spec, `token.ace.mqtt`; ACL blog). Blocking or
  deleting one token "does not affect the operation of other entities" (tokens KB).
  The `mqtt` topic mask **may not start with `flespi`** (API-spec regex
  `^(?!flespi$)(?!flespi\/).*$`).

### 2. ClientID rules
ClientID **must be unique per concurrent connection**; duplicates are disconnected
"in round robin fashion" (MQTT broker page). Consequence for us: the receiver must
use one stable unique id, and each concurrent sender/tab must use its own; two tabs
sharing a clientId will fight over the session. The broker also signs each published
message with a `token_id` user property so the receiver can tell senders apart
(MQTT broker page).

### 3. Sessions
- Persistent (`cleanSession=false`) **supported**: "Non-clean session is
  **persistent** — it keeps undelivered messages and subscriptions until the new
  connection to the broker is established (**up to 10 days**)" (MQTT API page).
- Offline queueing of QoS1 for a non-clean session is therefore **yes, bounded to
  ~10 days**. Storage limits: Free **100 MB persistent MQTT session storage**, and
  if exceeded "session is deleted" (pricing KB). A stuck session whose outgoing
  window is full is disconnected after `freeze_timeout` (default **600 s**,
  overridable 10–3600 via a CONNECT user property) so its queue can be redelivered
  on reconnect (MQTT broker page, specifics).
- **MQTT5 session-expiry interval:** not listed on the broker's MQTT 5.0 compliance
  checklist (it lists message-expiry, receive-maximum, etc. instead), so treat the
  documented **10-day** persistent-session retention as the effective session
  lifetime rather than relying on a client-set session-expiry.

### 4. Retained / LWT / wildcards / QoS / versions
All supported. Notable specifics: empty-payload publish to a wildcard mask
bulk-deletes retained messages; SUBACK is sent after the retained snapshot; single
retained message max **16 MB**; wildcard `#`/`+` normal MQTT. LWT supported with
MQTT5 will-delay. QoS effective ceiling is **1**. Versions 3.1 / 3.1.1 / 5.0.
(MQTT broker page + forum changelog)

### 5. Max payload / rate limits
Broker minimums: **max incoming payload 60 MB**; outgoing queue **256 MB**; QoS1
unacked queue **100 MB**; **single retained message 16 MB**; **24 topic levels**;
**64 topics per SUB/UNSUB**. Free-plan rate limits: **20,000 MQTT msg/min**
(exceed → tokens suspended 1 min), **50 active MQTT sessions**, **200
subscriptions/session**, **100 MB persistent session storage**, **100 MB retained
storage**, **50 MB/min REST+MQTT traffic**, **200 REST req/min**, logs 30 days or
3 GB. (MQTT broker page + pricing KB)

### 6. Free tier
- Limits: **10 devices, 2 channels, 1 stream, 1 plugin, 100 tokens, 2 webhooks,
  1 group, 2 realms, 10 assets/geofences/SMS**; storage not extendable (pricing
  page / KB). We consume no channels/devices for a pure MQTT setup, so the numeric
  limits are ample.
- Free is explicitly **"For testing & development purposes. Note that limits are
  not extendable"** with a **best-effort / no-refund** SLA (pricing page). This is
  the terms-of-use caveat for genuine family (non-test) use.
- **60-day inactivity deletion:** confirmed — "Free accounts are automatically
  removed after **two months of inactivity**" (account KB; restrictions table says
  "Account inactive — 60 days — account is deleted").
- **Does an always-connected client reset it? Yes.** Flespi staff: *"Activity
  includes: **MQTT connections**, REST requests, and active device connection to the
  channel."* (forum "Deleted Account?", staff reply). Another staff reply: *"API
  calls, MQTT sessions … almost any kind of normal activity is counted as alive
  account. You may just construct the system and from that point of time never
  login, it will continue to operate."* (forum "Questions regarding the
  limits/restrictions"). So a permanently-connected receiver keeps the account
  alive with no panel logins.

### 7. Browser WSS gotchas
- Endpoint: **`wss://mqtt.flespi.io:443`** (WSS = port 443 SSL); token as MQTT
  **Username**, blank password. `:8883` is raw MQTT/TLS, unusable from browser JS.
  (MQTT broker page)
- **Origin allow-list:** every token has an "Allowed origins" list; **default is
  any origin allowed (`"*"`)**. MQTT-over-WebSocket connections are subject to this
  check; a disallowed origin is rejected with **CONNACK 0x87 not authorized**
  (reason 5 for MQTT 3.x). Setting origins to an **empty array blocks all browser
  use** (backend-only token). Wildcards and a `{"preset":"flespi"}` item are
  supported (tokens KB; official API spec `token.origins*`).
- **IP whitelist** is also per-token (mismatch → 401 / CONNACK 0x87) — but IP
  whitelisting is a poor fit for public GitHub Pages senders with changing client
  IPs. (tokens KB)
- Since the GitHub Pages app ships a public token, the real mitigations are the ACL
  scope (publish-only on the pager topic), a strict origin allow-list
  (`https://<user>.github.io`) and either a short-lived token or accepting that a
  1-year rolling token is reachable by anyone who views the page.

## Design implications (Flespi-specific)

- Mint **at least two ACL tokens**: a receiver token (subscribe `grandpa/message`,
  publish `grandpa/status`, plus any diagnostics topic) and a sender token
  (publish-only `grandpa/message`). Optionally a third status/subscribe-only token.
  Topic must **not** start with `flespi/`.
- To satisfy "token never expires": set `ttl = 31536000`, **do not set `expire`**,
  and keep the always-on receiver connected. If you want the browser token to also
  never lapse, either share the receiver's refreshed token with the web app or
  arrange a periodic sender ping.
- Receiver: `cleanSession=false` with a unique fixed clientId; expect queued
  messages to survive an outage of **up to ~10 days** and ≤100 MB.
- Use MQTT 5 if you want will-delay/message-expiry, but do not depend on
  client-set session-expiry.

## Sources

- Flespi MQTT broker (config, auth, clientId uniqueness, QoS ceiling, retained, LWT,
  wildcard deletion, MQTT5 checklist, payload/queue limits, `freeze_timeout`) —
  https://flespi.com/mqtt-broker
- Flespi MQTT API (host/ports, token-as-username, **persistent session up to 10
  days**) — https://flespi.com/mqtt-api
- KB: Token — access key to the flespi platform (**must expire**, TTL/expire
  semantics, allowed origins + CONNACK 0x87, IP whitelist, blocking, MQTT ACL cannot
  target `flespi/`) — https://flespi.com/kb/tokens-access-keys-to-flespi-platform
- KB: How flespi pricing works (Free restrictions table incl. **MQTT broker limits**,
  **account inactive 60 days → deleted**, SLA) —
  https://flespi.com/kb/how-flespi-pricing-works
- KB: Account — your space inside flespi ("Free accounts are automatically removed
  after two months of inactivity") — https://flespi.com/kb/account-getting-inside-flespi
- Pricing page (Free = "**For testing & development purposes**", non-extendable
  limits, best-effort SLA) — https://flespi.com/pricing
- **Official Flespi Platform API spec** (primary): `token.ttl` (max 31536000,
  rolling refresh "whenever … any REST request or have active MQTT session"),
  `token.expire`, `token.post` anyOf expire/ttl, `token.origins`/`origins.post`
  (default `"*"`), `token.ace.mqtt` (topic/publish/subscribe) —
  https://flespi.io/platform/api.json (discovered via https://flespi.io/docs/)
- Forum [staff] "Deleted Account?" — "Activity includes: **MQTT connections**, REST
  requests, and active device connection" — https://forum.flespi.com/d/2011-deleted-account
- Forum [staff] "Questions regarding the limits/restrictions" — MQTT sessions count
  as activity; "you may … never login, it will continue to operate" —
  https://forum.flespi.com/d/107-questions-regarding-the-limits-restrictions
- Forum [staff] "Tokens via API REST" — TTL extends token lifetime on each request —
  https://forum.flespi.com/d/1333-tokens-via-api-rest
- Forum: [Changelog] flespi MQTT broker (QoS2 → max QoS1 on new subscriptions,
  short-field auth, will-topic ACL fix, topic selectors) —
  https://forum.flespi.com/d/19-changelog-flespi-mqtt-broker
- Blog: Take control of token access permissions with flexible ACLs (mqtt module,
  publish/subscribe sections, ACE structure) —
  https://flespi.com/blog/take-control-of-token-access-permissions-with-flexible-acls
- Companion (secondary, corroborating the rolling-TTL recipe): CloudThinker Flespi
  integration docs — https://docs.cloudthinker.io/guide/connections/flespi
