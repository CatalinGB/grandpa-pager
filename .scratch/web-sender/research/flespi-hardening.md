# Flespi hardening checklist — change these defaults

Research for grandpa-pager: an always-on Android receiver + a static GitHub Pages
browser sender, both talking to Flespi over TLS/WSS. This is a **concrete
"change these defaults" checklist**, not a coverage review.

Every claim is traced to a **Flespi-owned primary source** (docs, KB, official
API spec, Flespi blog, forum). Date of research: **Oct 2026**. The authoritative
token schema is the official Platform API spec at
<https://flespi.io/platform/api.json> (referenced below by definition name, e.g.
`token.ace.mqtt`).

Topics used by this project: **`grandpa/message`** (senders → receiver) and
**`grandpa/status`** (receiver → watchers). Never prefix them with `flespi/`
(MQTT ACLs cannot target that namespace — `token.ace.mqtt` pattern
`^(?!flespi$)(?!flespi\/).*$`).

---

## The dangerous defaults (what a new token gets)

| Field | Default | Why it is dangerous |
|---|---|---|
| `access.type` | **`0` = Standard** (`token.access.post` `x-schema-default`) | A Standard token is *not* default-deny: it can create/edit/delete **any** channel, device, stream, plugin, geofence, calculator, asset, modem, CDN and container, and can publish/subscribe to **any** non-`flespi/` topic. There is no ACL. |
| `origins` | **`"*"` = any web origin** (`token.origins.post` default `"*"`) | Any website can use the token from a browser. |
| `ips` | **empty = all IPs** (`token.ips`) | Use-anywhere. |
| `ttl` / `expire` | **none** — you *must* supply one (`token.post` `x-schema-anyOf`; error `"'expire' or 'ttl' properties are required"`) | No silent default, but a naive client picks a short `expire`. |
| ACL when type=2 | **allow-list only, everything else denied** (KB: *"What's not allowed is forbidden"*) | Good — but only if you actually set `type:2`; the default Standard token is *not* ACL-restricted. |

> Flespi's own KB recommends ACL tokens: *"We recommend to use ACL tokens by
> providing to the application only the access level it may need."*

---

## 1. Token ACLs — create scoped, publish/subscribe-only tokens

### 1a. Sender token — **publish-only on `grandpa/message`** (lives in browser `localStorage`)

```
POST https://flespi.io/platform/tokens
Authorization: FlespiToken <MASTER-TOKEN>
Content-Type: application/json

[
  {
    "info": "grandpa-pager web sender (publish-only)",
    "ttl": 31536000,
    "expire": 0,
    "access": {
      "type": 2,
      "acl": [
        { "uri": "mqtt", "topic": "grandpa/message", "actions": ["publish"] }
      ]
    },
    "origins": ["https://<your-user>.github.io"]
  }
]
```

### 1b. Receiver token — **subscribe `grandpa/message` + publish `grandpa/status`** (Android, backend-only)

```
[
  {
    "info": "grandpa-pager Android receiver (sub message / pub status)",
    "ttl": 31536000,
    "expire": 0,
    "access": {
      "type": 2,
      "acl": [
        { "uri": "mqtt", "topic": "grandpa/message", "actions": ["subscribe"] },
        { "uri": "mqtt", "topic": "grandpa/status",  "actions": ["publish"] }
      ]
    },
    "origins": []
  }
]
```

### 1c. Status token — **subscribe on `grandpa/status`** (+ publish if the watcher also sets status)

```
"acl": [
  { "uri": "mqtt", "topic": "grandpa/status", "actions": ["publish", "subscribe"] }
]
```
Drop `"publish"` if the watcher is strictly subscribe-only.

### Field notes (exact names)

- `access.type`: **`0` = Standard, `1` = Master, `2` = ACL, `3` = NOAPI**
  (`token.result.flags` `x-view-enum-labels`; also returned as shorthand
  `access_type`). **Set `2`.**
- `access.acl`: array of ACEs (`token.acl`, max 32 items). Each MQTT ACE
  (`token.ace.mqtt`) has:
  - `uri`: literal **`"mqtt"`** (only required field)
  - `topic`: the MQTT topic mask (max 1024 chars; cannot start with `flespi/`)
  - `actions`: array of **`"publish"` / `"subscribe"`** (min 1, max 2)
  - `methods`: optional; only meaningful for REST ACEs. For `mqtt` it can be
    omitted — `publish`/`subscribe` are governed by `actions`.
- The ACL is **default-deny**: any action/topic not listed is forbidden.
- Topic selectors are allowed in `topic`, e.g. `grandpa/message,status`.
- **There is no `forbid` field.** The mechanism is an allow-list only; the
  "deny" side is the default. Restricting an already-scoped token further = do
  not grant the action.

### UI location

flespi panel (`https://flespi.io`) → left menu **Tokens** → **"+"** →
choose access type **ACL** → **Add** an ACE → select module **mqtt** → enter
**topic**, tick **actions** (publish / subscribe), set **methods** if prompted →
Save. Do this once per role token.

---

## 2. WebSocket / browser security — restrict the token's origins

