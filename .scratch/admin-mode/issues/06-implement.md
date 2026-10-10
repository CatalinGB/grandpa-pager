# 06 — Implement admin mode

Type: task
Status: open
Blocked by: 02, 03, 04

## Question

Build it: the token-gated **admin section** in the GitHub Pages app (config form
+ Apply + live status) and the **receiver config handler** (subscribe
`grandpa/config`, validate, apply, persist, ack in `grandpa/status`, self-revert),
wired to the `grandpa-admin` token.
