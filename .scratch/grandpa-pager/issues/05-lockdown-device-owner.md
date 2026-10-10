# 05 — Lockdown: device owner + lock task

Type: research
Status: resolved
Blocked by:

## Question

How do we lock the Mi A1 into a single non-escapable app?

## Answer

Research: `.scratch/grandpa-pager/research/mi-a1-kiosk-lockdown.md`.

Mi A1 is Android One / Android 9, **single user, no accounts** → `adb shell dpm
set-device-owner com.grandpapager/.PagerDeviceAdminReceiver` works without a
factory reset (the no-accounts precondition is met).

Recipe (ordered, in `docs/runbook.md`):
1. Provision device owner.
2. `setLockTaskPackages` to allowlist only `com.grandpapager`; app calls
   `startLockTask()`; `setLockTaskFeatures(NONE)` removes power menu, status
   bar, recents, notifications.
3. Pin the app as persistent HOME (`addPersistentPreferredActivity`) so it
   re-arms after boot; keep `BootReceiver` as a backstop.
4. `svc power stayon true` (or `settings put global stay_on_while_plugged_in 7`).
5. `dumpsys deviceidle whitelist +com.grandpapager` and battery-optimization
   exemption for reliable MQTT.
6. Hide/disallow escaping keys; restore path: `dpm remove-active-admin`.

Accepted risk: stock-recovery factory reset (volume+power) still wipes the
device; ADB itself remains a root-ish escape for anyone technical.