- **Field: `origins`** (JSON), returned as `origins` on the token and set via
  `origins` on create/update (`token.origins`, `token.origins.post`).
- **Default is `"*"` — any origin.** You must change it.
- Value is either the string `"*"`, or an **array**. Array items are either a
  **plain custom-origin string** (schema `token.origin.custom`, pattern
  `^\w+://[\w*?_-]+(\.[\w*?_-]+)*(:\d+)?$`) or the single object form
  `{"preset":"flespi"}` (`token.origin.flespi`). Custom origins are **strings,
  not objects**.
- Lock the browser sender token to the Pages origin:
  ```json
  "origins": ["https://<your-user>.github.io"]
  ```
  (Wildcards allowed, e.g. `https://*.github.io`; prefer the exact origin.)
- **Backend-only tokens: set `"origins": []`** (empty array). This *blocks all
  browser/WSS use* of the token while native MQTT/TCP still works — ideal for
  the Android receiver token.
- The origin check applies **only to web-browser (WSS) connections**; a native
  TCP client is not checked by `origins`. A disallowed origin is rejected with
  **CONNACK `0x87` not authorized** (reason `5` for MQTT 3.x). Token log event
  code **1501** = "attempt to use token from forbidden web origin".
- Do **not** add `{"preset":"flespi"}` to these app tokens unless you want that
  token usable from flespi.io origins; use the panel with your Master token
  instead.

### UI location

Same token dialog → **Allowed origins** field (toggle) → remove the "any"
default → add your origin(s) or leave empty for backend-only.

---

## 3. TLS — Flespi does **not** force it; choose the secure ports

- Flespi publishes **both** secure and **plaintext** ports
  (`https://flespi.com/mqtt-broker`, `https://flespi.com/mqtt-api`):
  - MQTT over TCP: **`8883` (SSL)** or **`1883` (non-SSL)**
  - MQTT over WebSockets: **`443` (SSL/WSS)** or **`80` (non-SSL/WS)**
- There is **no token or account setting that forces TLS** — the Platform API
  spec contains no `tls`/`ssl`/`require_ssl` field. Enforcement is a client
  choice.
- **Change the default:** in client code always use the TLS endpoints —
  `mqtts://mqtt.flespi.io:8883` (native/Android) and
  `wss://mqtt.flespi.io:443` (browser). Never configure `1883`/`80`.
- Nothing to "enable" server-side; verify in client that the scheme is `mqtts`/
  `wss` and that certificate validation is on.

---

## 4. Account master token — never ship it to clients

- The **Master** token is `access.type = 1` and *"there are no restrictions for
  this type"*; it is the only type that can call `/platform/...` and create
  other tokens.
- **Identify it:** `GET https://flespi.io/platform/tokens/all?fields=id,key,access`
  — the entry with `access.type == 1` (shorthand `access_type == 1`, label
  "Master"). A scoped token shows `access.type == 2` (ACL). For the token
  currently in use, `GET /auth/info` with it in the `Authorization` header.
- **Rule:** the Master token is used only by you, offline, to mint the scoped
  tokens in §1. It must **never** appear in `sender.js`, `localStorage`, the
  Android app, or any config shipped to a device.

---

## 5. Token TTL — make it non-expiring *in practice*

- Flespi *requires* expiry: *"You cannot create a token without time
  limitations. The token MUST expire."* Either `ttl` or `expire` is mandatory on
  create.
- **Field names:** `ttl` (rolling, seconds, **max `31536000` = 365 days**,
  `token.ttl`) and `expire` (fixed UNIX timestamp, `token.expire`).
- **`ttl` is rolling:** *"specifies interval that is used to automatically
  update token expiration time with value of current-time + ttl, whenever you
  are doing any REST request or have active MQTT session with this token."*
- **Set exactly:**
  ```
  "ttl": 31536000,
  "expire": 0
  ```
  Leave `expire` at `0`/unset — never pin a fixed date. `ttl: 0` disables the
  rolling refresh (wrong). The always-on Android receiver keeps its token
  fresh indefinitely; the browser token is refreshed on each sender connection.

---

## 6. Privacy defaults to change

- **Retained / persisted data.** Messages you publish can persist: retained
  storage (Free **100 MB**) and persistent MQTT session storage (Free
  **100 MB**). Clear retained state with an empty-payload publish to the topic
  mask when done; keep payloads small. (§ *MQTT broker* pricing table.)
- **Logs are kept regardless.** Platform/broker logs are retained
  **30 days or 3 GB** on every plan including Free; there is no documented
  switch to disable them. Don't put sensitive content in topics; if the message
  is private, **encrypt the payload at the application layer** (Flespi offers
  transport TLS, not end-to-end/sealed payload encryption).
- **Sender identity is exposed to the receiver.** The broker signs every
  published message with a **`token_id`** user property (and adds `timestamp`).
  With one shared sender token this fingerprints the app, not each user — an
  argument for per-role (not per-user) tokens, or for not relying on `token_id`
  as an authz signal.
- **Sharing is off by default — keep it off.** Do not create subaccounts,
  grants, realms, or extra users for this project; a token created on your
  account is account-private unless explicitly shared. On token creation, leave
  **"Create for subaccount"** unchecked.
