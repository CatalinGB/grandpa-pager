# 11 — Lockdown provisioning verified

Type: task
Status: resolved
Blocked by: 05, 06

## Question

Does device-owner lock task actually work on this unrooted, account-free Mi A1?

## Answer

Yes. `adb shell dpm set-device-owner com.grandpapager/.PagerDeviceAdminReceiver`
succeeded (0 accounts present). The receiver then set lock task packages, removed
lock-task system features, set persistent home and disabled the status bar;
`dumpsys` showed `mLockTaskModeState=LOCKED`. Removal needs the app itself
(`dpm remove-active-admin` refuses a non-test admin), so an admin control
`am broadcast -a com.grandpapager.RELEASE_KIOSK` was added — it stops lock task,
un-whitelists, restores the status bar and calls `clearDeviceOwnerApp`. Verified:
state returned to `NONE`, no device owner, phone usable. Evidence:
`docs/evidence/03-kiosk-idle.png`.
