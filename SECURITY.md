# Security audit — Grandpa Pager

Date: 2026-10-10 (updated: same day, after release hardening). Scope: the Flespi
MQTT channel, the Android receiver, the GitHub Pages web sender, and the tokens.

## Assets
- **The message channel** (`grandpa/message`, `grandpa/status`) — private family
  communication.
- **Broker tokens** (Flespi) — the thing that grants access.
- **The web sender** — a *public* static site (GitHub Pages free = public repo).

## What is NOT exposed (verified)
- **No secrets in the repo.** `.env` (the Flespi token) and the test CA private
  key are in `.gitignore`; a scan for the token value found it **only** in
  `.env`. The published site ships code only.
- The web sender **has no token baked in** — the user types it and it lives in
  their browser `localStorage`.

## Findings

1. **The sender token is effectively public.** Any script on the page, or anyone
   with access to the user's browser, can read `localStorage`. *Mitigations:*
   a **scoped ACL** token (publish-only `grandpa/message`, subscribe
   `grandpa/status`), and **`origins` locked to the Pages origin** so a leaked
   token can't be used from another site. Residual risk is a user's own browser.
2. **Never ship the master token.** The `.env` token is *not* a master
   (`/platform/tokens` returned `403`), but treat it as sensitive and keep it off
   every client.
3. **TLS-only.** Use `mqtts://mqtt.flespi.io:8883` (Android) and
   `wss://mqtt.flespi.io:443` (browser). Flespi also serves **plaintext**
   `1883`/`80` — never use those; there is no forced-TLS switch, so the clients
   must be configured with the `mqtts`/`wss` URLs.
4. **Sender identity is not authenticated.** The `sender` name is typed by the
   user; anyone holding the token can claim any name. Adequate for a family, but
   it is not identity.
5. **Receiver token on the phone.** Physical/ADB access can read it; the kiosk
   lockdown reduces casual access.
6. **Availability.** Flespi's free tier is labelled "testing & development"; a
   token's TTL is max 1 year but **rolling** (kept alive by the always-on
   receiver).
7. **Reconnect after a long outage (fixed).** Paho's `automaticReconnect` could
   give up after a long outage, leaving a dead client the service never replaced
   (ticket 17). `PagerService` now runs a 30 s watchdog that recreates the client
   whenever the link is down, for both roles.
8. **TLS hostname verification (fixed).** Paho `mqttv3` does not enable endpoint
   identification by default, so any cert from a trusted CA would be accepted for
   any host. `TlsUtil` now wraps the socket factory to force the `HTTPS` endpoint
   identification algorithm.
9. **Bundled test CA (fixed for release).** The app used to trust the bundled
   test CA (`res/raw/ca.crt`, whose private key lives on the dev machine) in all
   builds. It is now trusted **only in debug builds**; release trusts the
   platform CAs only.
10. **CDN dependency removed.** MQTT.js is **vendored** (`mqtt.min.js`) so the
    page works even if a CDN is blocked — also removing a third-party script from
    the trust path.
11. **Content-Security-Policy added.** The web sender — which holds a broker
    token — now ships a strict CSP (`script-src 'self'`, `connect-src 'self' wss:`,
    `base-uri 'none'`, `form-action 'none'`, `object-src 'none'`).

## Recommended actions (priority order)
1. Create **scoped ACL tokens** and set **`origins`** exactly as in
   `docs/flespi-setup.md`; stop using the `.env` token in clients.
2. Keep the **receiver token off the browser** (`origins: []`).
3. Optional: **per-person sender tokens** for independent revocation.
4. **Rotate** the sender token if a device is lost (recreate the token).
5. For a real release build, provide a **release keystore**
   (`android/keystore.properties`, gitignored) instead of the debug-key fallback.

## Notes on the test that couldn't run here
The sandbox's headless Chromium cannot open *external* WebSockets (it opens a
localhost WSS broker fine), so the browser→Flespi leg was validated instead with
a Node MQTT client (same protocol/token) and with the page against the local WSS
broker. The browser→Flespi leg must be confirmed in a real browser.
