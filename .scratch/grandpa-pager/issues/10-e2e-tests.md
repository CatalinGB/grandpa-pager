# 10 — E2E test suite on the device

Type: task
Status: resolved
Blocked by: 06, 07, 08, 09

## Question

Prove the system end-to-end on the real phone.

## Answer

Ran on the Mi A1 (Android 9) via `adb reverse`. Passed: TLS connect; full-screen
display with sender + countdown; remote clear; 1-minute expiry auto-clear;
reboot recovery with message restore; Last-Will offline on disconnect; sender-app
publish; device-owner kiosk provisioning. Evidence in `docs/evidence/`; results
table in `docs/runbook.md` §4. 8 JVM unit tests pass.
