# 05 — Token & family-onboarding model

Type: grilling
Status: resolved
Blocked by:

## Question

How do the admin and family authenticate, and how do tokens stay safe on a public
static page? Decide: publisher-only restricted token in `localStorage`; a single
shared family token (with a typed sender name) vs per-person tokens; and what the
page shows/does when the token is missing or wrong.

## Answer

- The token is **entered at runtime and kept in the browser `localStorage`** —
  never baked into the page or the repo.
- **One shared publisher token is acceptable** for the family; senders are
  distinguished by the **typed `sender` name** (not authenticated — see
  `SECURITY.md`). Per-person tokens are the upgrade for revocation.
- Scope it: publish-only `grandpa/message`, subscribe `grandpa/status`,
  `origins` locked to the Pages origin (`docs/flespi-setup.md`).
- The page auto-connects when a token is present; otherwise it shows
  "neconectat" until the user connects.
