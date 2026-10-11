# Security audit — Grandpa Pager

Date: 2026-10-10 (updated after release hardening and the admin-mode work).
Scope: the Flespi MQTT channel, the Android receiver, the GitHub Pages web
sender **and admin console**, and the tokens.

## Assets
- **The message channel** (`grandpa/message`, `grandpa/status`, `grandpa/config`)
  — private family communication and device configuration.
- **Broker tokens** (Flespi) — the thing that grants access.
- **The web pages** — a *public* static site (GitHub Pages free = public repo).

## What is NOT exposed (verified)
- **No secrets in the repo.** `.env` / `.env.tokens` (Flespi tokens) and the test
  CA private key are in `.gitignore`; a scan of the whole git history found no
  token value. The published site ships code only.
- The web pages **have no token baked in** — the user types it; both the sender and the admin keep it in `localStorage`.

## Findings

1. **The sender token is effectively public.** Any script on the page, or anyone
   with access to the user's browser, can read `localStorage`. *Mitigations:*
   a **scoped ACL** token (publish `grandpa/message`, subscribe
   `grandpa/status`), and **`origins` locked to the Pages origin** so a leaked
   token can't be used from another site. Residual risk is a user's own browser.
2. **Never ship the master token.** The `.env` token is used only offline to mint
   the scoped tokens; keep it off every client.
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
   identification algorithm (Paho's no-arg socket may limit this — see the
   architecture caveat).
9. **Bundled test CA (fixed for release).** The app must not trust the bundled
   test CA (`res/raw/ca.crt`, whose private key lives on the dev machine) in a
   released build. `TlsUtil` trusts it **only in debug builds**; release trusts
   the platform CAs only.
10. **CDN dependency removed.** MQTT.js is **vendored** (`mqtt.min.js`) so the
    pages work even if a CDN is blocked — also removing a third-party script from
    the trust path.
11. **Content-Security-Policy added.** The web sender — which holds a broker
    token — ships a strict CSP (`script-src 'self'`, `connect-src 'self' wss:`,
    `base-uri 'none'`, `form-action 'none'`, `object-src 'none'`).
12. **The admin token is more powerful than the sender token.** It can reconfigure
    the device (display, layout, cues, telemetry, device-owner restrictions, kiosk
    lock), issue device-control commands (`restart_app`, `reboot_device`) and
    request a **guarded broker/credential change** (`set_connection`). It is
    narrowly scoped (pub/sub `grandpa/config`, pub `grandpa/command`, sub
    `grandpa/status`), origin-locked and held in `localStorage` (so the console stays signed in across restarts). It cannot remove
    the device owner or perform a factory reset, and the retained `grandpa/config`
    cannot change broker/credentials — that path is only the guarded command, which
    probes the candidate and **auto-reverts** within the window, and which never
    echoes the password (`status` reports `password_set` only). Residual risk: a
    leaked admin token can strand the device for the revert window and can restart
    or reboot it, so rotate on loss.
13. **The admin page now ships a CSP (fixed).** `admin.js` no longer uses inline
    `onclick`/`oninput` attributes; it dispatches controls through one delegated
    listener, so `admin.html` carries the same strict
    `script-src 'self'` policy (no `'unsafe-inline'`) as the sender.
14. **The receiver runs an end-of-life OS.** Android 9 (Mi A1) is out of security
    support, and the phone is a permanently-connected node. This is contained
    (dedicated SSID/VLAN, no Google account, USB debugging off, egress limited)
    rather than patched. Physical access can still read the receiver token
    (finding 5), so treat a lost device as a compromised token. Full operational
    hardening, including 24/7 battery/thermal/fire safety, is in
    [`docs/device-hardening.md`](docs/device-hardening.md).
15. **Invitation links embed a token in the URL.** These are a convenience
    for onboarding family (sender) and admins. The token is carried in the URL
    **fragment** so it is never sent to the server nor leaked via `Referer`; the
    page reads it and immediately strips it from the address bar, and `sw.js` no
    longer caches URLs that carry a query string. Residual risk: the link itself
    is a secret — whoever holds it can send messages (or, for the admin link,
    reconfigure the device), and it may survive in the sender's
    clipboard/history before the first load. Revoke by recreating the token.

## Recommended actions (priority order)
1. Create **scoped ACL tokens** and set **`origins`** exactly as in
   `docs/flespi-setup.md`; stop using the `.env` token in clients.
2. Keep the **receiver token off the browser** (`origins: []`).
3. Keep the **admin CSP** (`script-src 'self'`) — do not reintroduce inline
   handlers in `admin.html`/`admin.js`.
4. Optional: **per-person sender tokens** for independent revocation; consider a
   **separate admin token per admin**.
5. **Rotate** the sender/admin token if a device is lost (recreate the token).
6. For a real release build, provide a **release keystore**
   (`android/keystore.properties`, gitignored) instead of the debug-key fallback.
7. Follow [`docs/device-hardening.md`](docs/device-hardening.md): isolate the
   receiver on a dedicated network, keep it near 70% charge, and cable-lock it.

## Notes on the test that couldn't run here
The sandbox's headless Chromium cannot open *external* WebSockets (it opens a
localhost WSS broker fine), so the browser→Flespi leg was validated instead with
a Node MQTT client (same protocol/token) and with the page against the local WSS
broker. The browser→Flespi leg must be confirmed in a real browser.

