# MQTT.js in a plain static HTML page (GitHub Pages)

Research date: 2026-10-10. Primary sources: the MQTT.js repo at tag **v5.16.0**
(`github.com/mqttjs/MQTT.js`, `README.md`, `CHANGELOG.md`, published bundles),
the npm registry metadata for `mqtt@5.16.0`, live CDN responses, the MQTT 3.1.1
OASIS spec, EMQX broker docs/HW schema, and MDN/W3C web-platform specs. Every
claim is traced to a source unless marked **UNVERIFIED**.

## TL;DR

- **Yes, `https://unpkg.com/mqtt/dist/mqtt.min.js` still works** — it is an IIFE
  (not ESM) bundle, and MQTT.js's own README still documents it as the CDN path.
  It currently resolves to `mqtt@5.16.0` and returns HTTP 200. The proposal's
  `<script src=…>` line is **not broken**; the only fix needed is to **pin the
  version**.
- Recommended (plain page, no build step):
  ```html
  <script src="https://unpkg.com/mqtt@5.16.0/dist/mqtt.min.js"></script>
  <script>
    // global `mqtt` (window.mqtt) -> mqtt.connect(...)
  </script>
  ```
  (jsDelivr byte-identical mirror: `https://cdn.jsdelivr.net/npm/mqtt@5.16.0/dist/mqtt.min.js`.)
- ESM alternative (modern, still no bundler):
  ```html
  <script type="module">
    import mqtt from "https://cdn.jsdelivr.net/npm/mqtt@5.16.0/dist/mqtt.esm.js";
    const client = mqtt.connect("wss://…", { /* … */ });
  </script>
  ```
  Note: the `dist/mqtt.esm.js` bundle has **only a default export** — use
  `import mqtt from …` and `mqtt.connect`, **not** `import { connect }`.
- **Breaking change vs the pasted code: none in the API.** `mqtt.connect(...)`
  is unchanged in v5. The only v5 breaking change is that constructing an
  `MqttClient` instance now needs `new` — you never do that with `connect()`.
  The one edit to make is **pinning the CDN URL** (unpinned `mqtt/dist/…` tracks
  npm `latest`, so a future major could silently change under you).

---

## 1. Is `mqtt/dist/mqtt.min.js` still valid in v5? What's the recommended way?

**Valid, yes.** Despite v5 being "ESM-first", the package still ships prebuilt
UMD/IIFE and ESM bundles, and the README's *Browser → Via CDN* section still says:

> "The MQTT.js bundle is available through http://unpkg.com, specifically at
> https://unpkg.com/mqtt/dist/mqtt.min.js."

The package's `browser`-condition export map confirms the intended browser entries:

| File | Format | Exports | Notes |
|---|---|---|---|
| `dist/mqtt.js` | IIFE, unminified | global `mqtt` | same API |
| `dist/mqtt.min.js` | IIFE, minified | global `mqtt` | the paste's path |
| `dist/mqtt.esm.js` | ESM, minified | **default only** | `export default MI()` |

Evidence the IIFE exposes the global: the bundle begins
`"use strict";var mqtt=(()=>{ … return MI();})();` — a top-level `var` in a
classic `<script>` becomes `window.mqtt`, and the returned module namespace
carries `connect`, `connectAsync`, `Client`, `default`, etc.
Both the MQTT.js README and the EMQX beginner guide show exactly
`<script src="https://unpkg.com/mqtt/dist/mqtt.min.js"></script>` followed by
`mqtt.connect(...)`.

Package metadata (`mqtt@5.16.0`):
```
"main":   "./build/index.js"
"module": "./dist/mqtt.esm.js"
"exports": { ".": { "browser": { "import": "./dist/mqtt.esm.js",
                                "default": "./dist/mqtt.min.js" },
                   "default": "./build/index.js" } }
```

### Exact, pinned, verified URLs (all return HTTP 200, `text/javascript`)

| URL | Format | Verified |
|---|---|---|
| `https://unpkg.com/mqtt@5.16.0/dist/mqtt.min.js` | IIFE (global `mqtt`) | 200 |
| `https://cdn.jsdelivr.net/npm/mqtt@5.16.0/dist/mqtt.min.js` | IIFE (byte-identical to unpkg) | 200 |
| `https://unpkg.com/mqtt@5.16.0/dist/mqtt.esm.js` | ESM (default export) | 200 |
| `https://cdn.jsdelivr.net/npm/mqtt@5.16.0/dist/mqtt.esm.js` | ESM (byte-identical to unpkg) | 200 |
| `https://esm.sh/mqtt@5.16.0` | ESM (re-bundled, default export) | 200 |
| `https://cdn.jsdelivr.net/npm/mqtt@5.16.0/+esm` | ESM (re-bundled, default export) | 200 |

