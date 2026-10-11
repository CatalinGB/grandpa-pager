# Power, idle screen-off, and status/metrics cadence

**Date:** Oct 2026
**Scope:** The receiver role on the Xiaomi Mi A1 (tissot, Android One, **Android 9 / API 28**), permanently plugged in on home Wi-Fi, **device owner** with Lock Task Mode kiosk + persistent HOME. MQTT via Eclipse Paho `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` inside a foreground `Service` (`cleanSession=false`, TLS to Flespi, topics `grandpa/message|command|config|status`). The admin console publishes a versioned `RemoteConfig` to `grandpa/config`. Three questions: (1) can we turn the *screen* off when idle while still guaranteeing an immediate display on arrival; (2) what does the phone actually publish and should the cadence be 15 min and admin-configurable; (3) disabling other device features to save power.

Every non-obvious claim links to the source that owns it: Android developer docs / AOSP at the `android-9.0.0_r61` tag, the OASIS MQTT 3.1.1 spec, and the Eclipse Paho javadoc. Vendor/MIUI behaviour is called out as **unverified**. Code citations are `file:line`.

---

## 1. Idle screen-off with guaranteed immediate display

### Bottom line

- **"Screen fully off *and* CPU suspended *and* 100 % immediate over pure MQTT" is impossible.** An MQTT message can only arrive "immediately" while the TCP/TLS socket and the radio are up; letting the device suspend means the broker queues the QoS 1 message and the phone gets it at the next wake/maintenance window, not instantly. Doze "suspends network access" and "ignores wake locks" ([Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)).
- **The best achievable design** keeps the socket up (partial wake lock + Wi-Fi lock) and turns only the **display/backlight** off while idle; a new message re-lights the screen through a full-screen-intent notification plus `setTurnScreenOn()`/`setShowWhenLocked()`. This delivers "as immediate as the network" — effectively 100 % for a device that is charging and online — at the cost of keeping the Wi-Fi radio and TLS session alive.
- Because the pager is **permanently plugged in**, the real prize of screen-off is **heat and Li-ion ageing**, not runtime (`docs/device-hardening.md`). Disabling the backlight is the single biggest thermal win; keeping the CPU/radio awake costs almost nothing in charge while on mains.
- **No protocol/schema change is required** for this design. The only optional change is a `RemoteConfig` switch to enable/disable idle screen-off (see §2 for the config extension pattern).

### What an app can do to the screen

