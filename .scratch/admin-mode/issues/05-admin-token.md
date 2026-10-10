# 05 — Provision the admin token

Type: task
Status: resolved
Blocked by:

## Question

Create the least-privilege token the browser admin mode will use.

## Answer

Created ACL token **`grandpa-admin`** via the Master token:

- `mqtt`: `publish` + `subscribe` on `grandpa/config`;
  `subscribe` on `grandpa/status`.
- `origins: ["https://catalingb.github.io"]` (browser-locked);
  `ttl: 31536000`, `expire: 0`.

Secret saved to `.env.tokens` (gitignored), alongside the receiver + sender
tokens. Never the Master.
