# 07 — Receiver app

Type: task
Status: resolved
Blocked by: 01, 03, 04

## Question

Build the always-on receiver: connect over TLS, display full-screen with sender,
enforce expiry, publish diagnostics, survive reboot.

## Answer

Implemented in `ReceiverActivity` + `PagerService` (foreground). Verified on the
Mi A1: TLS connect via `adb reverse`, full-screen "From: <sender> / <text> /
clears in …", remote clear, 1-minute expiry auto-clear at `expires_at`, and
reboot recovery (BootReceiver restarts, message restored). Diagnostics + Last
Will exercised. See `docs/runbook.md` §4 and `docs/evidence/`.
