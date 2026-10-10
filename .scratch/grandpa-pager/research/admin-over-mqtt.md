# Admin mode over Flespi MQTT

Research date: 2026-10-10. Sources are Flespi's own docs/KB, blog posts, the
live OpenAPI specs, and (for browser-token risk) OWASP/MDN. Each claim is
cited inline. Items marked **INFERENCE** follow from two or more primary facts
rather than one explicit statement.

Question: a static GitHub Pages app (the "sender") wants an **admin mode** that
gates features on possession of an **MQTT/Flespi token**. The whole system
(receiver app + web sender) are MQTT clients of `mqtt.flespi.io`.

## TL;DR — yes/no

| Question | Answer |
|---|---|
| Can `flespi/rest/{method}/{api-uri}` do **platform** ops (tokens, subaccounts) over MQTT? | **Yes, but only with a Master token.** ACL and Standard tokens cannot. |
| Same JSON payload/response format as HTTP REST? | **Yes** — it is a proxy to HTTP REST. Body = payload; response to same topic (or response topic). |
| Which QoS? | **0 and 1 only.** QoS 2 not supported by the broker. |
| Can a token pub/sub **custom topics** like `grandpa/config`? | **Yes.** Any topic not starting `flespi/`. |
| Can ACL scope **publish vs subscribe per topic**? | **Yes** — `mqtt` ACE with `topic` mask + `actions: ["publish"/"subscribe"]`. |
| Can an ACL/Standard token ever reach platform methods? | **No** — explicitly impossible. |
| Can a browser (WSS) drive admin ops? | **Technically yes** (WSS :443, token as username), but you shouldn't hand it a Master token. |
| Can Flespi restrict a token to specific **origins**? | **Yes** — `origins`, enforced on MQTT-over-WebSocket too (CONNACK `0x87`). |
| Can a token be restricted to **publish-only** on one topic? | **Yes** — `mqtt` ACE `{"topic":"grandpa/config","actions":["publish"]}`. |
| Should a browser admin hold a **Master** token? | **No** (recommendation). Use a narrowly-scoped ACL token, never Master. |
| Sane remote-config for our own Android app? | **Yes** — admin publishes config JSON to `grandpa/config`; app subscribes and applies. |

---

## 1. Flespi REST API over MQTT