`unpkg.com/mqtt/dist/mqtt.min.js` (unpinned) currently `302`→`mqtt@5.16.0/dist/mqtt.min.js`.

**Important gotcha with the ESM CDNs:** `esm.sh`, jsDelivr `+esm`, and the raw
`dist/mqtt.esm.js` all expose **only the default export** (verified: their final
export map is `export{… as default}`). So `import { connect } from "mqtt"` works
**only** with a bundler resolving the package `main`; from a CDN in a plain page,
use the default import form.
`dist/mqtt.esm.js` is a single self-contained file (no external `import`
specifiers), so a bare `import mqtt from "…/mqtt.esm.js"` needs no import map.

**Recommended for this project (simplest, matches existing proposal): the pinned
IIFE `<script>`.** No `type="module"`, no import map, works on GitHub Pages.

### Optional Subresource Integrity (SRI)
Both CDNs send permissive CORS, so SRI works. sha384 of the v5.16.0 `mqtt.min.js`
(identical bytes on unpkg + jsDelivr):
```
sha384-9WWboT9UtuPFRlOLq4FygQkcJxzYcpz8SXGkNyCEXM+44n14xzjNlrRKYZwuF/A6
```
```html
<script src="https://unpkg.com/mqtt@5.16.0/dist/mqtt.min.js"
        integrity="sha384-9WWboT9UtuPFRlOLq4FygQkcJxzYcpz8SXGkNyCEXM+44n14xzjNlrRKYZwuF/A6"
        crossorigin="anonymous"></script>
```
(`dist/mqtt.esm.js` sha384: `m/pd/4VFUnqe7gqk+Nunx4dI9FRuhIQf+1LGTVUUXvLx3+JBzSiAnp6qUHdwJoNi`.)

---

## 2. `wss://`, credentials, protocol version, clientId, reconnect, clean

**Only WebSocket works in a browser.** The README is explicit:

> "The only protocol supported in browsers is MQTT over WebSockets, so you must
> use `ws://` or `wss://` protocols."

Supported URL schemes overall: `mqtt, mqtts, tcp, tls, ws, wss, wxs, alis`
(`wss` = MQTT-over-TLS-WebSocket). Browser uses the native `WebSocket` object (the
Node `ws` module is not used); it opens the socket with the MQTT subprotocol:

> `new WebSocket(url, [protocol])` where `protocol` is `"mqtt"` (or `"mqttv3.1"`
> when `protocolId:"MQIsdp", protocolVersion:3`).

That subprotocol matters: **EMQX fails connections with no subprotocol by
default** (`fail_if_no_subprotocol` = `true`, `supported_subprotocols` =
`mqtt, mqtt-v3, mqtt-v3.1.1, mqtt-v5`), and MQTT.js sends `mqtt`, so it is fine.

Credentials are plain options on the options object. Brokers differ — e.g. flespi
uses the **token as `username`**, EMQX uses a real username/password.

### Connect options that matter here (README `mqtt.Client` defaults)

| Option | Default | Notes |
|---|---|---|
| `protocolVersion` | `4` (**MQTT 3.1.1**) | set `5` for MQTT 5.0 |
| `protocolId` | `"MQTT"` | leave as-is unless an old broker |
| `clientId` | `'mqttjs_' + random hex` | auto-generated if omitted; **must be unique** per connection (flespi round-robins duplicates) |
| `clean` | `true` | `false` = persistent session / receive QoS1-2 while offline |
| `reconnectPeriod` | `1000` ms | `0` disables auto-reconnect |
| `keepalive` | `60` s | `0` disables pings |
| `connectTimeout` | `30000` ms | wait for CONNACK |
| `resubscribe` | `true` | auto re-subscribe on reconnect |
| `reconnectOnConnackError` | `false` | set `true` to retry even on CONNACK errors (e.g. bad auth) |
| `username` / `password` | — | broker credentials |
| `will` | — | Last Will (see §4) |
| `transformWsUrl`, `createWebsocket` | — | ws/wss-only hooks (signed URLs / custom subprotocol) |

