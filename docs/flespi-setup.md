# Flespi setup — what to change from the defaults

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
      { "uri": "mqtt", "topic": "grandpa/message", "actions": ["publish"] },
      { "uri": "mqtt", "topic": "grandpa/status",  "actions": ["subscribe"] }
    ]
  },
  "origins": ["https://catalingb.github.io"],
  "ttl": 31536000,
  "expire": 0
}
```

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
      { "uri": "mqtt", "topic": "grandpa/status",  "actions": ["publish"] }
    ]
  },
  "origins": [],
  "ttl": 31536000,
  "expire": 0
}
```

ACL is **default-deny**: only the listed topic+action pairs are permitted.

## 3. Point the receiver at Flespi

In the app's setup screen (or via ADB prefs): broker host `mqtt.flespi.io`,
port `8883`, **TLS on**, username = the **receiver token**. Role: Receiver.

## 4. Point the web sender at Flespi

Open the Pages site → **Setări broker** → broker `wss://mqtt.flespi.io:443`,
token = the **sender token**. It is stored only in that browser's
`localStorage`.

## 5. Privacy defaults

- The **sender token is effectively public** (users type it; it sits in their
  browser). Mitigate with the ACL + origin lock above, and rotate it if a device
  is lost. Recreate the token to rotate (independent revocation).
- `origins: []` on the receiver token prevents it being used from a browser at all.
- Prefer per-person sender tokens if you need to revoke one person only.
