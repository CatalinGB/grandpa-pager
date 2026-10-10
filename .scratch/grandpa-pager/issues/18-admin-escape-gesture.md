# 18 — Admin escape gesture (switch to launcher)

Type: task
Status: resolved
Blocked by: 05

## Question

As the admin, from the kiosk app, switch back to the original launcher without
re-provisioning.

## Answer

**Five taps in the bottom-left corner** (within 6 s) call `stopLockTask()` and
launch the system launcher (`ReceiverActivity.dispatchTouchEvent` +
`switchToLauncher`). The device owner is kept, so pressing Home returns to the
pager — a temporary admin escape, not a removal.

Verified on the Mi A1: 5 taps → `ReceiverActivity: admin gesture: switching to
launcher` → foreground becomes `com.android.launcher3`.

Note: the receiver is **not** orientation-locked — it respects rotation; the
gesture reads the live window size (`window.decorView.width/height`) so the
corner is correct in both portrait and landscape.
