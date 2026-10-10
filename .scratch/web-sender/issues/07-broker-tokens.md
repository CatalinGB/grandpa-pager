# 07 — Create broker account + restricted ACL tokens

Type: task
Status: open
Blocked by:

## Question

Owner-only: create the broker account (Flespi, or EMQX if 06 keeps it) and mint
the restricted tokens — a **publisher-only** token scoped to `grandpa/message`
for the sender, and the display token the Android receiver already needs
(publish `grandpa/status`, subscribe `grandpa/message`). Record where the tokens
live (never in the repo).