**MQTT version:** v5.0 and v3.1.1 are both supported. **Do not set
`protocolVersion` unless you need 5.0** — omitting it uses 3.1.1 (`4`), which all
three candidate brokers accept. Only set `protocolVersion: 5` if you actually use
5.0-only features (user properties, will delay, session expiry).

### Working connect example (token auth, wss)

```html
<!doctype html>
<meta charset="utf-8">
<title>Grandpa Pager — sender</title>
<script src="https://unpkg.com/mqtt@5.16.0/dist/mqtt.min.js"></script>
<script>
  const client = mqtt.connect("wss://mqtt.example.com:443/mqtt", {
    // --- auth ---
    username: TOKEN_USER,        // e.g. flespi token, or EMQX username
    password: TOKEN_PASS,        // (flespi: leave empty)
    // --- identity / session ---
    clientId: "pager-sender-" + Math.random().toString(16).slice(2, 10),
    clean: true,                 // MQTT 3.1.1 default is true
    // protocolVersion: 5,       // omit for 3.1.1 (recommended default)
    // --- liveness ---
    keepalive: 60,
    reconnectPeriod: 2000,       // auto-reconnect after 2 s
    connectTimeout: 30000,
    // rejectUnauthorized / TLS options are NOT available in the browser
  });

  client.on("connect", () => { /* publish here */ });
  client.on("error", (err) => console.error("mqtt error", err));
  client.on("reconnect", () => console.log("reconnecting…"));
</script>
```

Publish/subscribe signatures (README):
- `client.publish(topic, message, [options], [callback])` — `message` is
  `Buffer | String`; `options = {qos, retain, dup, properties}`; callback
  `(err, packet)` fires when QoS handling completes (next tick for QoS 0).
- `client.subscribe(topic|topics|obj, [options], [callback])` — callback
  `(err, granted)`, `granted = [{topic, qos}]`. `unsubscribe(topic, …)`.
- Promises: `connectAsync`, `publishAsync`, `subscribeAsync`.

---

## 3. Retained publish from the browser, and clearing it

Retained publish is a client-side flag; the broker stores it:

```js
// Publish retained QoS1
client.publish("grandpa/pager/message", JSON.stringify(msg), { qos: 1, retain: true },
  (err) => { if (err) console.error("publish failed", err); });
```

**Clearing the retained message = publish a zero-byte payload with `retain: true`:**

```js
// Clear the retained message for that topic
client.publish("grandpa/pager/message", "", { qos: 1, retain: true });
// (an empty string / Buffer.alloc(0) both give a zero-byte payload)
```

This is broker behaviour mandated by the MQTT spec, not a MQTT.js quirk. MQTT
3.1.1 §3.3.1.3, **[MQTT-3.3.1-10]/[MQTT-3.3.1-11]**:

> "A PUBLISH Packet with a RETAIN flag set to 1 and a payload containing zero
> bytes will be processed as normal by the Server … Additionally any existing
> retained message with the same topic name MUST be removed and any future
> subscribers for the topic will not receive a retained message. … A zero byte
> retained message MUST NOT be stored as a retained message on the Server."

Practical notes:
- Publish/clear only after `'connect'` (or check `client.connected`); MQTT.js
  queues QoS-0/1 messages pre-connect, but a status message ordering is easier to
  reason about after connect.
- Use QoS 1 + `retain:true`; use the callback rather than assuming delivery.
- `flespi` extra: publishing an **empty payload to a wildcard topic** performs a
  bulk delete of matching retained messages (flespi-specific).
- `retain` interacts with `subscribe({rh, rap})` only in MQTT 5; for 3.1.1 a new
  subscriber simply receives the current retained message.

Subscribe to the status/diagnostics topic:
```js
client.subscribe("grandpa/pager/status", { qos: 1 }, (err, granted) => {
  if (err) console.error(err);
  else console.log("granted", granted); // [{topic:"grandpa/pager/status", qos:1}]
});
client.on("message", (topic, message, packet) => {
  console.log(topic, message.toString(), "retain:", packet.retain);
});
```

---

## 4. Last Will (`will`) in the browser

**Yes — supported in the browser.** `will` is a normal CONNECT-packet option on
`mqtt.connect` (it is transported over the WebSocket like any other MQTT packet),
and the EMQX official browser example sets it. README format:

