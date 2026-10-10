# 06 — Implement admin mode

Type: task
Status: resolved
Blocked by: 02, 03, 04

## Question

Build it: the token-gated **admin section** in the GitHub Pages app (config form
+ Apply + live status) and the **receiver config handler** (subscribe
`grandpa/config`, validate, apply, persist, ack in `grandpa/status`, self-revert),
wired to the `grandpa-admin` token.

## Answer

Shipped.

**Web** — `admin.html` + `admin.js` (variant A, reachable via an **Admin** link on
the sender): enter the `grandpa-admin` token (kept in `sessionStorage`), the form
(display/cue/defaults/kiosk) **prefills from `grandpa/status.config`**, **Apply**
publishes the versioned config (retained) to `grandpa/config`, with a live device
status line and a JSON preview. Gated and not linked publicly except the Admin
link.

**Receiver** — `RemoteConfig`/`RemoteConfigCodec` (defensive parse + clamp, reject
bad `schema`), `PagerService` subscribes `grandpa/config` and applies, persists,
and **acks + echoes** the effective config in `grandpa/status.config`;
`ReceiverActivity` applies display size / orientation / countdown / cue / kiosk
(`locked:false` → stop lock task + open launcher). Receiver token granted
`subscribe grandpa/config`.

**Also fixed along the way**: Paho needs the socket factory's **no-arg
`createSocket()`**; `TlsUtil` is now wrapped so TLS connects reliably (this had
briefly broken the receiver's connection).

**Verified on Flespi**: published `{name_size_sp:90, countdown:true, torch.enabled:false,
defaults.duration_minutes:120, …}` → receiver logged `config applied`, `status.config`
reflected it (`config_applied_at` set, no error), and the phone rendered the larger
name + visible countdown.

