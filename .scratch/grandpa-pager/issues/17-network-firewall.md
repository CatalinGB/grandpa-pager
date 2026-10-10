# 17 — Lock down the phone's network (open-source firewall)

Type: task
Status: open
Blocked by:

## Question

Secure Grandpa's phone so it can only reach the MQTT broker and nothing else —
ideally with an open-source firewall.

## Answer

Research: `.scratch/grandpa-pager/research/android-network-firewall.md`.

**Recommended: NetGuard** (`eu.faircode.netguard`, GPLv3, **no root** — it uses a
local VPN). Already installed on the phone (`adb install`, v2.337; VpnService
`eu.faircode.netguard/.ServiceSinkhole` present).

Plan:
1. NetGuard → enable, **whitelist mode**, so everything is blocked except:
   - `com.grandpapager` (the receiver) — allowed;
   - only `mqtt.flespi.io:8883` where pinhole is possible (NetGuard can't pin a
     port, so allow the app + optionally the host).
2. Android **Settings → Network → VPN → NetGuard → Always-on VPN** + *Block
   connections without VPN*. Then the **device owner** (our app) sets
   `setAlwaysOnVpnPackage(admin, "eu.faircode.netguard", lockdown=true)` and
   `addUserRestriction(DISALLOW_CONFIG_VPN)` so the kiosk user can't disable it.

Caveats (from research): always-on VPN's **"Block connections without VPN" is
known to break DNS on Android 8+**; this device is Android 9 — test that MQTT and
DNS still work, otherwise run always-on *without* lockdown. Android 9 gives
device owners **no per-app network API**, and neither NetGuard nor Rethink can
pin a **port**.

Status: NetGuard installed; configuration + always-on wiring pending.