| Action | API | Availability / permission | Notes |
|---|---|---|---|
| Wake the screen directly (`PowerManager.wakeUp`) | `wakeUp(long)` | **Not available to normal apps** — `@removed Requires signature permission` (`DEVICE_POWER`) | Confirmed in AOSP `android-9.0.0_r61`; the method requires `android.permission.DEVICE_POWER`, which is `protectionLevel="signature"` ([AOSP PowerManager.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/core/java/android/os/PowerManager.java#L924), [AOSP AndroidManifest.xml](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/core/res/AndroidManifest.xml)). Device owner does **not** help. |
| Turn the screen on by showing an activity | `Activity.setTurnScreenOn(true)` | **API 27+** (so OK on API 28) | "the activity will cause the screen to turn on if the activity will be visible and resumed … normally used in conjunction with `showWhenLocked`" ([Activity#setTurnScreenOn](https://developer.android.com/reference/android/app/Activity#setTurnScreenOn(boolean))). |
| Show over the lock screen | `Activity.setShowWhenLocked(true)` | **API 27+** | "shown on top of the lock screen whenever the lockscreen is up and the activity is resumed" ([Activity#setShowWhenLocked](https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean))). |
| Legacy equivalents of the two above | `FLAG_TURN_SCREEN_ON`, `FLAG_SHOW_WHEN_LOCKED` on the window | API 5 / 5, **deprecated in API 27** | `FLAG_TURN_SCREEN_ON`: "once the window has been shown … poke the power manager's user activity … to turn the screen on"; `FLAG_SHOW_WHEN_LOCKED` superseded by `setShowWhenLocked` ([WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_TURN_SCREEN_ON)). |
| Keep the screen on while a message shows | `FLAG_KEEP_SCREEN_ON` | API 1, no permission | "as long as this window is visible … keep the device's screen turned on and bright" ([ref](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON)). The app already sets this unconditionally at `ReceiverActivity.kt:121` — that is why it never sleeps today. |
| Dismiss the keyguard | `KeyguardManager.requestDismissKeyguard(Activity, callback)` | **API 26+** | No permission; needs the activity visible via `setShowWhenLocked` or it fails; if `turnScreenOn` is true the screen turns on when dismissed ([KeyguardManager#requestDismissKeyguard](https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,android.app.KeyguardManager.KeyguardDismissCallback))). Moot here: the kiosk already suppresses the keyguard via Lock Task Mode with `LOCK_TASK_FEATURE_NONE` (`DeviceOwnerManager.kt:42`); `setKeyguardDisabled` (API 23) is an alternative. |
| Launch the activity even from the background | `Notification.Builder.setFullScreenIntent(PendingIntent, true)` | **API 11+**; channel must be `IMPORTANCE_HIGH`; `USE_FULL_SCREEN_INTENT` on target API 29+ | "An intent to launch instead of posting the notification to the status bar… the notification must also be posted to a channel with importance level set to `IMPORTANCE_HIGH` or higher" ([setFullScreenIntent](https://developer.android.com/reference/android/app/Notification.Builder#setFullScreenIntent(android.app.PendingIntent,%20boolean))). This is the system-sanctioned way to start the pager activity when backgrounded. |
| Turn the screen **off** programmatically | `DevicePolicyManager.lockNow()` | **API 8**, device admin; `USES_POLICY_FORCE_LOCK` | "If there's no lock type set, this method forces the device to go to sleep but doesn't lock the device" ([DevicePolicyManager#lockNow()](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#lockNow())). In kiosk (keyguard disabled) this turns the display off without presenting a keyguard. |
| Shorten the idle timeout instead of locking | `DevicePolicyManager.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, ms)` | **API 28**; device owner | Device owner may update `SCREEN_OFF_TIMEOUT` ([setSystemSetting](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setSystemSetting(android.content.ComponentName,%20java.lang.String,%20java.lang.String))). Lets the screen self-off after N seconds without locking. |

**What device owner adds** (vs a normal app): `lockNow()`, `setSystemSetting`/`setSecureSetting`, `setKeyguardDisabled`, and the user restrictions in §3 — but **not** `PowerManager.wakeUp`, which remains signature-only. Device owner does not grant the ability to force-wake the SoC.

### What must stay alive to receive immediately with the screen off

- **Doze** is entered when the device is "unplugged and stationary … with the screen off". In Doze the system "suspends network access", "ignores wake locks", defers standard `AlarmManager` alarms, and does not run `JobScheduler`/`WorkManager`; a **partial battery-optimization exemption** lets an app "use the network and hold partial wake locks during Doze and App Standby" ([Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)). Because the pager is **always charging**, Doze does not normally engage ("connecting a charger … exits Doze").
- **App Standby Buckets** exist from Android 9; the restrictions "apply only while the device is on battery power. While the device is charging, the system doesn't impose these restrictions", and **device owner apps are exempt** from the `restricted` bucket ([App Standby Buckets](https://developer.android.com/topic/performance/appstandby)). Again: charging + device owner means this is not the binding constraint.
- **Foreground service**: the app already runs `PagerService` as an FGS (`PagerService.kt:87`, `foregroundServiceType` not declared, fine on API 28). An app with an FGS "is not considered idle" by App Standby ([Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)).
- **Partial wake lock** (`PowerManager.PARTIAL_WAKE_LOCK`): keeps the CPU running with the screen off so the 30 s MQTT `PINGREQ` and message callbacks execute; ignored during Doze unless the app is battery-exempt ([PowerManager](https://developer.android.com/reference/android/os/PowerManager#PARTIAL_WAKE_LOCK)).
- **Wi-Fi lock** (`WifiManager.createWifiLock(WIFI_MODE_FULL_HIGH_PERF, tag)`, API 12): keeps the radio awake for the socket. On API 34 `WIFI_MODE_FULL_HIGH_PERF` is deprecated in favour of `WIFI_MODE_FULL_LOW_LATENCY` "due to the impact … on power dissipation" — **not a constraint on Android 9** ([WifiManager#WIFI_MODE_FULL_HIGH_PERF](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_HIGH_PERF)).
- **MQTT keepalive**: the socket is kept alive by the protocol keepalive, not by the status heartbeat. Paho is configured with `keepAliveInterval = 30` s (`MqttController.kt:41`); the broker must see a control packet within **1.5 ×** keepalive or it disconnects ([OASIS MQTT 3.1.1 §3.1.2.10](https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Keep_Alive)).

**What is guaranteed vs best-effort:** an FGS + battery-exemption + partial wake lock + Wi-Fi lock + persistent session is *guaranteed-ish* (the platform won't Doze an app on mains) but not literally 100 %: broker outage, TLS failure, OEM process-kill, kernel OOM, or a force-reboot each break "immediate". "100 %" is an operational target, not a platform guarantee.

### Recommended concrete design

1. **Idle:** stop holding `FLAG_KEEP_SCREEN_ON` when there is no active message; optionally call `lockNow()` (device owner) or set a short `SCREEN_OFF_TIMEOUT` so the display sleeps but the device stays awake.
2. **Connection:** hold a `PARTIAL_WAKE_LOCK` and a `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` for the lifetime of `PagerService`; keep the battery-optimization exemption (requested at `SetupActivity.kt:96`) and device-owner status.
3. **Arrival:** on `showMessage`, post a **full-screen-intent** notification on a new **`IMPORTANCE_HIGH`** channel (distinct from the existing `IMPORTANCE_LOW` FGS channel at `PagerService.kt:404`) whose `PendingIntent` starts `ReceiverActivity`; the activity sets `setShowWhenLocked(true)` + `setTurnScreenOn(true)` and holds `FLAG_KEEP_SCREEN_ON` only while a message is displayed.
4. **Cost:** the Wi-Fi radio + TLS session + a 30 s `PINGREQ` is the always-on price. On mains it is negligible in charge; it is why the design is really about **thermal** savings.

### Alternatives considered

- **FCM high-priority data message** is the Google-recommended way to wake a dozing app; it "wakes your app … gives the app temporary access to network services and partial wakelocks, then returns the device … to the idle state" ([Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)). But it adds an FCM backend, which this project deliberately avoids ("no server", `docs/decisions.md` D1) and does not remove the need to wake/display on the phone. Not recommended here.
- **MQTT cannot push to a suspended device** — there is no server-initiated wake in MQTT 3.1.1; the client's persistent session only queues QoS 1/2 while offline and replays on reconnect.

### Schema / protocol impact

None required. Optional: a `RemoteConfig` node (e.g. `power: { screen_off_when_idle: true }`) to let the admin toggle the behaviour — pattern in §2.

---

## 2. Metrics cadence: what the phone sends, and the stale-status line

### What the receiver actually publishes (from the code)

- **Payload:** a `DiagnosticsPayload` JSON (`state`, `sender`, `battery`, `charging`, `battery_temp_c`, `wifi`, `mqtt`, `current_message_id`, `expires_at`, `last_seen`, `app`, effective `config`, `config_applied_at`, `config_error`) — defined at `PagerCore.kt:40-58`, assembled in `PagerService.publishStatus()` (`PagerService.kt:291-311`).
- **Topic / QoS / retain:** `grandpa/status`, **QoS 1, retained** (`PagerService.kt:310` → `MqttController.publish`, `MqttController.kt:91-95`).
- **Cadence:** every **30 s** — `HEARTBEAT_MS = 30_000L` (`PagerService.kt:45`), used by the watchdog which calls `publishStatus()` (`PagerService.kt:319-334`). The same 30 s timer also drives reconnect (`ensureConnected()`), so heartbeat and reconnect are **not** independently configurable today.
- **Also published on events:** on connect/restore (`onReady`, `PagerService.kt:153-161`), on `showMessage` (`:252`), on `clearMessage` (`:274`), and after a remote-config apply (`:234`).
- **Retained semantics:** because it is retained, the broker stores the last status and replays it to any new subscriber ([OASIS MQTT 3.1.1 §3.3.1.3](https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html)). There is **no Last Will** (Flespi rejects a Will on a scoped token; `docs/decisions.md` D11), so `state` never flips to `offline` — staleness is the only offline signal.
- `STATUS_STALE_MS = 90_000L` (`PagerService.kt:46`) is declared but **never used** (dead constant).

### Diagnosis of the admin line `offlinebattery 98%wifi truemqtt trueseen 23576s`

**(a) Why there are no separators.** `renderDevice` builds one line from adjacent `<span>` elements with **no whitespace or separator text between them** (`admin.js:81-85`):

```js
el("devStatus").innerHTML = '<span class="pill …">' + (online ? "connected" : "offline") + '</span>' +
  "<span>battery " + s.battery + "%</span>" + "<span>…°C</span>" + "<span>wifi " + s.wifi + "</span>" + …
```

The **only** spacing is the CSS `gap` on the `.statusline` container (`admin.html:40`, `display:flex; gap:8px; flex-wrap:wrap`). There is no separator character in the DOM, so the text content itself is the run-together string; whenever the `gap` is not honoured (older engine) or the text is read/copied/extracted, the fields collide exactly as observed. Contrast `sender.js`, whose `renderDiag` composes explicit `\n`-separated, **labelled** lines via `textContent` (`sender.js:66-72`) — e.g. `"state: …\nmqtt: …\nbattery: …"`. That is the intended UI style; `admin.js` never implemented it. **The discrepancy is real**: the intended labelled/separated metrics exist only in the sender view, not the admin device panel. `gap` support for flexbox is comparatively recent and not universal ([MDN `gap`](https://developer.mozilla.org/en-US/docs/Web/CSS/gap)), so relying on it as the sole separator is fragile — use explicit separators/labels.

**(b) Why `seen 23576s` (~6.5 h) despite a 30 s heartbeat.** `23576 s ÷ 3600 ≈ 6.55 h`. The admin computes `age = now − last_seen` and shows a status whose last successful publish was ~6.5 h ago; `online = s.state !== "offline" && age < 90` is false, so the pill renders `offline` (`admin.js:78-85`). Since the status is **retained**, the panel immediately shows the last retained value even if the phone is long gone. Plausible causes, each mapped to a source and to the code:

| Cause | Primary source | What the code does |
|---|---|---|
| **Doze** (only if unplugged/screen-off/stationary): network suspended, wake locks ignored, standard alarms deferred | [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby) | The heartbeat is a `Handler.postDelayed` callback (`PagerService.kt:329`), not an `AlarmManager` alarm; it cannot fire while the CPU is suspended. Only expiry uses `setExactAndAllowWhileIdle` (`PagerService.kt:387`). |
| **App Standby bucket throttling** (on battery only) | [App Standby Buckets](https://developer.android.com/topic/performance/appstandby) | Same: alarms/jobs/network throttled; the `Handler` tick is not a system alarm at all. |
| **Missing battery-optimization exemption** | [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby) | Requested once at setup (`SetupActivity.kt:96-108`); the user can decline and it is never re-checked, so `isIgnoringBatteryOptimizations()` may be false. |
| **Wi-Fi off / no route** | — | `publishStatus()` returns early when `mqtt == null` (`PagerService.kt:293`) and `MqttController.publish` silently no-ops when not connected (`MqttController.kt:93`), so no new retained status is written; the broker keeps serving the old one. |
| **Watchdog not running** (process/service killed, or OEM task-killed) | — (vendor) | The watchdog is an in-process `Handler`; if `PagerService` is killed it stops. `START_STICKY` (`PagerService.kt:102`) and `BootReceiver` only cover recreation at boot/update, not a guaranteed prompt restart after a kill. |
| **Paho gave up auto-reconnecting** | [Paho `setAutomaticReconnect`](https://eclipse.dev/paho/files/javadoc/org/eclipse/paho/client/mqttv3/MqttConnectOptions.html) (1 s → doubling to a 2 min cap) | `ensureConnected()` recreates a dead client *only when the watchdog runs* (`PagerService.kt:127-139`); with the watchdog stopped, recovery never happens. |
| **No Last Will** | [OASIS MQTT 3.1.1 §3.1.2.7](https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html) / `docs/decisions.md` D11 | `state` never becomes `offline`; staleness is the only cue, which is exactly what the panel shows. |

**MIUI/Xiaomi "battery saver / autostart" is not the likely cause here and is unverified**: the Mi A1 is **Android One**, not MIUI (`mi-a1-kiosk-lockdown.md`), so MIUI's Autostart/Battery-saver screens do not apply; treat any such advice as **unverified** and prefer the AOSP mechanisms above.

### Should the metrics cadence be 15 min and admin-configurable?

- **15 min is compatible with an always-on socket, but only if the cadence is decoupled from the reconnect watchdog.** The connection is kept alive by the MQTT **keepalive** (`keepAliveInterval = 30` s, `MqttController.kt:41`), not by the 30 s status publish. So slowing *status* publishing to 15 min does not drop the socket. However, today the **same** `HEARTBEAT_MS` drives both the heartbeat and `ensureConnected()` (`PagerService.kt:319-334`); if you simply raise that constant to 15 min you also delay reconnect after a long outage by up to 15 min. Split them: keep a short reconnect watchdog, make the **status interval** configurable.
- **The UIs will mislabel a healthy device as offline.** Both panels hard-code `age < 90` seconds (`admin.js:80`, `sender.js:64`). At a 15 min cadence a healthy device is usually 0–900 s stale, so the threshold must become a function of the configured interval (e.g. `interval × 2 + margin`), and the receiver must publish the interval so the UIs can compute it. This is the main knock-on change.
- **Existing extension point:** `RemoteConfig` (`RemoteConfig.kt:8-20`) + `RemoteConfigCodec.parse` (`RemoteConfig.kt:31-68`). Add e.g. `telemetry: { interval_minutes: 15 }` as a new optional field:
  - model: add `val telemetry: Telemetry = Telemetry()` with `data class Telemetry(val interval_minutes: Int = 15)`; add it to the `RemoteConfig` constructor defaults.
  - codec: `RemoteConfigCodec.parse` reads it with the existing `int(o, "interval_minutes", 15, min, max)` clamp helper (`RemoteConfig.kt:70-75`); suggested bounds **1–1440** (or 1–60 for a pager), default **15**.
  - **Schema compatibility:** `parse` rejects `schema != 1` (returns `null` → `"schema invalid"`, `PagerService.kt:225`) but *ignores* unknown fields and *defaults* missing ones. Therefore an **optional field under schema 1 is backward compatible** (old receivers ignore it; new receivers default to 15 when absent). **Do not bump to schema 2** unless you also add a version-negotiation path, because current receivers would reject the whole config.
  - receiver wiring: use the configured interval for the status timer while keeping the reconnect watchdog separate; include the interval (and/or the computed staleness threshold) in `DiagnosticsPayload` so `admin.js`/`sender.js` can derive `age < threshold` instead of the hard-coded 90 s.

### UI discrepancy to fix

- Intended: separated, labelled metrics (as in `sender.js:66-72`).
- Actual: a single concatenated line of unlabelled spans in the admin panel (`admin.js:76-86`, `admin.html:40`). Add explicit separators/labels (or reuse the sender's labelled multi-line style) rather than depending on CSS `gap`.

---

## 3. Disabling other phone features (device owner, Android 9)

### User restrictions (`DevicePolicyManager.addUserRestriction`)

The device owner can apply `UserManager.DISALLOW_*` restrictions. API levels verified against the [UserManager reference](https://developer.android.com/reference/android/os/UserManager); all listed are ≤ 28 and therefore available on the device.

| Constant | API | Effect |
|---|---|---|
| `DISALLOW_MODIFY_ACCOUNTS` | 18 | No adding/removing accounts |
| `DISALLOW_CONFIG_WIFI` | 18 | No Wi-Fi config changes |
| `DISALLOW_CONFIG_BLUETOOTH` | 18 | No Bluetooth config via Settings |
| `DISALLOW_CONFIG_CREDENTIALS` | 18 | No credential config |
| `DISALLOW_SHARE_LOCATION` | 18 | Cannot turn on location sharing |
| `DISALLOW_CONFIG_LOCATION` | 28 | Cannot enable/disable location providers |
| `DISALLOW_CONFIG_BRIGHTNESS` | 28 | Cannot change brightness |
| `DISALLOW_CONFIG_DATE_TIME` | 28 | Cannot change date/time/timezone |
| `DISALLOW_CONFIG_SCREEN_TIMEOUT` | 28 | Cannot change screen-off timeout |
| `DISALLOW_CONFIG_MOBILE_NETWORKS` | 21 | Cannot configure mobile networks |
| `DISALLOW_CONFIG_TETHERING` | 21 | Cannot use/configure tethering |
| `DISALLOW_CONFIG_VPN` | 21 | Cannot configure VPN |
| `DISALLOW_CONFIG_CELL_BROADCASTS` | 21 | Cannot configure cell broadcasts |
| `DISALLOW_BLUETOOTH` | 26 | Bluetooth disallowed on the device |
| `DISALLOW_APPS_CONTROL` | 21 | Cannot modify apps in Settings/launchers |
| `DISALLOW_UNINSTALL_APPS` / `DISALLOW_INSTALL_APPS` / `DISALLOW_INSTALL_UNKNOWN_SOURCES` | 18 | No install/uninstall/sideload |
| `DISALLOW_PRINTING` | 28 | No printing |
| `DISALLOW_ADJUST_VOLUME` | 21 | Volume locked |
| `DISALLOW_SMS` / `DISALLOW_OUTGOING_CALLS` | 21 | No SMS / no outgoing calls |
| `DISALLOW_DATA_ROAMING` | 24 | No data roaming |
| `DISALLOW_NETWORK_RESET` | 23 | No network reset |
| `DISALLOW_ADD_USER` | 21 | No secondary users |
| `DISALLOW_SAFE_BOOT` | 23 | Blocks power-menu safe mode |
| `DISALLOW_FACTORY_RESET` | 21 | Blocks in-Android reset (recovery wipe still possible) |
| `DISALLOW_MOUNT_PHYSICAL_MEDIA` | 21 | No USB-OTG media |
| `DISALLOW_DEBUGGING_FEATURES` | 21 | No developer/USB-debugging toggle |
| `DISALLOW_SYSTEM_ERROR_DIALOGS` | 28 | Suppresses crash/ANR dialogs |

The already-deployed kiosk set is a subset (`DeviceOwnerManager.kt` currently sets only lock task + persistent HOME + `setStatusBarDisabled`; the restriction recipe is documented in `mi-a1-kiosk-lockdown.md`). A device owner can also pin the stay-awake setting via `setGlobalSetting(..., Settings.Global.STAY_ON_WHILE_PLUGGED_IN, ...)` ([setGlobalSetting](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setGlobalSetting(android.content.ComponentName,%20java.lang.String,%20java.lang.String))). Note `Settings.Global.WIFI_SLEEP_POLICY` "no longer has effect" as of M — use a `WifiLock` instead.

### Turning off sync

- `ContentResolver.setMasterSyncAutomatically(false)` (API 5) turns off global auto-sync; requires `WRITE_SYNC_SETTINGS`, which is `protectionLevel="normal"` in AOSP Android 9, so the app can hold it ([ContentResolver](https://developer.android.com/reference/android/content/ContentResolver#setMasterSyncAutomatically(boolean)), [AOSP AndroidManifest.xml](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/core/res/AndroidManifest.xml)). Per-account: `ContentResolver.setSyncAutomatically(account, authority, false)`.
- `DISALLOW_MODIFY_ACCOUNTS` prevents re-adding accounts. On this device there is **no Google account anyway** (required for `dpm set-device-owner`), so there is little to sync — the dominant effect is disabling/removing sync-capable apps (below).

### Disabling / hiding / suspending apps

| Route | API / permission | Notes |
|---|---|---|
| `DevicePolicyManager.setApplicationHidden(admin, pkg, true)` | **API 21**, device/profile owner | Preferred DO route; package unavailable to the user, data + APK preserved ([setApplicationHidden](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setApplicationHidden(android.content.ComponentName,%20java.lang.String,%20boolean))). |
| `DevicePolicyManager.setPackagesSuspended(admin, names, true)` | **API 24**, device/profile owner | Suspends packages for the user ([setPackagesSuspended](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setPackagesSuspended(android.content.ComponentName,%20java.lang.String[],%20boolean))). |
| `PackageManager.setApplicationEnabledSetting(pkg, COMPONENT_ENABLED_STATE_DISABLED_USER, 0)` | API 1, but requires `CHANGE_COMPONENT_ENABLED_STATE` = **`signature|privileged`** | Code route is `signature|privileged` in AOSP Android 9, so a normal/DO app **cannot** disable arbitrary packages this way; use `setApplicationHidden` in-app ([PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager#setApplicationEnabledSetting(java.lang.String,%20int,%20int)), [AOSP AndroidManifest.xml](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/core/res/AndroidManifest.xml)). |
| Shell: `adb shell pm disable-user --user 0 <pkg>`, `pm hide --user 0 <pkg>`, `pm suspend --user 0 <pkg>` | shell (privileged) | Verified in AOSP `PackageManagerShellCommand.java` (help lists `enable/disable/disable-user/hide/suspend`), which calls `setApplicationEnabledSetting` / `setApplicationHiddenSettingAsUser` ([AOSP](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)). |

### Network / background data

- `NetworkPolicyManager` is **not** in the public reference (no developer.android.com page; it is `@SystemApi`/hidden). `setRestrictBackground` requires the `MANAGE_NETWORK_POLICY` permission, which is `protectionLevel="signature"` in AOSP Android 9 — **not available to a device-owner app**.
- The supported route is the **Data Saver** setting, reachable over shell: `adb shell cmd netpolicy set restrict-background true` and per-UID `cmd netpolicy add restrict-background-blacklist <UID>` (verified in AOSP [`NetworkPolicyManagerShellCommand.java`](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/services/core/java/com/android/server/net/NetworkPolicyManagerShellCommand.java)). In-app there is no public DO equivalent on API 28.
- Background execution: use a foreground service (already) and rely on device-owner + charging exemptions (§1); do not add WorkManager jobs (deferred in Doze).

### Notifications, Bluetooth, location, printing

- **Notifications:** on API 28 there is no per-app "block all notifications" DPC API. Lock task with `LOCK_TASK_FEATURE_NONE` hides the shade/heads-up/status bar for all apps (`mi-a1-kiosk-lockdown.md`; `DeviceOwnerManager.kt:40-43`). Keep the pager's own channel at low importance.
- **Bluetooth:** `DISALLOW_BLUETOOTH` (26) disallows it and `DISALLOW_CONFIG_BLUETOOTH` (18) blocks configuring it. To actually power the radio down, `BluetoothAdapter.disable()` (API 5, `BLUETOOTH_ADMIN`, `protectionLevel="normal"`) still works on API ≤ 32; Device Owner/Profile Owner are explicit **deprecation exemptions** for the API-33 restriction ([BluetoothAdapter#disable()](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#disable())). `Settings.Global.BLUETOOTH_ON` via `setGlobalSetting` has no effect as of M.
- **Location:** `DISALLOW_SHARE_LOCATION` (18) + `DISALLOW_CONFIG_LOCATION` (28); device owner may also force `Settings.Secure.LOCATION_MODE=0` via `setSecureSetting` (device owner is allowed to set `LOCATION_MODE`; `setLocationEnabled` itself is API 30 and unavailable here) ([setSecureSetting](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setSecureSetting(android.content.ComponentName,%20java.lang.String,%20java.lang.String))).
- **Printing:** `DISALLOW_PRINTING` (28).

### MIUI / Xiaomi specifics — **UNVERIFIED**

The Mi A1 (`tissot`) is **Android One / stock AOSP-style**, not MIUI (`mi-a1-kiosk-lockdown.md`; Wikipedia: Xiaomi Mi A1), so MIUI's Autostart, Battery saver, and "restrict background activity" screens generally do not exist on this ROM and no Xiaomi Autostart procedure is required. If a Xiaomi system service is present, its behaviour is vendor-specific and **not** covered by the AOSP sources above; I could not retrieve an authoritative Xiaomi support page to confirm any such step, so treat any MIUI advice as **unverified**.

### Copy-pasteable commands (device owner + ADB)

```bash
PKG=com.grandpapager

# --- in-app (device owner) equivalents, shown for reference ---
# dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
# dpm.addUserRestriction(admin, UserManager.DISALLOW_BLUETOOTH)
# dpm.addUserRestriction(admin, UserManager.DISALLOW_SHARE_LOCATION)
# dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION)
# dpm.addUserRestriction(admin, UserManager.DISALLOW_PRINTING)
# dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
# dpm.setApplicationHidden(admin, "com.google.android.gm", true)

# --- shell (needs ADB before you close it) ---
# Master sync is set from the app via ContentResolver.setMasterSyncAutomatically(false)
# (there is no stable shell key to flip it; read it in-app with getMasterSyncAutomatically()).
# Data Saver / restrict background data
adb shell cmd netpolicy set restrict-background true
adb shell cmd netpolicy get restrict-background
# Hide/disable non-essential apps (examples; confirm names first with `pm list packages`)
adb shell pm list packages | grep -i -E "gmail|calendar|photos|maps|youtube|store|sync"
adb shell pm disable-user --user 0 com.google.android.gm
adb shell pm disable-user --user 0 com.google.android.apps.docs
# Quieten the radios (device owner app can also call BluetoothAdapter.disable())
adb shell svc bluetooth disable                    # AOSP Svc.java BluetoothCommand
```

### Recommended minimal set for an always-on pager

1. Keep the kiosk set already documented (`DISALLOW_FACTORY_RESET`, `DISALLOW_SAFE_BOOT`, `DISALLOW_MOUNT_PHYSICAL_MEDIA`, `DISALLOW_ADJUST_VOLUME`, `DISALLOW_ADD_USER`, `DISALLOW_CREATE_WINDOWS`, `DISALLOW_SYSTEM_ERROR_DIALOGS`, `DISALLOW_DEBUGGING_FEATURES`).
2. Add: `DISALLOW_MODIFY_ACCOUNTS`, `DISALLOW_BLUETOOTH` (+ `BluetoothAdapter.disable()`), `DISALLOW_SHARE_LOCATION`, `DISALLOW_CONFIG_LOCATION`, `DISALLOW_PRINTING`, `DISALLOW_CONFIG_MOBILE_NETWORKS`, `DISALLOW_CONFIG_TETHERING`, `DISALLOW_DATA_ROAMING`.
3. `ContentResolver.setMasterSyncAutomatically(false)`; hide/disable Play Store, Gmail/Calendar/Drive/Photos and other sync-heavy apps (no Google account is present anyway).
4. **Honest impact:** on a plugged-in, kiosk device the dominant draw is the **screen backlight** (see §1), then the Wi-Fi socket. Feature/app disabling is a **second-order** optimisation — mostly a *thermal* and *attack-surface* win, not a charge win. The primary-order levers remain the display and the socket.

---

## Uncertainties / open questions

1. **Exact root cause of the observed 23576 s staleness is not knowable from the code alone** — it depends on device state at the time (was it unplugged, was the exemption granted, was the process killed). The listed causes are the plausible set; confirm with `adb shell dumpsys deviceidle whitelist | grep grandpapager`, `dumpsys activity services PagerService`, and logcat.
2. **`Handler.postDelayed` vs Doze.** The heartbeat only runs while the CPU is awake; there is no `AlarmManager` backstop for status. A future design could add `setExactAndAllowWhileIdle` status pings, but that is capped at once per 9 minutes per app ([Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)).
3. **CSS `gap` support** for flexbox is version-dependent; I did not pin exact browser versions from a primary source. The robust fix is explicit separators, independent of `gap` support.
4. **Flespi keepalive/message-priority semantics** are broker-specific and not covered by the OASIS client spec; confirm what Flespi does with a slow status cadence and whether it has server-side push.
5. **`USE_FULL_SCREEN_INTENT`**: the app targets API 28 so it is not required today, but if `targetSdk` is ever raised (API 29+ requires the permission; API 34+ restricts FSI to calling/alarm apps), a full-screen-intent design must be revisited ([setFullScreenIntent](https://developer.android.com/reference/android/app/Notification.Builder#setFullScreenIntent(android.app.PendingIntent,%20boolean))).
6. **Battery cost of a permanent partial wake lock + high-perf Wi-Fi lock** is not quantified here; on mains it is dominated by heat, but measure `battery_temp_c` before/after enabling it.
7. **MIUI/Xiaomi behaviour is unverified** and likely irrelevant on Android One; no authoritative Xiaomi source was retrievable.
8. **Adding an optional `RemoteConfig` field under schema 1** is backward compatible per the current parser, but the *staleness threshold* coupling in the two web clients must be updated together or the admin/sender views will show false "offline".
