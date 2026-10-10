# Locking down a Xiaomi Mi A1 (tissot, Android One, stock Android 9) as an MQTT pager kiosk

Research date: 2026-10-10. Findings are traced to primary sources: Android
developer documentation, AOSP source at the `android-9.0.0_r61` tag (the exact
platform the Mi A1 ships), and Google/Xiaomi first-party pages. Anything not
backed by a primary source is marked **UNVERIFIED**.

Device under study: **Xiaomi Mi A1, codename `tissot`**, part of the **Android
One** program. It shipped with Android 7.1.2 and was officially updated to
**Android 9 "Pie" (API 28)**; the Mi A1 was the first worldwide Android One
phone and runs an AOSP-style stock ROM with Google apps, not MIUI.
([Wikipedia: Xiaomi Mi A1](https://en.wikipedia.org/wiki/Xiaomi_Mi_A1))
Because the ROM is stock Android One, the AOSP `dpm`, `cmd`, `settings`,
`dumpsys` and `svc` shell tools are all present. This is the single most
important enabler: Xiaomi's *MIUI* phones frequently strip `dpm`, but the A1
does not.

## Bottom line

**Yes — a true, escape-resistant kiosk is feasible on this device** without
root and without a custom ROM, by combining:

1. **Device owner** set once over ADB (`dpm set-device-owner`) from a small
   custom DPC/launcher app.
2. **Lock task mode** (the real kiosk API, not user-exitable screen pinning),
   with the pager app allowlisted and the power/overview/notification UI
   features switched off.
3. The pager app declared as **HOME** and `android:lockTaskMode="if_whitelisted"`
   so it becomes the launcher and re-enters lock task on every boot.
4. A handful of `settings`/`svc`/`dumpsys` one-liners for stay-on, immersive
   mode, and battery-optimisation exemption.

Device owner is what turns "screen pinning that the user can pop out of" into a
lock the user cannot exit. Without it, everything below degrades to
easily-defeated screen pinning / a normal launcher.

---

## 1. Device-owner provisioning WITHOUT factory reset

### 1.1 The command

Android documents the development-time path explicitly:

> While you're still developing, you can set your app as the admin of a fully
> managed device using the Android Debug Bridge (ADB).
> …
> 1. Build and install your device policy controller (DPC) app on the device.
> 2. Check that there are no accounts on the device.
> 3. Run … `adb shell dpm set-device-owner com.example.dpc/.MyDeviceAdminReceiver`
>
> — [Dedicated devices overview](https://developer.android.com/work/dpc/dedicated-devices)
> and [Dedicated devices cookbook → Development setup](https://developer.android.com/work/dpc/dedicated-devices/cookbook)

So the supported production path is *factory reset → QR/NFC enrollment*, but the
**ADB path deliberately bypasses the factory reset** for dev/test.

### 1.2 Exact requirements (from AOSP source, not folklore)

In Android 9 the ADB command path runs
`DevicePolicyManagerService.setDeviceOwner()` →
`enforceCanSetDeviceOwnerLocked()` →
`checkDeviceOwnerProvisioningPreConditionLocked()`.
(`frameworks/base/services/devicepolicy/.../DevicePolicyManagerService.java`
@ `android-9.0.0_r61`, lines ~7867–7903 and ~10952–10996.)

For the **ADB/shell caller** (`isAdb() == true`) the rules are:

| Condition | Result |
|---|---|
| A device owner is already set | fail: *"Trying to set the device owner, but device owner is already set."* |
| The user already has a profile owner | fail |
| User 0 not running | fail |
| `ro.build.characteristics` includes `watch` and the watch is paired | fail (irrelevant here) |
| **If user setup has already completed** and this is **not** a split-system-user build: `getUserCount() > 1` | fail: *"…because there are already several users on the device"* |
| **If user setup has already completed**: accounts present and not "compatible" (`hasIncompatibleAccountsOrNonAdb`) | fail: *"…because there are already some accounts on the device"* |

Crucially, for ADB the check on "user setup completed" only gates the
*account/user-count* checks — it is **not** itself a blocker. That is why
`dpm set-device-owner` works on an already-used phone **as long as there is at
most one user and no (incompatible) accounts**. This matches the independent
Android Enterprise FAQ:

> **ADB command (`dpm set-device-owner`)**: This can set Device Owner on a
> device that has been set up but has no Google accounts or secondary users
> present. However, this is a development and testing tool, not a production
> deployment method.
> — [Jason Bayton, "Can I set Device Owner without a factory reset?"](https://bayton.org/android/android-enterprise-faq/can-i-set-device-owner-without-factory-reset/)

Note the enforcement text is a single fatal branch — if `setActiveAdmin` already
succeeded and `setDeviceOwner` then throws, the command rolls the admin back
(the `runSetDeviceOwner` try/catch in
`DevicePolicyManagerServiceShellCommand.java`). So a failure leaves no half
state.

### 1.3 Does it work on a phone already signed into Google?

**Usually not until you remove the accounts.** A signed-in Google account *is*
an account, so `CODE_ACCOUNTS_NOT_EMPTY` fires. This is the single most common
real-world failure ("Not allowed to set the device owner because there are
already some accounts on the device"). It is not specific to Xiaomi.

There is a narrow escape hatch in the same AOSP code: accounts that advertise
the `android.accounts.account.feature.device_or_profile_owner.ALLOWED` feature
are treated as compatible even after setup, and a **test-only** admin relaxes
the check. In practice:

- **Sign out / remove every account** in Settings → Accounts before running the
  command (sync what you need first).
- **Hidden accounts are the classic trap.** Apps (Telegram, WhatsApp, Viber,
  Samsung, some Google services) register accounts that may not all surface in
  the Accounts screen and can still trip the check. If `set-device-owner` fails
  with "already some accounts" after you removed the visible ones, you are
  hitting hidden accounts and the practical fix is a **factory reset** followed
  by setup *without* signing into any account.
  ([StackOverflow: "Not allowed to set the device owner because there are already some accounts"](https://stackoverflow.com/q/41672825);
  corroborated by the AOSP `hasIncompatibleAccountsOrNonAdbNoLock()` source reading all accounts via `AccountManager.getAccountsAsUser()`.)

### 1.4 The DPC app's prerequisites

The admin component must actually exist and be valid or `setActiveAdmin`
throws before any of the above:

- An app declaring a `DeviceAdminReceiver` subclass.
- `RECEIVE_BOOT_COMPLETED` if you want boot handling.
- The receiver guarded by `android.permission.BIND_DEVICE_ADMIN` and exporting
  an `android.app.action.DEVICE_ADMIN_ENABLED` `<meta-data>` pointing at a
  `res/xml/device_admin.xml` that lists the policies it may use.
- **For later removal over ADB, the app must set `android:testOnly="true"` in
  the manifest and be installed with `adb install -t` (or built as a
  test/debug build).** See §1.5.

### 1.5 Removing the device owner

AOSP shell help for the command states:

> `remove-active-admin` … Disables an active admin, the admin must have declared
> `android:testOnly` in the application in its manifest. This will also remove
> device and profile owners.
> — `DevicePolicyManagerServiceShellCommand.java` help text, and Android 9's
> `cmds/dpm/src/com/android/commands/dpm/Dpm.java`

In Android 9 the implementation calls `forceRemoveActiveAdmin()`, which begins:

```java
if (!isAdminTestOnlyLocked(adminReceiver, userHandle)) {
    throw new SecurityException("Attempt to remove non-test admin "
            + adminReceiver + " " + userHandle);
}
```

(`DevicePolicyManagerService.java` @ `android-9.0.0_r61`, line ~3827.)

Therefore:

```bash
adb shell dpm remove-active-admin --user 0 com.example.grandpa/.GrandpaDeviceAdmin
```

works **only if the admin was installed test-only**. If you distribute a
non-test build, the only removal path is a factory reset (or uninstalling the
whole app, which clears its admin state — also blocked once it is the device
owner, so recovery is effectively a factory reset). **Always keep a test-only
build so you can back out.**

---

## 2. True kiosk: Lock Task Mode vs screen pinning

Primary doc: [Lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode).

Key distinction the docs draw:

> **Key Term:** Lock task mode and screen pinning are sometimes used
> interchangeably. Screen pinning appears similar (and provides an immersive
> experience) but **the person using the device can exit the mode whenever they
> want.**

Lock task mode, when the app is **allowlisted by a DPC**, is not user-exitable:

> Only apps that have been allowlisted by a device policy controller (DPC) can
> run when the system is in lock task mode.

### What changes in lock task mode (AOSP/Google docs)

> - The status bar is blank with notifications and system information hidden.
> - The Home and Overview buttons are hidden.
> - Other apps can't launch new activities.
> - The lock screen (if set) is disabled.

### What device owner unlocks that plain pinning does not

| Capability | Plain `startLockTask()` (no DO) | Device owner + allowlist |
|---|---|---|
| Mode name | Screen pinning | Lock task mode |
| User can unpin/exit | **Yes** (back+overview hold) | **No** |
| Can allowlist other apps | No | Yes (`setLockTaskPackages`) |
| Can pre-choose which UI features stay | No | Yes (`setLockTaskFeatures`, API 28) |
| Can suppress power menu | No | Yes (omit `LOCK_TASK_FEATURE_GLOBAL_ACTIONS`) |
| Can launch *another* app into lock task | No | Yes (API 28, `ActivityOptions.setLockTaskEnabled`) |
| Survives reboot automatically | No | Yes, via home-app + `lockTaskMode` attribute (API 28 supports DO-launched lock task) |

### Allowlisting

```java
dpm.setLockTaskPackages(adminName, new String[] { "com.example.grandpa" });
```

- API level 21+. Only the device owner, an affiliated profile owner, or a
  `MANAGE_DEVICE_POLICY_LOCK_TASK` holder may call it.
- From Android M (API 23) **removing** a package from the list finishes that
  package's locked tasks — this is the remote "unlock" lever.
- Packages sharing a UID with an allowed package are also allowed.

### UI features (`setLockTaskFeatures`) — **API 28, exactly available here**

> Any system features not included in `flags` are implicitly disabled … **By
> default, only `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` is enabled**; all the other
> features are disabled. To disable the global actions dialog, call this method
> omitting `LOCK_TASK_FEATURE_GLOBAL_ACTIONS`.
> — [DevicePolicyManager reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setLockTaskFeatures(android.content.ComponentName,int))

Feature flags: `HOME`, `OVERVIEW`, `GLOBAL_ACTIONS` (power menu), `NOTIFICATIONS`,
`SYSTEM_INFO` (clock/battery), `KEYGUARD`, `NONE`.

For a pager that must be inescapable, call:

```java
dpm.setLockTaskFeatures(adminName, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
```

This hides Home/Overview/status/notifications **and disables the long-press
power menu** (the only feature on by default). `LOCK_TASK_FEATURE_KEYGUARD`
being off means the lock screen stays disabled while locked.

### Starting/entering lock task

- **API 28+:** the DPC can start any app's activity into lock task with
  `ActivityOptions.setLockTaskEnabled(true)`. *(This is the mechanism to re-arm
  after boot.)*
- **Pre-28 fallback:** the app itself calls `Activity.startLockTask()` in
  `onResume()`; if the package is not allowlisted, that call falls back to
  **screen pinning** — a silent downgrade to be aware of.
- **Manifest shortcut:** declaring the activity with
  `android:lockTaskMode="if_whitelisted"` makes the system auto-enter lock task
  when that activity launches (e.g. as HOME).

### Blocking overlays

Other apps can otherwise draw over the kiosk (toasts, dialogs). The DPC blocks
this by adding the `DISALLOW_CREATE_WINDOWS` user restriction, typically in
`onLockTaskModeEntering()`.

---

## 3. Auto-start on boot

Three complementary mechanisms; use all three:

1. **Become the HOME app.** AOSP cookbook "Be the home app":
   > You can set your app as the device's home app so that it's launched
   > automatically when the device starts up.

   The activity declares `android.intent.category.HOME` (plus `MAIN`/`DEFAULT`)
   and the DPC pins it with
   `DevicePolicyManager.addPersistentPreferredActivity()` for the HOME intent
   filter. This makes the pager the launcher and the first thing shown at boot.

   Pure-ADB analogue (works without DO, but the user could change it back):
   ```bash
   adb shell cmd package set-home-activity com.example.grandpa/.KioskActivity
   ```
   (`PackageManagerShellCommand` handles `set-home-activity`.)

2. **`android:lockTaskMode="if_whitelisted"`** on the HOME activity so that
   being launched *as home* immediately enters lock task. This is how the kiosk
   "re-locks" on every boot without user action (API 28 path).

3. **`BOOT_COMPLETED` receiver in the DPC** that re-asserts policy (allowlist,
   features, restrictions) and, if needed, launches the kiosk into lock task
   with `ActivityOptions.setLockTaskEnabled(true)`. Requires
   `RECEIVE_BOOT_COMPLETED`. Note API 28 constrains *background* activity
   starts for apps targeting 28; the HOME-app approach sidesteps this because
   the system itself launches home.

---

## 4. Blocking keys, bars and the power button

| Key / surface | Without device owner | With device owner + lock task |
|---|---|---|
| **HOME** | exits to launcher | hidden/disabled when `HOME` feature off; even if `HOME` on, only allowlisted home opens |
| **RECENTS/Overview** | shows recents | `OVERVIEW` off by default; hidden |
| **BACK** | can pop within app stack | cannot leave the locked task; app should intercept/ignore Back itself |
| **Status bar / notification shade** | pullable | hidden; `setLockTaskFeatures` without `SYSTEM_INFO`/`NOTIFICATIONS` |
| **Navigation bar** | present | hidden in lock task; also `policy_control immersive.full=*` (§5) |
| **Long-press POWER → power/reboot/airplane menu** | shown | **disabled** by omitting `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` |
| **Short-press POWER** | screen off | still turns the screen off (physical key; not blockable). App re-shows on wake |
| **Volume keys** | change volume | `DISALLOW_ADJUST_VOLUME` mutes and blocks |
| **Safe mode** | reachable via power menu | `DISALLOW_SAFE_BOOT` blocks the power-menu path |
| **Recents gesture / assistant** | varies | no gesture nav on this Pie device; assistant needs HOME/long-press which is gone |
| **USB-OTG media** | mountable | `DISALLOW_MOUNT_PHYSICAL_MEDIA` |
| **Accessibility/settings escape** | reachable | settings UI unreachable in lock task |

Relevant user restrictions (all present on Android 9; API levels verified in the
[UserManager reference](https://developer.android.com/reference/android/os/UserManager)):
`DISALLOW_SAFE_BOOT` (API 23), `DISALLOW_FACTORY_RESET` (API 21),
`DISALLOW_MOUNT_PHYSICAL_MEDIA` (API 21), `DISALLOW_ADJUST_VOLUME` (API 21),
`DISALLOW_ADD_USER` (API 21), `DISALLOW_CREATE_WINDOWS` (API 21),
`DISALLOW_DEBUGGING_FEATURES` (API 21), `DISALLOW_CONFIG_WIFI` (API 18),
`DISALLOW_CONFIG_BRIGHTNESS` (API 28), `DISALLOW_INSTALL_APPS` (API 18),
`DISALLOW_MODIFY_ACCOUNTS` (API 18). The AOSP cookbook recommends the kiosk set
`DISALLOW_FACTORY_RESET`, `DISALLOW_SAFE_BOOT`, `DISALLOW_MOUNT_PHYSICAL_MEDIA`,
`DISALLOW_ADJUST_VOLUME`, `DISALLOW_ADD_USER`. Add `DISALLOW_DEBUGGING_FEATURES`
to close the ADB hole after setup.

**What only device owner can do:** everything in the right-hand column above —
allowlisting apps for lock task, choosing lock-task UI features, disabling the
power menu, hiding notifications/system info, setting user restrictions, and
suppressing crash/ANR dialogs (`DISALLOW_SYSTEM_ERROR_DIALOGS`, API 28).

---

## 5. Keep screen on + fullscreen immersive

### Keep screen on

- `svc power stayon` (AOSP `com.android.commands.svc.PowerCommand`):
  ```
  usage: svc power stayon [true|false|usb|ac|wireless]
  ```
  `true` sets `BATTERY_PLUGGED_AC | BATTERY_PLUGGED_USB | BATTERY_PLUGGED_WIRELESS`
  and wakes the screen; it does **not** confirm or require root.
  - `adb shell svc power stayon true` — keep on for any charger.
  - `adb shell svc power stayon ac` — only on AC (a wall charger is AC).
- The underlying setting, writable directly (bitmask: AC=1, USB=2, WIRELESS=4,
  confirmed in `BatteryManager.java` @ android-9.0.0_r61):
  ```bash
  adb shell settings put global stay_on_while_plugged_in 7
  ```
  (`Settings.Global.STAY_ON_WHILE_PLUGGED_IN` = `"stay_on_while_plugged_in"`.)
- Belt-and-braces in the app: `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`
  on the kiosk activity (AOSP cookbook "Keep the screen on").
- Raise the idle timeout so nothing sleeps on brief unplug:
  ```bash
  adb shell settings put system screen_off_timeout 2147483647
  ```

### Fullscreen / hide bars via ADB

AOSP's `PolicyControl` class (Pie) reads
`Settings.Global.POLICY_CONTROL` (`"policy_control"`) and documents:

> to force immersive mode everywhere: `"immersive.full=*"`
> to disable the immersive mode confirmations for specific packages:
> `"immersive.preconfirms=com.package.one,com.package.two"`

So:

```bash
adb shell settings put global policy_control "immersive.full=*:immersive.preconfirms=*"
```

This is the ADB-level fullscreen lever for the idle/launcher screen. In practice
the app should also set `SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
FULLSCREEN | HIDE_NAVIGATION | LAYOUT_*` in `onWindowFocusChanged()`, and lock
task mode already removes the bars.

---

## 6. Battery-optimisation exemption

Doze/App Standby can silence a background MQTT client. Primary doc:
[Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby).

The shell whitelist command lives in `DeviceIdleController` (AOSP Pie). From its
help text:

> `whitelist [package ...]` … Prefix the package with `-` to remove it from the
> … whitelist or `+` to put it back in.

The implementation requires the `DEVICE_POWER` permission (held by `shell`) and
accepts `+pkg` to add:

```bash
adb shell dumpsys deviceidle whitelist +com.example.grandpa
adb shell dumpsys deviceidle whitelist            # list current entries
```

In-app equivalent: request
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, or simply be the device owner
(DO apps are exempt from many power restrictions). The running check is
`PowerManager.isIgnoringBatteryOptimizations()`.

Useful extras:
```bash
# Let the app hold wakelocks / run in background
adb shell cmd appops set com.example.grandpa WAKE_LOCK allow
adb shell cmd appops set com.example.grandpa RUN_IN_BACKGROUND allow
```
**(UNVERIFIED:** exact `appops` op names can differ by build; test on the
device.**)**

Wi-Fi: Android 7+ removed the old `wifi_sleep_policy` knob; Wi-Fi stays up while
the device is charged and awake. Keeping the screen on + charging is the
practical way to keep MQTT connected. Note the Mi A1 on Pie was reported to have
Wi-Fi issues (Netflix/Twitter report), so monitor and reconnect in-app.
(§"Netflix or Twitter not working … Android Pie could be culprit", cited in the
Wikipedia article.)

---

## 7. Notifications and lock screen

- **Lock task with `LOCK_TASK_FEATURE_NONE`** hides the notification shade,
  heads-up notifications and the status bar for *all* apps — this is the
  primary suppression mechanism on Pie. There is no per-app "block all
  notifications" DPC API on API 28 (that arrived later).
- **Lock screen:** lock task disables it by default (`KEYGUARD` feature off).
  Additionally the DPC can call `setKeyguardDisabled(admin, true)` (API 23),
  which behaves like lock type "None"; **it will not take effect if a PIN,
  password or pattern is set**, so the device must have no secure lock. As of
  Android P the call also dismisses a currently shown keyguard.
- `setStatusBarDisabled(admin, true)` (API 23) exists but the reference warns it
  **has no effect in LockTask mode** and does nothing on the lock screen —
  "Using LockTask instead of this method is recommended."

---

## 8. What CANNOT be prevented, even with device owner

These are the realistic limits. None require technical skill except the last.

1. **Hardware force-reboot/power-off.** Holding the power button ~8–10 s
   force-resets the SoC; device owner cannot intercept this. The pager returns
   after reboot (home app + lock task re-arm), so it is a *gap*, not an escape.
2. **Stock recovery factory reset.** Booting the bootloader/recovery with the
   physical **volume + power** combo and choosing "wipe data/factory reset" wipes
   the device regardless of `DISALLOW_FACTORY_RESET` — that restriction only
   blocks the *in-Android* Settings reset. On the Mi A1 the bootloader is
   unlockable by Xiaomi policy, but even a locked bootloader's stock recovery
   generally allows a userdata wipe. **This is the biggest real-world escape
   vector** and only physical possession can do it.
3. **ADB if left enabled.** With USB debugging on and a previously authorised
   computer, anyone can `pm uninstall`, `appops`, or `dpm remove-active-admin`
   (test-only build). Mitigate with `DISALLOW_DEBUGGING_FEATURES`, then
   `settings put global adb_enabled 0` and `adb usb` revoke. After that ADB is
   closed unless the user can reach Developer Options (which lock task hides).
4. **Safe mode.** `DISALLOW_SAFE_BOOT` blocks the power-menu route to safe mode.
   There is no documented hardware key-combo for safe mode on the A1, and in
   safe mode a home-app kiosk would not auto-run — so keep it blocked.
5. **Crash/ANR of the kiosk app.** Set `DISALLOW_SYSTEM_ERROR_DIALOGS` (API 28)
   so the system suppresses the error dialog and restarts the app.
6. **A determined user with physical access + a PC + Google credentials** can
   always factory reset and start over. For a non-technical elderly user this is
   the only meaningful hole, and it does not leak data or bypass the lock in
   place.
7. **You (the operator) lock yourself out.** Keep the test-only build; if you
   set a device owner from a non-test build and lose the app/key, recovery is a
   factory reset.

---

## 9. Copy-pasteable commands + recommended lockdown recipe

Assumes:
- A custom app `com.example.grandpa` with a `DeviceAdminReceiver`
  `com.example.grandpa/.GrandpaDeviceAdmin`, a HOME activity
  `com.example.grandpa/.KioskActivity` with
  `android:lockTaskMode="if_whitelisted"`, and `android:testOnly="true"`.
- The phone has **no Google/other accounts and only the primary user**.

### Phase 0 — prerequisites

```bash
# Install the DPC/kiosk app, test-only so it can be removed later (see §1.5)
adb install -t grandpa-pager.apk

# Confirm camera/security: there must be no accounts
adb shell dumpsys account | grep -i "Accounts:"
# (expect "Accounts: 0")  -- remove accounts in Settings or factory reset if not
```

### Phase 1 — become device owner (once)

```bash
adb shell dpm set-device-owner \
  com.example.grandpa/.GrandpaDeviceAdmin
# Success: Device owner set to package com.example.grandpa
# Active admin set to component com.example.grandpa/.GrandpaDeviceAdmin
```

The app's `DeviceAdminReceiver.onEnabled()` should, at minimum, set the kiosk
user restrictions and the lock-task allowlist + features:

```java
// onEnabled(...)
dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
dpm.addUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME);
dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
dpm.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS);
dpm.addUserRestriction(admin, UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS); // API 28
dpm.setLockTaskPackages(admin, new String[]{ "com.example.grandpa" });
dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
dpm.setKeyguardDisabled(admin, true);

// Pin the kiosk as HOME so it launches at boot and re-enters lock task
IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
home.addCategory(Intent.CATEGORY_HOME);
home.addCategory(Intent.CATEGORY_DEFAULT);
dpm.addPersistentPreferredActivity(admin, home,
    new ComponentName("com.example.grandpa", "com.example.grandpa.KioskActivity"));
```

### Phase 2 — adb one-liners (idempotent; safe to re-run)

```bash
PKG=com.example.grandpa

# Keep screen on while charging (AC/USB/wireless), and a very long idle timeout
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 2147483647

# Fullscreen / immersive, and suppress the immersive "swipe to exit" hint
adb shell settings put global policy_control "immersive.full=*:immersive.preconfirms=*"

# Battery-optimisation exemption (Doze whitelist)
adb shell dumpsys deviceidle whitelist +$PKG

# Make it the home app via ADB too (belt-and-braces with addPersistentPreferredActivity)
adb shell cmd package set-home-activity $PKG/.KioskActivity

# Wake the screen now so it comes up locked
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
```

### Phase 3 — close the back doors (after verifying the kiosk works)

```bash
# Block debugging for all users, then turn ADB off at the framework level
adb shell dpm set-user-restriction DISALLOW_DEBUGGING_FEATURES  # via your DPC preferred
adb shell settings put global adb_enabled 0
# Revoke previously authorised host keys (do this while still connected):
adb shell rm -f /data/misc/adb/adb_keys   # may be blocked on production builds
```

Prefer setting `DISALLOW_DEBUGGING_FEATURES` **from the app** (device owner)
rather than a raw ADB command, since the restriction API is the supported route.

### Phase 4 — recovery (keep this documented)

```bash
# Only works if the admin was installed test-only:
adb shell dpm remove-active-admin --user 0 com.example.grandpa/.GrandpaDeviceAdmin
# then uninstall normally
adb uninstall com.example.grandpa
```

### Handy verification commands

```bash
adb shell dpm list-owners                       # device/profile owners
adb shell dumpsys deviceidle whitelist | grep grandpa
adb shell settings get global policy_control
adb shell settings get global stay_on_while_plugged_in
adb shell cmd package resolve-activity -c android.intent.category.HOME
adb shell dumpsys activity activities | grep -i "lockTask\|grandpa"
```

---

## Risks / what can still escape

- **Factory reset from stock recovery (volume+power)** — the one realistic
  "escape". Device owner restrictions cannot prevent a recovery wipe. Only
  physical control of the phone prevents it.
- **Force reboot via long power-hold** — not preventable; harmless (kiosk
  returns) but expect it.
- **ADB left enabled** — a laptop + one command can uninstall the app or drop
  the device owner (test-only build). Must be closed in Phase 3.
- **Hidden accounts** — the top reason `dpm set-device-owner` fails on an
  otherwise "clean" phone. If it fails after removing visible accounts, factory
  reset and set up with no account.
- **Non-test-only DPC** — cannot be removed over ADB; lockout recovery is a
  factory reset. Always ship/install test-only for this project.
- **App bugs** — an ANR/crash without `DISALLOW_SYSTEM_ERROR_DIALOGS` can strand
  a dialog; with it the system auto-restarts. Test reboot, Wi-Fi loss, broker
  disconnect.
- **Silent downgrade to screen pinning** — if the pager package is ever not on
  the lock-task allowlist when `startLockTask()` runs, the user *can* exit. Assert
  the allowlist on every boot (DPC `BOOT_COMPLETED`).
- **OS updates** — a system update can change behaviour; optionally freeze OTA
  with `setSystemUpdatePolicy` (API 21) during sensitive periods.
- **Physical volume/power keys** always exist as hardware; they only affect
  volume/screen-off, not app exit.

## Recommended approach in one paragraph

Set the pager app up as a **device owner DPC that is also the HOME app**,
installed **test-only**, then flip it on once with
`adb shell dpm set-device-owner`. Allowlist only the pager for lock task and set
`LOCK_TASK_FEATURES_NONE` so the power menu, status bar, notifications,
recents and home are all gone; pin the app as persistent HOME so it re-enters
lock task on every boot. Add the stay-on / immersive / doze-whitelist
one-liners, then turn off debugging (`DISALLOW_DEBUGGING_FEATURES` +
`adb_enabled 0`). Keep the test-only build so you can `dpm remove-active-admin`
if needed. The residual hole is a stock-recovery factory reset, which no
software policy can prevent.

---

## Sources

- Android Developers — [Dedicated devices overview](https://developer.android.com/work/dpc/dedicated-devices) (last updated 2026-03-05)
- Android Developers — [Lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode)
- Android Developers — [Dedicated devices cookbook](https://developer.android.com/work/dpc/dedicated-devices/cookbook) (home app, kiosk restrictions, keep screen on, dev-setup `dpm set-device-owner`)
- Android Developers — [DevicePolicyManager reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager) (`setLockTaskPackages` API 21, `setLockTaskFeatures` API 28, `setStatusBarDisabled` API 23, `setKeyguardDisabled` API 23, feature/restriction semantics)
- Android Developers — [UserManager reference](https://developer.android.com/reference/android/os/UserManager) (restriction names + API levels)
- Android Developers — [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) (deviceidle test/whitelist, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
- Android Developers — [Settings.Global reference](https://developer.android.com/reference/android/provider/Settings.Global) (`STAY_ON_WHILE_PLUGGED_IN` = `stay_on_while_plugged_in`, `ADB_ENABLED`, `DEVICE_PROVISIONED`, `POLICY_CONTROL`)
- Android Developers — [Settings.Secure reference](https://developer.android.com/reference/android/provider/Settings.Secure) (deprecated `ADB_ENABLED` → Global)
- AOSP @ `android-9.0.0_r61` — `services/devicepolicy/.../DevicePolicyManagerService.java` (`setDeviceOwner`, `enforceCanSetDeviceOwnerLocked`, `checkDeviceOwnerProvisioningPreConditionLocked`, `hasIncompatibleAccountsOrNonAdbNoLock`, `forceRemoveActiveAdmin`)
- AOSP @ `android-9.0.0_r61` — `cmds/dpm/src/com/android/commands/dpm/Dpm.java` and (main) `DevicePolicyManagerServiceShellCommand.java` (`set-device-owner`, `remove-active-admin`, test-only requirement)
- AOSP @ `android-9.0.0_r61` — `cmds/svc/src/com/android/commands/svc/PowerCommand.java` (`svc power stayon [true|false|usb|ac|wireless]`)
- AOSP @ `android-9.0.0_r61` — `services/core/java/com/android/server/DeviceIdleController.java` (`dumpsys deviceidle whitelist +pkg`)
- AOSP @ `android-9.0.0_r61` — `services/core/java/com/android/server/policy/PolicyControl.java` (`policy_control` `immersive.full=*`, `immersive.preconfirms`)
- AOSP @ `android-9.0.0_r61` — `services/core/java/com/android/server/pm/PackageManagerShellCommand.java` (`set-home-activity`), `core/java/android/os/BatteryManager.java` (plug bitmasks)
- Jason Bayton — [Can I set Device Owner without a factory reset?](https://bayton.org/android/android-enterprise-faq/can-i-set-device-owner-without-factory-reset/) (ADB path needs no accounts / one user)
- StackOverflow — ["Not allowed to set the device owner because there are already some accounts on the device"](https://stackoverflow.com/q/41672825) (hidden-account failure)
- Wikipedia — [Xiaomi Mi A1](https://en.wikipedia.org/wiki/Xiaomi_Mi_A1) (codename `tissot`, Android One, Android 7.1.2 → 9 Pie)