```js
const client = mqtt.connect("wss://mqtt.example.com:443/mqtt", {
  username: TOKEN_USER,
  clientId: "pager-sender",
  will: {
    topic: "grandpa/pager/status",
    payload: JSON.stringify({ sender: "alice", online: false }),
    qos: 1,
    retain: true,
    // MQTT 5.0 only:
    // properties: { willDelayInterval: 5, messageExpiryInterval: 3600,
    //               contentType: "application/json" }
  },
});
```

Notes:
- `will.topic` is required by the spec ([MQTT-3.1.2-9]); MQTT.js errors on
  "Invalid will topic" if missing.
- The will is published by the broker only on an **unclean** disconnect (network
  drop), not on `client.end()`.
- MQTT 5 `will.properties` (will delay, expiry, content type, user properties)
  work if you set `protocolVersion: 5`; on 3.1.1 set only `topic/payload/qos/retain`.

---

## 5. Browser constraints: CORS/Origin and secure context

### Secure context / mixed content (the real constraint for GitHub Pages)
- GitHub Pages is served over **HTTPS**. A browser will **block `ws://` from an
  HTTPS page as mixed content** and also blocks mixed `<script>`/`fetch`. So on
  GitHub Pages you **must use `wss://`** (TLS WebSocket), and the broker's
  certificate must be trusted by the browser (public CA, hostname matches, not
  expired). EMQX's own guide: "make sure to use encrypted WebSocket connections
  when under HTTPS"; "you must ensure the broker's certificate is trusted by the
  client's browser."
- `WebSocket` itself is not a secure-context-only API, but the host page's scheme
  decides: HTTPS page → `wss://` only. See W3C Mixed Content §3.2/§4.4 (untrusted
  URLs are blockable) and MDN Mixed content.
