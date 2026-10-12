# Flespi setup — what to change from the defaults

> **Confirmed ACL schema (2026-10):** each ACL entry is
> `{ "uri": "mqtt", "topic": "grandpa/#", "actions": ["publish","subscribe"] }`
> (ACL is **default-deny**). The token **display name field is `info`**, and the
> secret value is the token object's **`key`** (64 chars).
>
> **#1 gotcha:** the stock token's ACL is a *template* pointing at
> `replace/this/topic/with/your/own/#` and `you/can/add/multiple/topics/#` —
> which match nothing. Replace those topics with `grandpa/#` (or the specific
> topics below) or **nothing is ever delivered** (CONNECT succeeds, publishes are
> acked, but the broker routes nothing).

Research: `.scratch/web-sender/research/flespi-hardening.md`.
Everything runs on **one Flespi broker**. The defaults are too permissive — you
must scope them down.

## Endpoints (use TLS only)

| Client | URL |
|---|---|
| Android receiver / app | `mqtts://mqtt.flespi.io:8883` |
| Browser (GitHub Pages) | `wss://mqtt.flespi.io:443` |

Flespi also serves **plaintext** `1883`/`80` — never use those. There is no
forced-TLS switch; you enforce it by configuring the clients with the `mqtts://`
/ `wss://` URLs.

## 1. Never use the master token in a client

A token with `access.type: 1` (Master) can do anything. Keep your `.env` token on
your PC only. Create **scoped** tokens below.

## 2. Token defaults are dangerous — change them

A new token defaults to `access.type: 0` (**Standard**: can manage every
Telematics entity and publish/subscribe anywhere). You must set:

- `access.type: 2` (ACL), with an explicit `acl`.
- `origins` — default is `"*"`; set it explicitly.
- `ttl: 31536000` (1 year, Flespi's max) and `expire: 0`. The TTL is **rolling**
  (resets on activity), so a token kept active by the always-on receiver is
  effectively non-expiring.

### Sender token (goes in the web page's localStorage)

```json
{
  "name": "grandpa-web-sender",
  "access": {
    "type": 2,
    "acl": [
      { "uri": "mqtt", "topic": "grandpa/message", "actions": ["publish", "subscribe"] },
      { "uri": "mqtt", "topic": "grandpa/last",    "actions": ["publish", "subscribe"] },
      { "uri": "mqtt", "topic": "grandpa/status",  "actions": ["subscribe"] },
      { "uri": "mqtt", "topic": "grandpa/config",  "actions": ["subscribe"] }
    ]
  },
  "origins": ["https://catalingb.github.io"],
  "ttl": 31536000,
  "expire": 0
}
```

> **Note:** the sender subscribes to `grandpa/message` (to mirror each message
> into history and to show the "already showing" banner), `grandpa/last` (the
> retained history array it maintains), and `grandpa/config` (to read the
> admin's `defaults.history_count` so the choice is consistent on **every**
> device, not just the admin's browser). It publishes to `grandpa/message` and
> `grandpa/last`. A publish-only grant leaves its subscribers blind and the
> history unreadable. The config subscription is issued in a separate SUBSCRIBE
> packet, so if this grant is missing the sender still works (it falls back to a
> default of 10) — but add the grant to get the configured count everywhere.

`origins` is the key browser lock: with it set, a leaked token **cannot be used
from any other website**. Use `[]` to block browsers entirely.

### Receiver token (goes on Grandpa's phone only)

```json
{
  "name": "grandpa-receiver",
  "access": {
    "type": 2,
    "acl": [
      { "uri": "mqtt", "topic": "grandpa/message", "actions": ["subscribe"] },
      { "uri": "mqtt", "topic": "grandpa/command", "actions": ["subscribe"] },
      { "uri": "mqtt", "topic": "grandpa/config",  "actions": ["subscribe"] },
      { "uri": "mqtt", "topic": "grandpa/status",  "actions": ["publish"] }
    ]
  },
  "origins": [],
  "ttl": 31536000,
  "expire": 0
}
```

### Admin token (goes in the admin browser page)

```json
{
  "name": "grandpa-admin",
  "access": {
    "type": 2,
    "acl": [
      { "uri": "mqtt", "topic": "grandpa/config",  "actions": ["publish", "subscribe"] },
      { "uri": "mqtt", "topic": "grandpa/command", "actions": ["publish"] },
      { "uri": "mqtt", "topic": "grandpa/status",  "actions": ["subscribe"] },
      { "uri": "mqtt", "topic": "grandpa/message", "actions": ["subscribe"] },
      { "uri": "mqtt", "topic": "grandpa/last",    "actions": ["subscribe"] }
    ]
  },
  "origins": ["https://catalingb.github.io"],
  "ttl": 31536000,
  "expire": 0
}
```

> **History:** `grandpa/last` carries the message-history array. The admin
> subscribes to it (read-only) to render the history; it is published only by the
> web sender, so no `publish` grant is needed here.

ACL is **default-deny**: only the listed topic+action pairs are permitted.

> **Admin commands:** the `publish grandpa/command` grant is what lets the console
> issue `set_connection` (guarded broker change), `restart_app` and `reboot_device`.
> Without it those buttons publish into the void. The grant is publish-only — the
> console never subscribes to the command topic.

> **Two hard-won gotchas (verified):**
> - A **publish-only** token can send but its subscribers receive **nothing**.
>   The receiver MUST have **subscribe** on `grandpa/message` (+`grandpa/command`).
> - **Flespi rejects any CONNECT that carries a Last Will** for a scoped token
>   ("Not authorized"). The receiver therefore sets **no LWT**; offline detection
>   uses status staleness instead.

## 3. Point the receiver at Flespi

In the app's setup screen (or via ADB prefs): broker host `mqtt.flespi.io`,
port `8883`, **TLS on**, username = the **receiver token**. Role: Receiver.

## 4. Point the web sender at Flespi

Open the Pages site → **Settings** → broker `wss://mqtt.flespi.io:443`,
token = the **sender token**. It is stored only in that browser's
`localStorage`.

## 4b. Point the admin console at Flespi

Open `…/admin.html` (or the **Admin** link on the sender) → enter the **admin
token**. It is kept in `sessionStorage` (cleared when the tab closes). The admin
page is built against `wss://mqtt.flespi.io:443`.

## 5. Privacy defaults

- The **sender token is effectively public** (users type it; it sits in their
  browser). Mitigate with the ACL + origin lock above, and rotate it if a device
  is lost. Recreate the token to rotate (independent revocation).
- `origins: []` on the receiver token prevents it being used from a browser at all.
- The **admin token is more powerful** (it can reconfigure the device, including
  kiosk lock). Keep it out of `localStorage` (the app uses `sessionStorage`), lock
  it to the Pages origin, and rotate it if lost. Never the Master token.
- Prefer per-person sender tokens if you need to revoke one person only.
