# Flespi MQTT-over-WSS — connection facts

Research for the static browser sender (`grandpa-pager` web-sender): a GitHub Pages app
connecting directly to Flespi over secure WebSocket with MQTT.js.
All claims traced to Flespi-owned primary sources (docs, KB, pricing, forum changelog, privacy policy).
Date of research: Oct 2026.

## Facts table

| # | Question | Finding | Source |
|---|----------|---------|--------|
| 1 | Secure WebSocket endpoint | `wss://mqtt.flespi.io:443` — **confirmed**. Host is `mqtt.flespi.io`; WSS uses port **443 (SSL)** (WS non-SSL: 80). Native TLS ("MQTT over TCP") uses **8883 (SSL)** / 1883. | MQTT broker page; MQTT API page; "How to consume…" KB |
| 1a | Is a path like `/mqtt` required? | **No.** Docs specify only `mqtt.flespi.io:443` with no path. Empirically, the server answers a WebSocket upgrade with `HTTP/1.1 101 Switching Protocols` on `/`, `/mqtt`, `/mqtt/`, and `/ws` alike, so any path (MQTT.js default `/`) is accepted. | MQTT broker page; live curl handshake (Oct 2026) |
| 2 | Auth presentation | Flespi **token** is the MQTT **Username**; **Password left blank**. Alternatively the token may be placed in the **Password** field. **ClientID** must be unique per connection (duplicates disconnected round-robin). | MQTT broker page; "How to consume…" KB; tokens KB |
| 2a | Token format / field limits | 64-byte token. For clients limited to 16/32 bytes: username = **token id**, password = first ≥16 bytes of token key; token **must be ACL type**. | MQTT broker page footnote; forum changelog |
| 2b | Works from a browser? | **Yes** — MQTT over Secure WebSocket is an advertised feature, subject to the Origin check (see #7). | MQTT broker page; tokens KB |
| 3 | Publish-only / subscribe-only token scoping | **Yes.** ACL tokens expose an **`mqtt`** module with topic rules having separate **publish** and **subscribe** sections; "what is not allowed is forbidden". Different tokens can be scoped too (publish-only on `pager/…`, subscribe-only on `status/…`). ACLs are configured in the flespi panel **Tokens** screen or via the tokens REST API (`acl` = array of `{uri, methods, ids}`). | ACL blog; tokens KB; forum changelog (topic selectors) |
| 3a | ACL caveat | ACLs for MQTT **cannot** be configured for topics starting with **`flespi/`**. For `flespi/...` topics you instead grant REST ACLs on the underlying `gw/...` element and must **not** create an `mqtt` ACL. → A private status topic must NOT start with `flespi/`. | tokens KB; ACL blog |
| 3b | Other token guards | **Allowed origins** (browser allow-list) and **IP whitelist** are per-token. | tokens KB |
| 4 | Free tier | €0/mo, expressly **"For testing & development purposes. Note that limits are not extendable."** Not positioned for production. SLA: best-effort / no refund. | pricing page; "How pricing works" KB |
| 4a | Free tier limits | 10 devices, 2 channels, 1 stream, 1 plugin, 50 calc devices, 10 assets/geofences/SMS, 100 tokens, 2 webhooks, 1 group, 2 realms. No message-count plan limit for MQTT beyond rate limits. | pricing page; "How pricing works" KB |
| 4b | Free MQTT broker limits | 50 active MQTT sessions; 200 subscriptions/session; **20,000 MQTT messages/min**; 100 MB persistent session storage; **100 MB retained-message storage**. Exceeding 20k/min → tokens suspended 1 min. | "How pricing works" KB |
| 4c | Free platform limits | REST/MQTT traffic 50 MB/min; 200 REST API requests/min; logs 30 days or 3 GB. | "How pricing works" KB |
| 4d | Inactive-account deletion | **Confirmed 60 days.** Free plan restriction: "Account inactive — 60 days — account is deleted." (Commercial: ∞.) | "How pricing works" KB (restrictions table) |
| 5 | Retained messages | **Supported**, storage-limited (Free 100 MB; new retained messages not stored once full). SUBACK is sent after the retained snapshot; wildcard (`#`/`+`) empty-payload publish bulk-deletes matching retained messages; `modified_since` filters retained on subscribe. | MQTT broker page |
| 5b | Last Will & Testament | **Supported.** Broker has "Will delay interval" (MQTT 5 compliance: done) and a changelog entry about validating will-message topics against ACL. | MQTT broker page (MQTT 5 checklist); forum changelog |
| 5c | QoS | **QoS 0 and QoS 1 supported.** Publishing QoS 2 is allowed, but new subscriptions are downgraded to **max QoS 1** (Maximum QoS advertised in CONNACK; existing QoS 2 subs keep working). Treat effective ceiling as QoS 1. | MQTT broker page; forum changelog |
| 5d | MQTT versions | **3.1, 3.1.1, and 5.0.** Broker is MQTT 5.0-compliant (shared subs, message expiry, response topic, topic alias, etc.). | MQTT broker page; MQTT API page |
| 6 | Data residency / privacy | Single region **EU**, two datacenters in the **Netherlands** (hosting sub-processor Zylon), operated by **Gurtam UAB, Lithuania**, under **GDPR**. Inbound/outbound platform IPs `185.213.2.0/24`. Privacy policy / DPA: https://flespi.com/privacy-policy (last updated 2025-05-19); public contract: https://flespi.com/contract. | Regions KB; privacy policy |
| 7 | Browser-specific constraints | **Server-side Origin check on the WSS handshake.** Tokens have an "Allowed origins" list; **default is any origin allowed**, but it can be restricted to specific origins (wildcards) or set to an **empty array to block all browser use**. A disallowed origin is rejected with **CONNACK 0x87 not authorized** (reason 5 for MQTT 3.x). No CORS preflight applies to a WebSocket upgrade — enforcement is the token Origin allow-list. The GitHub Pages origin (`https://<user>.github.io`) must be allowed. | tokens KB (Allowed origins) |

## Practical implications for the proposal

- Use `wss://mqtt.flespi.io:443` (no `/mqtt` path needed; MQTT.js path `/` is fine). Do **not** use `:8883` from a browser — that is raw MQTT/TLS and browsers cannot speak it from JS.
- `mqtt.connect('wss://mqtt.flespi.io:443', { username: TOKEN, password: '', clientId: <unique> })`.
- Two scoped ACL tokens: publisher token (publish on the pager topic), subscriber token (subscribe on the status topic). Topic must not start with `flespi/`.
- The token is embedded in a public static app and therefore effectively public — this is the central security constraint (see risks).

## Risks

1. **Token exposure (security).** A GitHub Pages app is static and public; the token ships to every visitor and cannot be kept secret. An ACL-scoped, short-TTL token plus a strict origin allow-list and IP allow-list mitigates but does not eliminate abuse of the granted scope.
2. **Free tier is not production-grade (availability).** Flespi documents the Free plan as **testing & development only**, with non-extendable limits, best-effort/no-refund SLA, and **account deletion after 60 days of inactivity**. A pager that is idle for 60 days could lose its account (and all data/retained messages).
3. **QoS ceiling is 1** and Free broker caps (50 sessions, 20k msg/min, 100 MB retained) are modest.
4. **ACL constraint:** the status topic must not begin with `flespi/` if it is to be scoped via an MQTT ACL.

## Sources

- Flespi MQTT broker (config: host, ports, auth, QoS, MQTT versions, retained, limits, MQTT 5 checklist) — https://flespi.com/mqtt-broker
- Flespi MQTT API (connection details, topics, session persistence) — https://flespi.com/mqtt-api
- KB: How to consume messages from flespi via MQTT (WSS URI `mqtt.flespi.io:443`, token-as-username, blank password) — https://flespi.com/kb/how-to-consume-telematics-hub-messages-via-mqtt
- KB: Token — access key (token types, ACL, **allowed origins** incl. WSS + CONNACK 0x87, IP whitelist, MQTT ACL cannot target `flespi/`) — https://flespi.com/kb/tokens-access-keys-to-flespi-platform
- Blog: Take control of token access permissions with flexible ACLs (mqtt module, publish/subscribe sections, ACE structure) — https://flespi.com/blog/take-control-of-token-access-permissions-with-flexible-acls
- KB: How flespi pricing works (Free restrictions incl. **60-day inactive deletion**, MQTT broker & platform limits) — https://flespi.com/kb/how-flespi-pricing-works
- Pricing page (Free = "testing & development purposes", non-extendable limits, SLA best-effort) — https://flespi.com/pricing
- KB: Regions — geographically distributed flespi datacenters (EU region, Netherlands DCs, Gurtam UAB Lithuania, GDPR) — https://flespi.com/kb/regions-geographically-distributed-flespi-datacenters
- Privacy policy / DPA (Gurtam UAB Lithuania; sub-processors incl. Zylon, Netherlands) — https://flespi.com/privacy-policy
- Forum: [Changelog] flespi MQTT broker (QoS 2→1 sub downgrade & Maximum QoS, short-field auth via token id, will-message ACL security fix, topic selectors) — https://forum.flespi.com/d/19-changelog-flespi-mqtt-broker
- Empirical: live WebSocket handshake `HTTP/1.1 101 Switching Protocols` on `mqtt.flespi.io:443` for `/`, `/mqtt`, `/mqtt/`, `/ws` (Oct 2026).
