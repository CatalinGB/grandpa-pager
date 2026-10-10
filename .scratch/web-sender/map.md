# Grandpa Pager — Web Sender — Wayfinder Map

> A new effort, related to (but separate from) the `grandpa-pager` map, which
> built the Android receiver. This map pivots the **sender** to a zero-backend
> static web app. Open tickets are child issues under `issues/`.

## Destination

A zero-backend **static web sender**, served from **GitHub Pages**, that connects
straight to the family MQTT broker over **secure WebSocket** (MQTT.js, `wss://`)
so the admin and family can open the page, log in with a restricted token, type a
message, and publish it to the **already-built Android receiver** on Grandpa's Mi
A1. The Android receiver and its schema are unchanged. (Execution effort.)

## Notes

- **Effort type: EXECUTE** — the owner said "Make the sender a static GitHub
  Pages web app… just open the page, log in, type the message, and publish."
- **Domain**: browser JavaScript, MQTT over WebSocket, GitHub Pages, Flespi /
  EMQX Cloud.
- **Skills each session should consult**: `research`, `prototype`, `grilling`,
  `domain-modeling`.
- **Standing constraints**: free; **private, publisher-only restricted token**
  (never a master token); no secrets in the repo; the payload **must carry
  `sender`** (owner requirement); keep the existing receiver + wire schema.
- **Related map**: `.scratch/grandpa-pager/` — owns the Android receiver and the
  **broker decision (EMQX Cloud Serverless)**; this effort may re-open the broker.
- **Owner-visible discrepancy to settle**: the pasted proposal omits `sender`
  and switches the broker to Flespi.

## Decisions so far

<!-- index: one line per closed ticket; zoom the link for detail -->

- [01 Flespi WSS + ACL token model](issues/01-flespi-wss-acl.md): `wss://mqtt.flespi.io:443`, token-as-username, publish/subscribe-scoped ACL tokens; free tier is dev/test only and deletes inactive accounts after 60 days.
- [02 EMQX Cloud WebSocket support](issues/02-emqx-cloud-wss.md): EMQX Cloud Serverless **already supports WSS** at `wss://<deployment>:8084/mqtt` (username/password) — **no broker switch needed**.
- [03 MQTT.js in the browser](issues/03-mqttjs-browser.md): pin `mqtt@5.16.0/dist/mqtt.min.js`; API unchanged in v5; must use `wss://`.
- [09 Does Flespi fully cover all needs?](issues/09-flespi-coverage.md): Yes functionally (persistent sessions ~10 d, retained, LWT, wildcards, per-token ACLs). No infinite token — max 1 y TTL, but rolling, so one shared token kept alive by the receiver is effectively non-expiring. Free tier is "testing & development".

## Not yet specified

- A static `/display` **web page** as a kiosk-browser fallback for the display
  side (the proposal's step 4) — decide after the sender works.
- **Family onboarding**: one shared family token (sender name typed) vs
  per-person tokens.
- Whether the web sender should **escalate offline alerts** (email/webhook)
  beyond the in-page status.

## Out of scope

- Replacing the Android receiver (owner wants the custom app as the final
  display).
- Any server/backend — the whole point is zero-backend.
- Commercial kiosk / web-kiosk as the final display (the existing map already
  ruled this out).
