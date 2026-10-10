# 01 — Admin over MQTT + token gating

Type: research
Status: resolved
Blocked by:

## Question

Can a browser admin mode be gated by an MQTT token, and how should the admin
actually configure the system?

## Answer

Research: `.scratch/grandpa-pager/research/admin-over-mqtt.md`.

- A browser over `wss://mqtt.flespi.io:443` with a token-as-username can drive
  **custom-topic** config writes with an **ACL** token. ✅
- Flespi **REST-over-MQTT** (`flespi/rest/{method}/{api-uri}`) can proxy API
  calls, but **platform** operations (tokens, subaccounts) require a **Master**
  token — which must **never** live in a browser; ACL tokens can't be granted
  platform methods at all.
- **Recommended pattern**: the admin browser holds a narrow ACL token
  (`mqtt`: publish `grandpa/config`, subscribe `grandpa/status`, origin-locked)
  and publishes a **versioned config JSON** (retained) to `grandpa/config`; the
  receiver subscribes, validates, applies, and reflects the result in
  `grandpa/status`.
- **Top caveat**: possession = authority. A leaked token in `localStorage` is
  game over for its scope — keep it narrow, `sessionStorage`, origin/IP-locked,
  short TTL, rotatable; never Master.
