# 21 — Notify when a message already exists (option B, non-blocking)

Type: task
Status: resolved
Blocked by: 04

## Question

With multiple senders, tell the sender that a message is already on the screen
before they send (owner: "option B — notify already existing").

## Answer

Implemented in the web sender (non-blocking, no dialog):

- On connect the sender subscribes to the **retained** `grandpa/message`
  (in addition to `grandpa/status`).
- A **banner** shows the current message: *"Pe ecran acum: „…" de la Maria
  (expiră în 2 min). Dacă trimiți, îl înlocuiești."*
- Sending is **not blocked**; last-write-wins still applies.
- If a **different** message id arrives within ~6 s of your send, the banner adds
  *"⚠ Mesajul tău tocmai a fost înlocuit de altcineva."* — the race feedback that
  plain last-write-wins lacked.
- An empty retained payload clears the banner.

Note: showing it requires a token allowed to **subscribe** `grandpa/message`.
