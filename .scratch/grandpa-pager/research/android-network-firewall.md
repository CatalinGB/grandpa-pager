# Locking a device-owner kiosk's network to a single outbound MQTT connection

Research date: 2026-10-10. Findings traced to primary sources: project repos /
F-Droid build metadata / release pages, and AOSP source at the
`android-9.0.0_r61` tag (the exact platform the Xiaomi Mi A1 runs). Anything not
backed by a primary source is marked **UNVERIFIED**.

Device under study: **Xiaomi Mi A1 (`tissot`), Android 9 / API 28**, unrooted,
already a **device-owner kiosk** running one app that connects outbound over TLS
to `mqtt.flespi.io:8883`. Goal: allow only that MQTT connection, block
everything else, survive reboot, and not break the kiosk.

Companion notes: `mi-a1-kiosk-lockdown.md` (device owner + lock task),
`android-mqtt-client-reliability.md`, `mqtt-broker-options.md`.

---

## Bottom line

**Use NetGuard** (`eu.faircode.netguard`, GPLv3, no root) in **whitelist mode**
(block all, allow only `com.grandpapager`), then have the **existing device
owner DPC enforce it**: set NetGuard as the **always-on VPN** and add the
`DISALLOW_CONFIG_VPN` user restriction so the kiosk user cannot disable it. No
root is required. NetGuard does everything this case needs *except* port-level
rules (it can allow a host/address, not a specific port); since the only allowed
app is the pager, host-level is redundant hardening, not a requirement.

There is **no device-owner API to restrict a single app's network** on Android 9
(verified in AOSP, §4). The closest a DPC can get on its own is to *force all
traffic through a VPN app* it chooses — which still needs a VPN/firewall app.

**Biggest caveat:** always-on VPN with the "Block connections without VPN"
(lockdown) sub-option is documented by NetGuard to break DNS resolution on
Android 8+, which includes this device; test it, and be ready to run always-on
**without** lockdown (accepting a short pre-VPN boot window).

---

## 1. Comparison of the open-source options

All four are installable as plain APKs (F-Droid), no Google account required.
"Local VPN" = uses Android's `VpnService` on-device (no traffic leaves the
device); only one app may hold the VPN at a time on Android.

| | **NetGuard** | **Rethink DNS + Firewall** | **TrackerControl** | **AFWall+** |
|---|---|---|---|---|
| Package | `eu.faircode.netguard` | `com.celzero.bravedns` | `net.kollnig.missioncontrol.fdroid` | `dev.ukanth.ufirewall` |
| License | GPL-3.0-only | Apache-2.0 | GPL-3.0-only | GPL-3.0 |
| Latest / date (2026) | **2.337**, 2026-08-03 | **v0.5.7**, 2026-09-22 | **2026.08.05**, 2026-08-10 | **4.1.0**, 2026-08-06 |
| Maintenance | Active (Marcel Bokhorst / M66B) | Very active (celzero) | Active (Konrad Kollnig, research project) | Active (ukanth) |
| Needs **root**? | **No** — local VPN | **No** — local VPN | **No** — local VPN | **Yes** — iptables (Magisk/SuperSU/LineageOS su) |
| Allow **only one app** (block all others) | **Yes** — whitelist mode (`Settings > Defaults`: enable *Block Wi-Fi* + *Block mobile*, then allow the one app) | Partial — per-app *firewall* blocks individual apps + domain/IP denylists; no single documented global "whitelist, allow one" switch | No clean per-app allowlist; model is **tracker** blocking per app (fork of NetGuard) | **Yes** — per-app allow/block, plus custom iptables |
| Whitelist a specific **host** | **Yes** (PRO: allow/block individual addresses per app) | **Yes** — domain/IP denylists and per-IP rules | Tracker-domain based | **Yes** (custom rules, host/IP) |
| Whitelist a specific **port** | **No** — author explicitly declines address/port ranges (FAQ 41) | **No** — domain/IP, not ports | No | **Yes** — arbitrary iptables (`--dport 8883`) |
| Autostart after reboot | Yes via `RECEIVE_BOOT_COMPLETED`, **if** powered off with it enabled; Xiaomi/MIUI can block autostart (FAQ 38, 50). Always-on VPN (set by DPC) persists after reboot | Yes (`RECEIVE_BOOT_COMPLETED`); also always-on VPN capable | Yes (`RECEIVE_BOOT_COMPLETED`) | Boot rule manager applies rules before apps start |
| Persistent notification / VPN key | Its own foreground notification **cannot** be removed without instability; the Android **VPN key icon is system-managed and cannot be removed** (FAQ 24); both hidden by the kiosk's `LOCK_TASK_FEATURE_NONE` | Foreground notification + system VPN key | Foreground notification + system VPN key | Notification (Android may add a permanent one; not a VPN key) |
| Fit for this case | **Best** | Good alternative | Poor (tracker model) | Excluded (needs root) |