- **No client certificates / `ca` / `rejectUnauthorized` in the browser** — you
  cannot do mutual-TLS or pin a CA from JS; the browser controls trust. (EMQX
  FAQ + MQTT.js issue #1515.)

### CORS / Origin
- **WebSocket is not subject to CORS preflight** — there is no `Origin`-based
  preflight/`Access-Control-Allow-Origin` negotiation for `new WebSocket()`.
  What a broker *can* do is read the `Origin` request header sent during the
  WebSocket handshake and reject the handshake (e.g. HTTP 403) if it dislikes it.
  MDN (Writing WebSocket servers): "All browsers send an `Origin` header… you can
  use this header for security… and send a 403 Forbidden if you don't like what
  you see." Non-browser agents can forge `Origin`; browsers cannot.
- **EMQX:** origin checking is **off by default** and absence of `Origin` is
  allowed by default. From the broker schema
  (`emqx_schema.erl`): `websocket.check_origin_enable = false`,
  `websocket.allow_origin_absence = true`, `check_origins` defaults to
  `http://localhost:18083, http://127.0.0.1:18083`. So a GitHub Pages origin
  connects fine unless the operator turns the check on and allowlists origins.
- **flespi:** connectors are token-auth with topic ACLs; an Origin allowlist tied
  to MQTT WebSocket was **not confirmed** from a primary source (**UNVERIFIED**).
- **HiveMQ Cloud:** no documented `Origin` restriction was found (**UNVERIFIED**);
  treat as permissive unless proven otherwise.

### Other browser realities
- **Background-tab throttling:** browsers throttle timers in hidden tabs, which
  can delay MQTT keepalive pings and drop the connection. Mitigate with a larger
  `keepalive`, a `reconnectPeriod`, or a Web Worker. (EMQX guide.)
- **WebSocket errors are opaque:** for security, browsers hide connection-time
  failure detail, so `client.on("error")` may not report the true cause. (MQTT.js
  README, "Browser".)
- **Path matters:** most brokers require a path (`/mqtt` for EMQX; HiveMQ uses
  `/mqtt`; check the broker's `wss` URL). Omitting it is a common failure.

---

## 6. Pins and breaking changes vs the pasted code

**Pin:** `mqtt@5.16.0` (npm `latest` as of 2026-10-10). Other dist-tags seen:
`beta 5.0.0-beta.4`, `v4-backport 4.3.8`, `security-patch 2.18.9` — none relevant;
the v2/v4 advisories (`GHSA-h9mj-fghc-664w`, `GHSA-hg78-c92r-hvwr`) affect only
`< 2.15.0` / `<= 0.3.13`, **not v5**. No deprecation and no open advisory against
`5.16.0`.

**v5.0.0 breaking changes (README + CHANGELOG):**
1. Dropped EOL Node 12/14; supports Node 18/20. *(irrelevant to a browser page)*
2. TypeScript rewrite. *(irrelevant at runtime)*
3. **`new` is required to construct an `MqttClient` instance.** — the only
   user-facing API break. `mqtt.connect(...)` is unaffected.
4. Added promise APIs (`connectAsync`, `publishAsync`, …). Additive.
5. App was already ESM-first for bundlers (`import mqtt from 'mqtt'` since
   `>5.2.0`), but **dist bundles remain** for script-tag use.

**vs the pasted proposal:**
- `<script src="https://unpkg.com/mqtt/dist/mqtt.min.js">` — **still valid**; make
  it `@5.16.0` to pin. No code change required in the `mqtt.connect` / `publish`
  / `subscribe` calls: `{qos:1, retain:true}`, empty-payload clear, `{qos}` on
  subscribe, `username`/`password`/`clientId`/`reconnectPeriod`/`clean`, and `will`
  all have the same shape as in v4.
- If you instead adopt the ESM bundle: use `import mqtt from "…/mqtt.esm.js"`
  (default), not `import { connect }`.

## Unverified / uncertain

- HiveMQ Cloud and flespi **Origin/Referer enforcement for WSS** — not confirmed
  from primary docs; EMQX is the only broker with a documented, verified default
  (off).
- Whether MQTT.js sends a `Sec-WebSocket-Protocol` value the broker rejects: EMQX
  is fine (`mqtt`); not separately tested against every broker.
- Exact byte-for-byte equivalence was checked only for `dist/mqtt.min.js` and
  `dist/mqtt.esm.js` between unpkg and jsDelivr (both identical).

## Sources

- MQTT.js repo, tag `v5.16.0` — `README.md` (Browser, Via CDN, Import styles,
  Client options, publish/subscribe/will, Reconnection) and `CHANGELOG.md`
  (5.0.0 BREAKING CHANGES):
  https://github.com/mqttjs/MQTT.js/blob/v5.16.0/README.md ,
  https://raw.githubusercontent.com/mqttjs/MQTT.js/v5.16.0/CHANGELOG.md
- MQTT.js v5.16.0 published bundles (global `mqtt`; ESM default export):
  https://unpkg.com/mqtt@5.16.0/dist/mqtt.min.js ,
  https://unpkg.com/mqtt@5.16.0/dist/mqtt.esm.js
- npm registry metadata / dist-tags for `mqtt`:
  https://registry.npmjs.org/mqtt , https://registry.npmjs.org/mqtt/5.16.0
- jsDelivr package file listing + byte-identical mirror:
  https://data.jsdelivr.com/v1/packages/npm/mqtt@5.16.0?structure=flat
- esm.sh bundle metadata: https://esm.sh/mqtt@5.16.0 (X-ESM-Path header)
- MQTT 3.1.1 OASIS spec, §3.3.1.3 retained/zero-byte
  ([MQTT-3.3.1-10], [MQTT-3.3.1-11]) and §3.1.2 will
  ([MQTT-3.1.2-9]):
  https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html
- EMQX "How to Use MQTT over WebSocket" (browser + MQTT.js, CDN snippet, will,
  wss-from-https, background-tab, no client certs):
  https://www.emqx.com/en/blog/connect-to-mqtt-broker-with-websocket
- EMQX broker config schema (WebSocket Origin defaults
  `check_origin_enable=false`, `allow_origin_absence=true`, subprotocols):
  https://raw.githubusercontent.com/emqx/emqx/master/apps/emqx/src/emqx_schema.erl
- flespi MQTT broker (token as username, WS ports 443/80, retained + wildcard
  clearing, QoS, uniqueness of ClientID): https://flespi.com/mqtt-broker
- MDN — Writing WebSocket servers (browsers send `Origin`; server may 403; no
  CORS preflight): https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_WebSocket_servers
- MDN — Mixed content (blockable vs upgradable; HTTPS page must not load insecure
  active content): https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Mixed_content
- W3C Mixed Content (Editors' Draft): https://w3c.github.io/webappsec-mixed-content/
- MDN — `WebSocket()` constructor (schemes `ws/wss/http/https`, no fragments):
  https://developer.mozilla.org/en-US/docs/Web/API/WebSocket/WebSocket