- **IPs allow-list.** `ips` defaults to empty (any IP). For the *receiver*
  token you may set a fixed egress IP, but public GitHub Pages senders have
  changing IPs, so do **not** IP-lock the browser token — use `origins`
  instead.
- **Data residency.** Hosting is EU (two Netherlands DCs, Zylon), operated by
  Gurtam UAB (Lithuania) under GDPR — no action needed, but note the region.

---

## Ordered checklist (do these in order)

1. Log in to the panel; **copy the Master token once** (it is `access.type=1`). Keep it offline.
2. Create the **sender** ACL token: `access.type=2`, one ACE
   `{"uri":"mqtt","topic":"grandpa/message","actions":["publish"]}`,
   `origins=["https://<user>.github.io"]`, `ttl=31536000`, `expire=0`.
3. Create the **receiver** ACL token: `access.type=2`, ACEs
   `{"uri":"mqtt","topic":"grandpa/message","actions":["subscribe"]}` and
   `{"uri":"mqtt","topic":"grandpa/status","actions":["publish"]}`,
   `origins=[]` (backend-only), `ttl=31536000`, `expire=0`.
4. Create the **status** ACL token: `access.type=2`, ACE
   `{"uri":"mqtt","topic":"grandpa/status","actions":["publish","subscribe"]}`,
   `origins=["https://<user>.github.io"]`, `ttl=31536000`, `expire=0`.
5. In every client, use TLS only: `mqtts://mqtt.flespi.io:8883` (Android) and
   `wss://mqtt.flespi.io:443` (browser). Never 1883/80.
6. Ensure no Master/Standard token is embedded anywhere in shipped code.
7. Verify: `GET /platform/tokens/all?fields=id,key,access,origins,ttl` shows all
   three tokens at `access.type=2` with the right `origins`/`ttl`; watch the
   token **Logs** tab for event `1501` (forbidden origin) to confirm the check
   is active.
8. Keep payloads free of secrets (logs are kept 30 days); clear retained
   messages when they are no longer needed.

---

## Sources

- **Official Flespi Platform API spec** (primary; definitions quoted above:
  `token.access.post` `x-schema-default {type:0}`, `token.access.acl`,
  `token.ace.mqtt` `uri/topic/actions/methods`, `token.acl` max 32,
  `token.origins` / `token.origins.post` default `"*"` / `token.origin.custom`
  / `token.origin.flespi`, `token.ttl` max 31536000, `token.expire`,
  `token.ips`, `token.post` anyOf expire/ttl, `token.result.flags` enum labels,
  `POST /platform/tokens`, `/auth/info`) —
  <https://flespi.io/platform/api.json> (index: <https://flespi.io/docs/>)
- KB: Token — access keys to the flespi platform (token types; **"What's not
  allowed is forbidden"**; must expire; TTL/expire semantics; **Allowed
  origins** default any + `{"preset":"flespi"}` + empty array blocks browsers +
  CONNACK 0x87; IP whitelist; MQTT ACL cannot target `flespi/`) —
  <https://flespi.com/kb/tokens-access-keys-to-flespi-platform>
- Flespi MQTT broker — configuration (host/ports incl. **1883 non-SSL** and
  **80 non-SSL**, token as username, QoS, retained storage, logs via REST/MQTT,
  `token_id`/`timestamp` user properties) — <https://flespi.com/mqtt-broker>
- Flespi MQTT API — connection details (same port list), token-as-username,
  persistent session up to 10 days — <https://flespi.com/mqtt-api>
- Blog: Take control of token access permissions with flexible ACLs (ACE
  `uri`/`methods`/`ids`, mqtt module topic+actions, `flespi/` caveat) —
  <https://flespi.com/blog/take-control-of-token-access-permissions-with-flexible-acls>
- KB: How flespi pricing works — restrictions tables: **Logs 30 days or 3 GB**,
  **MQTT retained 100 MB**, **persistent session 100 MB**, account inactive 60
  days; Free = testing/dev, non-extendable —
  <https://flespi.com/kb/how-flespi-pricing-works>
- Blog: Event-driven web application design with MQTT (browser connects over
  WebSockets with a restricted ACL token) —
  <https://flespi.com/blog/event-driven-web-application-design-with-mqtt>
- Forum [staff] "Tokens via API REST" — *"if you configure ttl, then with each
  request, the lifetime of your token will be extended"* —
  <https://forum.flespi.com/d/1333-tokens-via-api-rest>
- Forum [Changelog] flespi MQTT broker (topic selectors in MQTT ACL, will-topic
  validated against ACL, short-field auth) —
  <https://forum.flespi.com/d/19-changelog-flespi-mqtt-broker>
- KB: Account — your space inside flespi (Master/Standard/ACL panel access,
  "Account inactive — 60 days → deleted") —
  <https://flespi.com/kb/account-getting-inside-flespi>
- KB: Regions (EU/Netherlands, Gurtam UAB) —
  <https://flespi.com/kb/regions-geographically-distributed-flespi-datacenters>
- Companion: `flespi-coverage.md`, `flespi-wss.md` in this folder.