### NetGuard specifics (primary: README + FAQ.md)
- No root; makes an on-device sinkhole/filter using `VpnService`; no external
  server (FAQ 6).
- **Whitelist mode** is the intended "block everything, allow favourites"
  mechanism (FAQ 0, FAQ 25).
- **Autostart** on boot if it was enabled before shutdown and not on external
  storage (FAQ 50); **always-on VPN** supported on Android 7+ (FAQ 1).
- **Lockdown mode** blocks all traffic except apps marked "Allow in lockdown
  mode" (FAQ 52) — useful as a profile, not needed if whitelist mode is used.
- Cannot be used alongside another VPN (only one `VpnService`); Android
  **cannot remove the VPN key icon** (FAQ 2, 24).
- **No wildcards/address-port ranges** (FAQ 41) → cannot restrict to port 8883.
- F-Droid builds exist but the author "does not support" them (FAQ 15);
  GitHub and Play are the author's channels. F-Droid build is current (2.337).

### Rethink DNS + Firewall specifics (primary: README, docs.rethinkdns.com/firewall)
- Per-app firewall (block an app over Wi-Fi/mobile), plus block-by-category,
  block-in-background, block-when-locked, and **per-IP** block; DNS
  blocklists/allowlists. On Android 9 and below it reads `procfs` for per-app
  mapping (README).
- Apache-2.0, actively developed, F-Droid build current. Larger APK (~30 MB).
- Not a clean "allow one app" whitelist and no port rules — workable but more
  fiddly than NetGuard for this exact job.

### TrackerControl specifics (primary: README, F-Droid)
- Explicitly a **tracker/ad** monitor and blocker built on the NetGuard engine;
  three blocking modes (Minimal/Standard/Strict) are **tracker-list** based, not
  a per-app network allowlist. Not a fit for "allow only this one app"; left in
  the table for completeness.

### AFWall+ specifics (primary: README, releases)
- **Requires root** ("no root = no functionality"); iptables-based; supports
  custom `iptables` rules (e.g. `--dport 443`), per-app, per-network-type, boot
  protection. Would give true host/port control — but the device is unrooted, so
  it is **out of scope** here.

---

## 2. Kiosk interaction

**Can the firewall's VPN service run while the pager app is in lock task?**
Yes. Lock-task mode constrains **activities** (which app can be on screen:
"Other apps can't launch new activities"), not background/foreground
**services**. The VPN runs as a `VpnService` owned by the firewall app and is
unaffected by another package being in lock task. See the Lock task mode doc
(cited in `mi-a1-kiosk-lockdown.md`).

**Do we need to whitelist the firewall app out of lock task?**
- It does **not** need to be allowlisted to *run* — services run regardless.
- It **does** matter for the one-time setup: the first VPN "Connection request"
  consent dialog is a system UI (`com.android.vpndialogs`) that lock task /
  `DISALLOW_CREATE_WINDOWS` can suppress. Avoid this entirely by configuring the
  VPN from the device owner with `setAlwaysOnVpnPackage`, which the AOSP doc says
  is **"automatically granted and persisted after a reboot"** (no consent
  dialog). Set it up before/outside lock task regardless.
- Leaving the firewall app **off** the lock-task allowlist is the safer choice:
  the kiosk user then cannot reach its UI even if it had a launcher icon.

**Conflict with the always-on foreground MQTT service?**
No conflict. The pager's socket is just another app's traffic flowing through the
firewall's local VPN; it is allowed because the pager's UID is allowlisted. (The
only way it could bypass the firewall is if the MQTT client itself called
`VpnService.protect()` or bound to a specific network — normal clients don't.)
The pager app and firewall app run as independent services; both can be
foreground services at once.

**Persistence after reboot:** NetGuard's own `RECEIVE_BOOT_COMPLETED` autostart
exists, but a **DPC-set always-on VPN is stronger** — Android itself starts the
always-on VPN, independent of the app's own boot receiver, and (per the
`setAlwaysOnVpnPackage` doc) it "is automatically granted and persisted after a
reboot."

