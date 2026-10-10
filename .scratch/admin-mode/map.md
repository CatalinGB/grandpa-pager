# Grandpa Pager — Admin Mode — Wayfinder Map

> A new effort (spans the GitHub Pages sender + the Android receiver). Open
> tickets are child issues under `issues/`.

## Destination

An **admin mode inside the GitHub Pages app**, unlocked by entering a **scoped
admin MQTT token**, that configures the pager — display, arrival cue, default
duration, and kiosk re-lock/open-launcher — by **publishing a versioned config
the receiver validates and applies**, plus a live read of the device status.
(Execution effort.)

## Notes

- **Effort type: EXECUTE** (owner confirmed scope Q1–Q6).
- **Domain**: static web (GitHub Pages, MQTT.js/WSS) + Android receiver
  (Kotlin, device owner) + Flespi MQTT.
- **Skills each session should consult**: `grilling`, `domain-modeling`,
  `prototype`, `research`.
- **Standing constraints (confirmed)**:
  - Admin is gated by a **narrow ACL token** — **never Master** in a browser.
  - v1 configures: **display, arrival cue, default duration, kiosk re-lock / open
    launcher, read-only diagnostics**.
  - **Broker URL / tokens stay on-device** (5-tap launcher gesture + Setup screen);
    never changed over MQTT.
  - Bad config must be **rejected / self-reverted**; keep the last-good config.
- **Related**: `.scratch/grandpa-pager/` (receiver), `.scratch/web-sender/` (sender).

## Decisions so far

<!-- index: one line per closed ticket; zoom the link for detail -->

- [01 Admin over MQTT + token gating](issues/01-admin-over-mqtt.md): a browser ACL token over WSS can drive custom-topic config; Flespi **platform** ops need Master (never in a browser); possession of the token = its authority.
- [05 Provision the admin token](issues/05-admin-token.md): created `grandpa-admin` (publish/subscribe `grandpa/config`, subscribe `grandpa/status`, origin-locked to the Pages site, TTL 1 y).
- [02 Config schema](issues/02-config-schema.md): v1 = `{schema:1, display, cue, defaults, kiosk}`; receiver clamps bounds, rejects unknown schema, ignores unknown fields, echoes applied config in status.
- [03 Admin UI shape](issues/03-admin-ui.md): variant **A “Secțiuni”** — grouped single-scroll form + live status + JSON preview, gated by `?admin=1` + the `grandpa-admin` token.
- [04 Receiver: apply/validate/ack](issues/04-receiver-apply.md): subscribe retained `grandpa/config`, apply on (re)connect; reject bad `schema`, else clamp/ignore; kiosk via activity; persist + reboot; ack in `status.config` + read-back; receiver token granted `subscribe grandpa/config`.
- [06 Implement admin mode](issues/06-implement.md): shipped `admin.html`/`admin.js` (variant A, token-gated, prefill from status, Apply→`grandpa/config`) + receiver config handler; verified end-to-end on Flespi.

## Built & shipped

- **Admin page**: https://catalingb.github.io/grandpa-pager/admin.html (enter the
  `grandpa-admin` token).
- Receiver applies remote config from `grandpa/config` and acks it in
  `grandpa/status.config`.
- The map is **complete**: all decision tickets resolved; only build work
  remained and it is done.

## Not yet specified

- **Multi-admin & revocation**, and whether the admin token should ever refresh.
- **Config history / audit** of who applied what.

## Out of scope

- **Flespi platform operations from the browser** (managing tokens/subaccounts) —
  Master-only; stays on the PC, never in a page.
- **Changing the broker URL or tokens over MQTT** (kept on-device).
- Per-admin tokens / audit log (v1 uses one `grandpa-admin` token).
