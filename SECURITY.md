# Security audit — Grandpa Pager

Date: 2026-10-10. Scope: the Flespi MQTT channel, the Android receiver, the
GitHub Pages web sender, and the tokens.

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
7. **Reconnect after a long outage (bug).** In testing, after the broker was down
   for a period, the Android receiver did **not** auto-recover until the app was
   restarted (ticket 17). A real outage could leave Grandpa's screen stale.
8. **CDN dependency removed.** MQTT.js is now **vendored** (`mqtt.min.js`) so the
   page works even if a CDN is blocked — also removing a third-party script from
   the trust path.

## Recommended actions (priority order)
1. Create **scoped ACL tokens** and set **`origins`** exactly as in
   `docs/flespi-setup.md`; stop using the `.env` token in clients.
2. Keep the **receiver token off the browser** (`origins: []`).
3. Fix the receiver reconnect bug (ticket 17).
4. Optional: **per-person sender tokens** for independent revocation.
5. **Rotate** the sender token if a device is lost (recreate the token).

## Notes on the test that couldn't run here
The sandbox's headless Chromium cannot open *external* WebSockets (it opens a
localhost WSS broker fine), so the browser→Flespi leg was validated instead with
a Node MQTT client (same protocol/token) and with the page against the local WSS
broker. The browser→Flespi leg must be confirmed in a real browser.