---

## 3. Device-owner (`dpm`) native options — can we skip a third-party app?

**No per-app network restriction API exists on Android 9.** Reading the AOSP
platform interface at `android-9.0.0_r61`
(`frameworks/base/core/java/android/app/admin/DevicePolicyManager.java`), the
**only** network-related levers are:

1. **`setAlwaysOnVpnPackage(admin, vpnPackage, lockdownEnabled)`** (API 24) —
   `DevicePolicyManager.java` line ~4471. Lets the DPC force all traffic through
   a named VPN app and, with `lockdownEnabled=true`, "disallow networking when
   the VPN is not connected." **This still requires a VPN app** (must declare a
   `VpnService` with `BIND_VPN_SERVICE`, target API 24+, and not opt out of
   always-on). It **does not firewall per app** — the per-app policy is the
   firewall app's job. This is the key enabler: a DPC can *lock the firewall on*.
2. **`setRecommendedGlobalProxy(admin, proxyInfo)`** (API 24) — device owner
   only, but the doc states: "This proxy is **only a recommendation** and it is
   possible that some apps will ignore it." HTTP(S) proxy only; not a blocker.
3. **`setGlobalProxy(admin, proxy, exclusions)`** — **`@hide` in API 28**
   (`DevicePolicyManager.java` line ~3488, annotated `@hide`), so a normal
   third-party DPC cannot call it. Not available to us.
4. **`setNetworkLoggingEnabled(admin, enabled)`** (line ~8935) — logging only,
   not enforcement.

The **`UserManager` user restrictions** (AOSP
`core/java/android/os/UserManager.java`) that touch the network are all
**device-level configuration locks**, none of which restrict a *specific app's*
network:
`DISALLOW_CONFIG_WIFI` (`no_config_wifi`), `DISALLOW_CONFIG_MOBILE_NETWORKS`
(`no_config_mobile_networks`), `DISALLOW_CONFIG_TETHERING`,
`DISALLOW_AIRPLANE_MODE`, `DISALLOW_DATA_ROAMING`, `DISALLOW_NETWORK_RESET`, and
`DISALLOW_CONFIG_VPN` (`no_config_vpn`).

**`DISALLOW_CONFIG_VPN` is the one we want.** Its AOSP doc says it "prevents VPNs
from starting. **However, in Android 7.0 (API 24) or higher, the system does
start always-on VPNs created by the device or profile owner.**" So:
`setAlwaysOnVpnPackage(NetGuard, lockdown?)` **+** `DISALLOW_CONFIG_VPN` gives us
a firewall the kiosk user cannot start, stop, or reconfigure — while the DPC's
own always-on VPN still runs.

**Conclusion:** a device owner **cannot** do per-app network firewalling on its
own, and **cannot** do it "without a third-party app." What it *can* do is
*enforce and lock* a third-party firewall. So the answer is a hybrid: NetGuard
does the filtering; the DPC makes it mandatory and undisableable.

---

## 4. Installation and configuration without heavy tapping

- **APK availability / `adb install`:** all candidates ship as F-Droid APKs, no
  Google account required.
  - NetGuard `eu.faircode.netguard` v2.337 (2026-08-03), **2.7 MiB**, ABIs
    `arm64-v8a armeabi-v7a x86 x86_64` (Mi A1 = arm64). Download:
    `https://f-droid.org/repo/eu.faircode.netguard_2026080101.apk`. Note the
    author does not support F-Droid builds (FAQ 15) but the build is current and
    signed by F-Droid.
  - Rethink v0.5.7 (2026-09-22), ~30 MiB; TrackerControl 2026.08.05, 12 MiB.
  - Install with `adb install -t <apk>` (same as the DPC/kiosk app in
    `mi-a1-kiosk-lockdown.md`).
- **Configuring the allowlist without heavy tapping:** none of these apps exposes
  a config file, CLI, or device-owner hook, and their prefs live in
  `/data/data/<pkg>/shared_prefs/` (not writable without root). So the allowlist
  **must be tapped in-app** — but it is light:
  - NetGuard: `Settings > Defaults` → enable *Block Wi-Fi* and *Block mobile*
    (whitelist mode), then tap the pager app once to allow it; optionally enable
    *Manage system apps* so system components are also denied. A handful of taps,
    once.
  - Use NetGuard's **export/import** (FAQ 33, 44) to snapshot the config for
    re-provisioning; import is still via the in-app file picker.
  - Do this **before** entering full lock task, since the VPN consent dialog and
    app UI must be reachable; with a DPC always-on VPN the consent is granted
    automatically.