**Topic & format.** Publish to `flespi/rest/{method}/{api-uri}`. `{method}`
must be lowercase and valid for `{api-uri}`. The optional "data" parameter is
passed as the **MQTT message payload** (JSON, same as an HTTP REST body).
Source: [flespi MQTT API](https://flespi.com/mqtt-api) §"REST API over MQTT";
[flespi API basics](https://flespi.com/kb/flespi-api-basics) §"MQTT over REST".

Example from the blog — create a channel:
1. subscribe `flespi/rest/post/gw/channels`
2. publish `[{"name":"first","protocol_name":"test"}]` to the same topic
3. response arrives on the subscription.

The blog states plainly it is **"a wrapper … behind the scenes your MQTT
request will be proxied to an HTTP request."** Source:
[flespi blog: MQTT scores again](https://flespi.com/blog/mqtt-scores-again-flespi-rest-api-over-mqtt).

**Response routing.**
- Default: response is published to the **same topic**.
- MQTT **v5.0** only: `"response topic"` property redirects the response.
- User property `"response_cid"` redirects the response to another subaccount
  (intended for a Master account calling and steering the result to a child).
- User property `"response_chunked": "true"` chunks large responses; the last
  chunk carries an `"http_code"` user property.
- The response body is the normal REST envelope:
  `{"result":[...],"errors":[...],"warnings":[...]}`.
Sources: [flespi MQTT API](https://flespi.com/mqtt-api);
[flespi API basics](https://flespi.com/kb/flespi-api-basics) §"Response format".

**QoS.** The broker supports **QoS 0 and QoS 1 only** (no QoS 2). Also max
payload 60 MB, max single retained message 16 MB. Source:
[flespi MQTT broker](https://flespi.com/mqtt-broker).

**Can it do platform ops (tokens / subaccounts)?**
**Yes — but only with a Master token.** The mapping is generic: "Use any
existing {method} to manipulate flespi instances in {api-uri}" ([MQTT
API](https://flespi.com/mqtt-api)). Authorization is the **connection's token**,
and the token-types doc is explicit ([tokens KB]
(https://flespi.com/kb/tokens-access-keys-to-flespi-platform)):
- **Master** — "the only type of token that provides access to any REST API call
  that starts from `/platform/`, e.g.: realms, grants, webhooks, billing,
  recycle bin, subaccounts, flespi chat …".
- **Standard** — Telematics (`gw/*`) entities only; "Does not have access to
  tokens, grants, realms, webhooks, subaccounts".
- **ACL** — "It is **not possible to issue ACL token that provides access to
  platform-level methods** - tokens, grants, realms, webhooks, subaccounts".

So `flespi/rest/post/platform/customer/tokens` (create a token) or
`flespi/rest/get/platform/customer/subaccounts/all` works **only** when the MQTT
connection authenticated with a **Master** token; an **ACL or Standard token
gets an access-denied error** (HTTP 403 / reason code 3). **INFERENCE**, but the
proxy behaviour above plus the token-type table make it direct: there is no
separate MQTT restriction documented for `/platform/` URIs, and the ACL `mqtt`
topic mask cannot even address `flespi/` (see below) — the REST ACL / access
type is what governs the call.

**ACL is NOT the mechanism for `flespi/rest/...`.** The `mqtt` ACL topic mask is
forbidden in the `flespi/` namespace (JSON-Schema pattern
`^(?!flespi$)(?!flespi\/).*$`, error "direct ACL to flespi/ namespace is
forbidden"), and the KB warns ACLs for MQTT "cannot be configured for topics
starting with `flespi/`". Therefore REST-over-MQTT calls are authorized by the
token's **REST ACL / access type**, not by any topic ACL. Sources:
[flespi.io/platform/api.json](https://flespi.io/platform/api.json) `token.ace.mqtt`;
[tokens KB](https://flespi.com/kb/tokens-access-keys-to-flespi-platform);
[ACL blog](https://flespi.com/blog/take-control-of-token-access-permissions-with-flexible-acls).

**Net:** over MQTT you can reach **any** REST method the token is allowed to
reach over HTTP — including platform ops **iff** the token is Master. You would
never want that Master token sitting in a browser.

---

## 2. Arbitrary custom topics + per-topic ACL

**Yes.** "You can use flespi MQTT broker separately from telematics by
publishing and subscribing any messages to it" ([MQTT API]
(https://flespi.com/mqtt-api)). The only restriction: topic names **must not
start with `flespi/`** — "Use any topic names except those starting with
`flespi/...`" ([MQTT broker](https://flespi.com/mqtt-broker)); "Any MQTT message
published by user to such a topic will be discarded" ([MQTT topics guide]
(https://flespi.com/kb/mqtt-topics)). Each account is in an **isolated topic
namespace** ([MQTT broker](https://flespi.com/mqtt-broker)). So
`grandpa/config`, `grandpa/messages`, `grandpa/diagnostics` are all fine.

**Per-topic permission scoping: yes.** The token ACL has an `mqtt` ACE whose
schema is exactly ([flespi.io/platform/api.json](https://flespi.io/platform/api.json)
`token.ace.mqtt`):

```json
{
  "uri": "mqtt",
  "topic": "grandpa/config",          // MQTT subscription mask; flespi/ forbidden
  "actions": ["publish"],             // enum: "publish" | "subscribe"
  "methods": ["GET","POST","DELETE"]  // HTTP methods (default get-post-delete)
}
```

You can add several `mqtt` ACEs to one token (up to 32 ACEs total) to grant
different pub/sub rights on different masks. This is the "Token for MQTT broker"
scenario in the [ACL blog]
(https://flespi.com/blog/take-control-of-token-access-permissions-with-flexible-acls):
"pick the **mqtt** module … customize the **topic**, **actions**, and
**methods** depending on the roles. You may need to create several tokens — one
with full privileges, one for consuming messages only, etc."

Note on `flespi/` topics: to read platform state topics
(`flespi/state/gw/...`) you do **not** create an `mqtt` ACE — you create a REST
ACL on the underlying element (`gw/devices`, `gw/channels`, …). Sources:
[ACL blog](https://flespi.com/blog/take-control-of-token-access-permissions-with-flexible-acls);
[tokens KB](https://flespi.com/kb/tokens-access-keys-to-flespi-platform).

---

## 3. Token-gating a browser app

### What "the token IS the login" guarantees
It is **possession-based authentication (bearer credential)**: whoever holds the
64-byte token string can open the connection and exercise every right the token
carries, with no separate username/password and no interactive challenge. The
token is "a 64-byte randomly generated key-string … used as an MQTT connection
**Username**" ([tokens KB]
(https://flespi.com/kb/tokens-access-keys-to-flespi-platform)). There is no
notion of a "user session" beyond the token. So admin mode gated on token
possession = "whoever can paste the token is admin". It cannot distinguish one
admin human from another except per-token (and you *can* issue one token per
person for revocation/audit — see the token `Logs` tab, same KB).

### Risk of a token in `localStorage`
- Any JavaScript on the **same origin** can read it; a single XSS (or a
  malicious dependency / compromised CDN script) exfiltrates the token and the
  attacker can act as admin until the token expires or is blocked. OWASP:
  "Avoid hosting multiple applications on the same origin, all of them would
  share the same `localStorage` object, use different subdomains instead" and
  "Use `sessionStorage` instead of `localStorage` if persistent storage is not
  needed" ([OWASP HTML5 Security Cheat Sheet]
  (https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html)).
- On a static GitHub Pages site there is no server to keep the secret; the token
  is shipped to / stored in the browser by definition.
- GitHub Pages is one shared origin patterns- and XSS-surface-wise; putting an
  admin secret in `localStorage` on a page whose whole build is third-party JS is
  the classic high-risk pattern.
- Recommendation: prefer `sessionStorage` (gone when the tab closes) over
  `localStorage`, keep the admin entry point out of the normal user bundle, and
  make the token short-TTL, revocable, IP/origin-locked, and minimally scoped.

### Which token type should an admin browser hold?
**Never Master.** Master is "the almighty token granting access to the flespi
platform API and allowing the creation of other tokens … we recommend **not to
use it directly** except rare cases when you really need unlimited access"
([tokens KB](https://flespi.com/kb/tokens-access-keys-to-flespi-platform);
[ACL blog](https://flespi.com/blog/take-control-of-token-access-permissions-with-flexible-acls)).
A browser token should be an **ACL** token scoped to the minimum: e.g.
`{"uri":"mqtt","topic":"grandpa/config","actions":["publish"]}` plus (optionally)
subscribe on a diagnostics topic. An ACL token **cannot** be escalated to
platform ops by design.

### Can Flespi restrict a token to specific origins and to publish-only?
**Origins: yes.** The token has an `origins` field that "relates only to the
request originated from the web browsers … restrict web access for a token to
the set of custom web origins … Wildcards are supported". Crucially:
"**MQTT over WebSocket connections are subject to the allowed origins check
too. A connection from a disallowed origin is rejected with CONNACK reason code
0x87 not authorized (5 for MQTT 3.x clients).**" You can also set origins to an
**empty array to block browser use entirely**. Example masks:
`https://*.subdomain.domain`. Sources:
[tokens KB](https://flespi.com/kb/tokens-access-keys-to-flespi-platform) §"Allowed origins";
[flespi.io/platform/api.json](https://flespi.io/platform/api.json) `token.origins`.

> Caveat: origin checking relies on the browser-supplied `Origin` header on the
> WebSocket upgrade. It stops a *disallowed web page* from using the token; it is
> not a defence against a stolen token replayed from a non-browser client
> (which sends no `Origin`). Pair it with the **IP whitelist** (`ips` field,
> same KB) where possible.

**Publish-only: yes.** `"actions":["publish"]` on a topic mask (e.g.
`grandpa/config`) gives exactly publish-only; omitting `"subscribe"` denies it.
Source: schema above; [ACL blog]
(https://flespi.com/blog/take-control-of-token-access-permissions-with-flexible-acls).

---

## 4. Remote-config pattern for our own Android client

For a **custom client we control**, the sane pattern is:

```
admin UI  --publish JSON (retained)-->  grandpa/config
receiver  --subscribe (retained)------>  applies config on receipt/reconnect
```

**Recommendation: yes, publish a config JSON to a custom topic.** Flespi's
platform device commands/settings are designed for *protocol-recognised GPS
trackers*, not for an arbitrary app that speaks MQTT directly; forcing our client
through `gw/devices/...` means inventing a Flespi channel/protocol mapping — pure
overhead (see §5).

Pros:
- Dead simple, tool-agnostic, no Flespi platform entities needed at all.
- Decoupled: admin writes; receiver reads whenever online.
- **Retained messages** give last-known-config replay on reconnect; the broker
  also offers `modified_since`, wildcard retained deletion, and retained+SUBACK
  ordering semantics ([MQTT broker](https://flespi.com/mqtt-broker)) — so a
  config pushed once survives receiver restarts.
- Enables optimistic concurrency if the config carries a monotonically
  increasing `version`.

Cons:
- No built-in schema validation or transaction: the app must validate and
  reject bad JSON itself.
- No delivery acknowledgement by default; add a `grandpa/config/ack` publish if
  the admin needs confirmation.
- A single retained config topic is a **global** switch — one writer corrupts
  all receivers.

Security surface: this token can reconfigure *and possibly unlock* the kiosk, so
treat it as a high-value credential. Mitigate with ACL **publish-only on
`grandpa/config`**, allowed origins pinned to the exact Pages origin, IP
whitelist where feasible, short TTL + easy revocation, and per-admin tokens so
one leak doesn't require re-keying everyone. The receiver should treat config as
untrusted: validate ranges, cap "unlock"/"admin" style fields, and never let a
config message do arbitrary command execution.

---

## 5. Flespi device-management / commands — relevant?

Flespi has a full device commands & settings system, but it targets
Flespi-*registered* devices (protocols that expose commands). Relevant facts:
- **Commands** API: real-time (`POST /gw/devices/{sel}/commands`, 15 s default
  timeout) or queued (`.../commands/queue`, TTL 60 s–30 days, 10 delivery
  attempts). Custom commands accept arbitrary protocol text. Source:
  [Commands & settings KB](https://flespi.com/kb/commands-and-settings).
- **Settings** = named command wrapper + **device shadow** (stores current and
  pending value). Settings are a subset of commands; `DELETE` re-reads from
  device; pending settings wait indefinitely; commands queue max 100.
  Same source.
- Settings-polling frequency is configurable (`once`/`never`/`daily`/…).
  Same source.
- MQTT exposes the queue/settings lifecycle as topics, e.g.
  `flespi/log/gw/devices/{id}/commands-queue/+/sent,processed,expired` and
  `flespi/state/gw/devices/{id}/settings/...` ([MQTT topics guide]
  (https://flespi.com/kb/mqtt-topics)).

**Fit for a custom MQTT client:** weak. These work only if our receiver is
modelled as a Flespi **device** with a protocol that defines the commands — i.e.
we would have to wrap our config in a Flespi-recognised command/setting schema
and register a channel/device. That is Flespi's answer for *hardware fleets*, not
for two MQTT apps talking to each other. The custom-topic retained-JSON pattern
in §4 is simpler and equally secure if the topic ACL is tight. (Flespi's own
docs position MQTT as "an efficient events bus that can interconnect software
(micro-)services" — [MQTT API](https://flespi.com/mqtt-api) — which is exactly
our case.)

One genuinely relevant platform feature for *admin tooling*: the
`response_cid` user property lets a **Master** MQTT connection run a REST call
and redirect the response to a subaccount ([MQTT API](https://flespi.com/mqtt-api))
— useful if you later split the family account into subaccounts. It does not
make a Master token browser-safe.

---

## Bottom line

- A **browser can drive admin ops over WSS** (`wss://mqtt.flespi.io:443`, token
  as username): custom-topic config writes for sure; even Flespi REST/platform
  calls would proxy through — but platform calls require a **Master** token,
  which must never live in a browser.
- **Recommended pattern:** admin browser holds a narrowly-scoped **ACL** token
  (`mqtt` ACE: `topic: grandpa/config`, `actions: ["publish"]`), origin-locked to
  the Pages origin; it publishes a versioned config JSON (retained) to
  `grandpa/config`; the Android receiver subscribes and applies it, validating
  before acting.
- **Top security caveat:** possession = full authority. A stolen or XSS-leaked
  token in `localStorage` is game over for whatever that token can do, so scope
  it to publish-only on one topic, never Master, prefer `sessionStorage`,
  short TTL, origins + IP locks, per-admin issuance and revocation.

---

## Sources

- Flespi MQTT API — https://flespi.com/mqtt-api
- Flespi API basics (REST + MQTT, response format, rest-over-mqtt) — https://flespi.com/kb/flespi-api-basics
- Flespi MQTT topics guide — https://flespi.com/kb/mqtt-topics
- Flespi MQTT broker (hosts/ports, QoS, ACL, user properties, limits) — https://flespi.com/mqtt-broker
- Flespi token KB (types, origins, IP whitelist, ACL for MQTT) — https://flespi.com/kb/tokens-access-keys-to-flespi-platform
- Flespi blog: MQTT scores again (REST API over MQTT) — https://flespi.com/blog/mqtt-scores-again-flespi-rest-api-over-mqtt
- Flespi blog: flexible ACLs — https://flespi.com/blog/take-control-of-token-access-permissions-with-flexible-acls
- Flespi commands & settings KB — https://flespi.com/kb/commands-and-settings
- Flespi Platform OpenAPI (`token.ace.mqtt`, `token.origins`) — https://flespi.io/platform/api.json
- Flespi MQTT Broker OpenAPI — https://flespi.io/mqtt/api.json
- OWASP HTML5 Security Cheat Sheet (localStorage guidance) — https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html

## Unverified / caveats

- **Platform ops over `flespi/rest` are INFERENCE**, not a one-line vendor
  statement: it follows from (a) REST-over-MQTT being a proxy to HTTP REST with
  the connection's token and (b) Master being the only token type reaching
  `/platform/`. No Flespi doc explicitly says "an MQTT Master token can create
  tokens over `flespi/rest/...`". Confirm with a live test using a throwaway
  Master token before relying on it.
- The exact role of the `methods` field on an `mqtt` ACE (schema default
  `GET,POST,DELETE`) is not explained in prose; `actions` (`publish`/`subscribe`)
  is the documented pub/sub gate.
- Origin checks rely on the client-supplied `Origin`; non-browser replayers can
  omit it, so origins are not a substitute for IP whitelisting / short TTL.
- Flespi's own docs do not give a browser-token storage best practice; the
  `localStorage` guidance here is generic web-security (OWASP/MDN), not
  Flespi-specific.