---

## 5. Risks and limits

- **Early-boot window.** A VPN-based firewall "can only start when Android
  allows it," so without always-on there is no protection during early boot-up
  (NetGuard FAQ 1). Mitigated by making it the **DPC always-on VPN**, which
  Android starts very early; optionally add lockdown to hard-block until it is up.
- **Always-on lockdown vs DNS (the big one).** NetGuard explicitly warns: on
  Android 8+ **do not** enable always-on VPN's "*Block connections without VPN*"
  sub-option, because it "will block resolving domain names too" (FAQ 1, 51).
  This device is Android 9. **Test** whether to use `lockdownEnabled=true`; if
  MQTT/DNS breaks, fall back to always-on **without** lockdown and accept a short
  pre-VPN leak.
- **VPN lifecycle restarts.** The VPN service restarts when connectivity changes
  or the screen turns on/off (to apply conditions) — during the restart,
  allowed/blocked rules are briefly not applied unless *Seamless VPN handover on
  reload* is enabled (which "does not work properly on all Android versions"
  and can hang; FAQ 1, 9). Keep NetGuard conditions simple (avoid
  screen-on/roaming rules) for a fixed kiosk.
- **Battery.** Local VPN filtering adds CPU overhead; the network-speed-graph
  notification and logging add more (FAQ 4). This is a **stay-on, plugged-in**
  kiosk, so battery is a non-issue; keep IP filtering on and logging off.
- **User disabling it.** On a lock-task kiosk the Settings UI is unreachable, and
  `DISALLOW_CONFIG_VPN` prevents VPN configuration changes; the DPC-set
  always-on VPN restarts even if the app is killed. Very low risk once the DPC
  policy is applied. (Keep NetGuard off the lock-task allowlist so its UI is
  unreachable.)
- **Non-app network / captive portal / OTA.** The VPN captures app TCP/UDP/ICMP.
  In whitelist mode (with *Manage system apps* on) the system updater and other
  components are denied → OTA is blocked. Google connectivity/captive-portal
  checks are allowed by a NetGuard predefined rule (FAQ 28); if you deny them the
  status bar may show a "!" but the kiosk is on a known fixed Wi-Fi. All other
  protocols are dropped and shown as blocked (FAQ 27).
- **Only one VPN at a time.** Android forbids two `VpnService` apps; you cannot
  also run a real VPN (FAQ 2). Fine here.
- **Port precision.** NetGuard/Rethink cannot restrict to port 8883. Because the
  pager is the **only** allowed app, its own TLS connection to
  `mqtt.flespi.io:8883` is the only outbound path in practice; host-level
  restriction is redundant hardening. True port pinning needs root (`AFWall+`),
  which this device does not have.
- **Xiaomi autostart.** MIUI-style ROMs can block app autostart; the A1 is
  Android One (stock), and using DPC always-on VPN sidesteps this (FAQ 38, 50).

---

## 6. Recommendation and concrete steps

**Recommendation:** NetGuard, in whitelist mode, enforced as the device-owner
always-on VPN, with `DISALLOW_CONFIG_VPN`. No root.

Concrete steps (run in this order; the DPC app already exists per
`mi-a1-kiosk-lockdown.md`):

1. **Install NetGuard over adb** (Mi A1 is arm64):
   ```bash
   curl -LO https://f-droid.org/repo/eu.faircode.netguard_2026080101.apk
   adb install -t eu.faircode.netguard_2026080101.apk
   ```
2. **While outside lock task**, open NetGuard and:
   - Grant the VPN connection request once.
   - `Settings > Defaults`: enable **Block Wi-Fi** and **Block mobile**
     (whitelist mode). Enable **Manage system apps** (advanced) so system
     components are denied too.
   - Allow only `com.grandpapager` (tap its Wi-Fi + mobile icons to "allowed").
   - Enable *Disable on call* if needed; keep conditions to a minimum.
   - (Optional PRO) add an address rule allowing `mqtt.flespi.io`.
   - Export the settings as a backup.
3. **Force it on from the DPC** (device owner), in `onEnabled`/a boot receiver:
   ```java
   // Always-on VPN, made mandatory
   dpm.setAlwaysOnVpnPackage(admin, "eu.faircode.netguard", /*lockdown=*/true);
   // Prevent the kiosk user from starting/stopping/reconfiguring any VPN
   dpm.addUserRestriction(admin, UserRestrictions.DISALLOW_CONFIG_VPN); // "no_config_vpn"
   // Optional hardening: keep NetGuard un-uninstallable
   dpm.setUninstallBlocked(admin, "eu.faircode.netguard", true);
   ```
   If `lockdown=true` breaks DNS/MQTT on this Android 9 device (the §5 caveat),
   re-call with `lockdown=false` and rely on whitelist mode + always-on.
4. **Do not add NetGuard to the lock-task allowlist** — it must run as a VPN
   service, not as an on-screen app; leaving it off keeps its UI unreachable.
5. **Verify** (outside lock task, over adb):
   ```bash
   adb shell dumpsys package eu.faircode.netguard | grep -i always   # if available
   adb shell settings get global always_on_vpn_app
   adb shell settings get global always_on_vpn_lockdown
   adb shell dumpsys user | grep -i no_config_vpn
   # Functional: confirm the pager still reaches mqtt.flespi.io:8883 and that a
   # browser/other app has no connectivity; then reboot and re-check.
   ```
   (Exact `settings` keys are **UNVERIFIED** on Android 9 — confirm with
   `adb shell settings list global | grep -i vpn`.)
6. **Then close ASB/debugging** as in `mi-a1-kiosk-lockdown.md` Phase 3.

Fallback if NetGuard's whitelist+always-on proves unreliable on this ROM:
**Rethink DNS + Firewall** (Apache-2.0, very active), per-app firewall with
domain/IP rules, enforced the same way via `setAlwaysOnVpnPackage` +
`DISALLOW_CONFIG_VPN`.

---

## Sources

- **NetGuard** — GitHub README (`github.com/M66B/NetGuard`), `FAQ.md`
  (FAQ 0, 1, 2, 6, 9, 15, 24, 25, 27, 28, 33, 38, 41, 44, 50, 51, 52, 63),
  F-Droid package page (`f-droid.org/en/packages/eu.faircode.netguard/`,
  v2.337, 2026-08-03, GPL-3.0-only, min Android 6.0).
- **Rethink DNS + Firewall** — GitHub README (`github.com/celzero/rethink-app`),
  `docs.rethinkdns.com/firewall`, F-Droid page
  (`f-droid.org/en/packages/com.celzero.bravedns/`, v0.5.7, 2026-09-22,
  Apache-2.0).
- **TrackerControl** — GitHub README
  (`github.com/TrackerControl/tracker-control-android`), F-Droid page
  (`.../net.kollnig.missioncontrol.fdroid/`, 2026.08.05-fdroid, GPL-3.0-only).
- **AFWall+** — GitHub README + releases (`github.com/ukanth/afwall`, v4.1.0,
  2026-08-06, GPL-3.0, root required).
- **Android / AOSP @ `android-9.0.0_r61`** —
  `frameworks/base/core/java/android/app/admin/DevicePolicyManager.java`
  (`setAlwaysOnVpnPackage` ~L4471, `setRecommendedGlobalProxy` ~L3554,
  `setGlobalProxy` `@hide` ~L3488, `setNetworkLoggingEnabled` ~L8935);
  `frameworks/base/core/java/android/os/UserManager.java`
  (`DISALLOW_CONFIG_VPN` = `no_config_vpn`, `DISALLOW_CONFIG_WIFI`,
  `DISALLOW_CONFIG_MOBILE_NETWORKS`, `DISALLOW_CONFIG_TETHERING`,
  `DISALLOW_AIRPLANE_MODE`, `DISALLOW_DATA_ROAMING`, `DISALLOW_NETWORK_RESET`).
- **Android Lock task mode** developer doc
  (`developer.android.com/work/dpc/dedicated-devices/lock-task-mode`) — lock
  task constrains activities, other apps can't launch activities; background
  services unaffected.
- **Xiaomi Mi A1** context and device-owner/lock-task specifics — this repo's
  `mi-a1-kiosk-lockdown.md` (AOSP `dpm`, lock task, user restrictions).

### Unverified / to confirm on-device
- The exact `Settings.Global` keys for always-on VPN (`always_on_vpn_app`,
  `always_on_vpn_lockdown`) on Android 9 — read with `settings list global`.
- Whether NetGuard's whitelist+always-on+lockdown preserves DNS/MQTT on this
  specific ROM (see §5 caveat).
- Rethink's exact UI affordance for a global "allow only one app" (its
  documented model is per-app blocking + domain/IP rules, not a whitelist).
