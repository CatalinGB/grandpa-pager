# TODO

Backlog / known issues that are not yet resolved.

## PWA install fails on Android Chrome/Brave ("This app cannot be installed")

**Status:** open — site-side verified good; blocked on device-side diagnosis.

**Symptom:** On Android, Chrome and Brave show *"This app cannot be installed"* and
only offer a home-screen **shortcut**. The same URL installs fine in desktop
Chrome (Linux). Reproduced by the owner on the GitHub Pages HTTPS site.

**Verified good (site side, 2026-10):**
- `https://catalingb.github.io/grandpa-pager/` serves the up-to-date build
  (`sender.js?v=15`, `common.js?v=4`).
- Manifest is complete: `name`/`short_name`, `start_url`, `display: standalone`,
  `prefer_related_applications: false`, 192 + 512 icons (`purpose: any` + maskable).
- Icons are **8-bit PNG** (were 16-bit — re-encoded; WebAPK minting rejects 16-bit).
- Service worker registers and is active; manifest served as
  `application/manifest+json`; HTTPS.
- Chrome CDP `Page.getInstallabilityErrors` → `[]` for `index.html` and
  `admin.html`, including with an Android Chrome UA override.
- Meets all criteria in https://web.dev/articles/install-criteria.

**Conclusion:** the app meets Chrome's installability criteria, so the failure is
device/browser side, not the app.

**Remaining hypotheses (device side):**
- Chrome/Brave install PWAs as a **WebAPK minted via Google Play services**; if
  Play services is missing/disabled, or a **DNS/adblock/firewall filter blocks
  Google's endpoints** (common on self-hosted/pi-hole/AdGuard setups), minting
  fails → shortcut only.
- Opened inside an **in-app browser / Custom Tab** (link tapped in a chat app).
- **Enterprise / parental policy or a work profile** disabling installs.
- **Older Chrome** tripping on **relative** `start_url`/`scope` in WebAPK minting.

**Next steps:**
1. On the phone, enable USB debugging → desktop Chrome `chrome://inspect/#devices`
   → inspect the tab → **Application → Manifest** → read the exact
   installability errors. Paste them here.
2. Quick discriminator: install **https://airhorner.com/** on the same phone. If
   that also fails, it's device/account/network; if it works, keep digging on our
   side.
3. Try **absolute** `start_url` / `scope` / explicit `id` in the manifests
   (`/grandpa-pager/…`) as an older-Chrome workaround. Safe for the current
   fixed GitHub Pages path, but drops portability (custom domain / different
   repo name would need edits).

**Related change (shipped):** `common.js` shows an "installing needs a secure
https:// address" hint when `window.isSecureContext === false` (the `http://` case
that produces the same Android message). Not the owner's case (they are on
HTTPS) but a good guard.

## Periodic self-maintenance on the receiver (scheduled reboot / app restart)

**Status:** idea — needs an app update, so deferred (the deployed app cannot be
updated right now).

**Motivation:** the phone can eventually become unreachable after days of
uptime; a periodic reboot (or app restart) could clear app/OS drift and memory
leaks and get it back to a known-good state. Explicitly **not** a fix for
power/Wi-Fi outages.

**Key constraint:** the trigger must be **on-device**. A remote command (the
admin's existing `reboot_device`/`restart_app`) needs connectivity, so it cannot
rescue a phone that is already unreachable. The web admin is a static page with no
backend and cannot run a reliable scheduler either.

**Proposed design (app update):**
- New config under `schema: 1`, e.g.
  `maintenance: { restart_every_hours: 0, reboot_every_days: 0, at_hour: 4 }`
  (0 = off; the receiver ignores unknown fields, so this is backward-compatible).
- Receiver schedules an `AlarmManager.setExactAndAllowWhileIdle` (re-armed on boot
  in `BootReceiver`), at a low-use local hour.
- Prefer `restart_app` (cheap, ~seconds, clears app-level leaks) by default; only
  use `DevicePolicyManager.reboot()` for the full reboot when the device is owner
  and enabled. If not device owner, fall back to the app restart and record it in
  status.
- Admin UI: a "Maintenance" section (enable + interval + hour) with a warning
  that a reboot takes the screen offline for ~1 min.
- Telemetry: record `last_restart_at` / `last_reboot_at` in `status` so the
  admin can see it happened.

**Risks / notes:**
- A message arriving during the reboot is retained, but if its TTL expires while
  booting it may be missed (late-arrival grace is 10 min / 1 min show).
- Scheduling relies on the alarm firing under Doze; prefer device-owner APIs.
- Interim (no app update): use the admin's manual **Restart app / Reboot device**
  when the phone is reachable; for power-related outages look at
  `tools/scripts/charge-controller.js` (smart plug) instead.
- **Interim (HA, 2026-10):** a Home Assistant automation now restarts the app
  daily and reboots the phone every 3 days at 00:00 (`ha/`). It works through
  MQTT, so it only fires while the phone is reachable — it does **not** replace
  an on-device schedule, it just removes the manual step in the normal case.
- Investigate the actual failure mode first (battery/charging/wifi/mqtt/last_seen
  from the last status) — a reboot masks it.


## Home Assistant hardening (2026-10-25)

Two failure modes showed up while the HA integration was running unattended:

1. **Retained MQTT discovery vanished.** After an HA restart every
   `grandpa_pager_*` entity came back as `unavailable` (`restored: true`), so the
   dashboard showed nothing and — worse — the daily restart automation pressed a
   dead `button.grandpa_pager_restart_app`, silently doing nothing.
   Fixed twice over:
   - `script.pager_publish_discovery` re-publishes all 17 retained discovery
     payloads (`ha/discovery/pager.json` is the source of truth).
   - `automation.pager_republish_mqtt_discovery_on_start` runs it 10 s after HA
     starts, so a restart repairs itself.
   - **Still worth doing on the broker:** `persistence true` plus a
     `persistence_location` on a mapped volume in `mosquitto.conf`, so retained
     messages survive a Mosquitto restart and the repair step stays a fallback.
2. **The app stopped publishing `grandpa/status`** (hung at 03:49, resumed
   instantly on `restart_app`). Telemetry silently went stale for 17 h.
   Candidate follow-up: an HA alert when `sensor.grandpa_pager_last_seen` is
   older than ~45 min, so a hung app is noticed the same day instead of by luck.

### Open: `grandpa/message` outbound is dropped by Flespi's ACL (2026-10-25)

Measured both directions on the live bridge (config unchanged, `both` in place):

| Direction | Test | Result |
|---|---|---|
| Flespi → home | publish on Flespi, read `grandpa/message` locally | **works** (instant) |
| home → Flespi | `mqtt.publish` + Clear button, watch Flespi live/retained | **dropped** |
| home → Flespi (`grandpa/command`) | press Restart app | **works** (27 ms, app acks) |
| home → Flespi with the bridge token | publish as `grandpa-pager-home` | **dropped** |
| home → Flespi with the sender token | publish as `grandpa-web-sender` | **works** |

So it is the **token ACL**, not the bridge and not the app: `grandpa-pager-home`
may publish `grandpa/command`, subscribe to everything, but not publish
`grandpa/message`. Consequence: **Send and Clear from the HA dashboard do not
reach the phone** (the earlier "clear reached Flespi" check was made against the
home broker, which does see the empty message).

Self-inflicted incident while testing a workaround: a second bridge for the
outbound direction (`message in` on one connection, `message out` on another)
makes Mosquitto echo the topic between the two bridges — ~15k messages in three
minutes, the app then drained the queued backlog and republished a status per
message (~19/s for ~5 min). The extra session was also refused by Flespi
(`Connection Refused: not authorised`). Config restored byte-for-byte from the
backup; the queue drained on its own and the pager was never affected (all test
payloads were already expired, so nothing was displayed).

Fix: Flespi → Tokens → `grandpa-pager-home` → allow **publish** on
`grandpa/message` (or `grandpa/#`), restart Mosquitto, then send one message from
the dashboard and confirm it appears on the phone. No app change, no HA change.

### 2026-10-30 outage: the pager died while healthy, and stayed dead ~20 h

Last contact 22:28 local, status fully healthy (100 %, charging, Wi-Fi on, MQTT
on, device owner). Nothing since: the 00:00 restart and two manual
restart/reboot presses produced **no ack at all** (last ack still Oct 28 22:00),
so this was not the Oct 25 "alive but not publishing" mode — the app was gone or
unreachable. Home Assistant restarted twice that day but the bridge/retained state
was fine, and the phone's charging LED is on (camera), so power is present.

Root cause of the *duration*, regardless of the trigger: **every recovery path was
MQTT-only**. The daily restart and the 3-day reboot were commands published into a
dead client, so the pager had no way back until a human touched it.

Fixed in the app (build pending): phone-owned maintenance supervisor
(`maintenance.*` in the retained config, AlarmManager, re-armed on boot) with a
silence ladder (restart → reboot), reboot cooldown and a 15 % battery floor. See
`docs/decisions.md` D19.

Still open:
- deploy the build and turn off the two legacy HA automations;
- add a HA staleness alert (last_seen > 45 min) so the next outage is noticed in
  an hour, not a day;
- physical check of the device when possible (battery swelling is still the #1
  hardware risk on this 2017 phone, and the charge controller is not running —
  battery sits at 100 %);
- decide the OTA path: the device owner app can download + verify an APK, but
  Android 9 does not grant `INSTALL_PACKAGES`, so a *silent* self-install is not
  guaranteed; expect either a one-tap installer prompt (needs someone at the
  device) or ADB over TCP (needs `adb tcpip 5555` after each boot). Investigate
  device-owner install behaviour on the actual device before relying on it.

### 2026-10-30 review follow-ups (1.3.0)

Fixed:
- **liveness was fake**: `MqttController.publish()` no-ops when disconnected and
  `publishStatus()` still refreshed `lastPublishAt`, so the supervisor could
  report "healthy" while doing nothing. Liveness now comes only from Paho's
  `deliveryComplete` (D20).
- sender-role installs no longer arm/execute maintenance (role gate in the
  scheduler and the receiver).
- stale reboots now back off exponentially (6 h → 12 h → 24 h, cap) and the
  backoff resets on the first acked publish.
- a scheduled restart is recorded only after the start was handed to the system.
- telemetry added: memory/native/threads/CPU/storage/Doze/RSSI/last crash/last
  disconnect/counters/last acked publish/service starts (+ admin cards).

Still open:
- ~~supervisor shares the app process~~ — **fixed in 1.5.0**: `MaintenanceReceiver`
  and `BootReceiver` run in `:maintenance` with file-backed plan/heartbeat/state
  (one writer per file, atomic replace). Only a user force-stop still defeats it.
- **Retained status can lag one bridge reconnect**: Mosquitto does not preserve
  the retain flag on live forwards, so entities created between statuses (or HA
  restarts) show the last bridged payload until the next status (≤15 min). Not
  worth a republish loop; the age display makes it visible.
- **APK signing key**: v1.1.0 was signed with a debug cert (`aa2533…`) that no
  longer exists; from 1.3.0 onward builds use `1099d3…`. That key is now preserved
  as `android/pager-release.jks` with a real password (`keystore.properties`, both
  gitignored) — see runbook §5d. One in-place update may still be refused because
  of the old key; after that single reinstall every future release updates in
  place. **Back up the keystore + password** (cannot be regenerated).

### 2026-10-31 clear did not work (test phone)

Cause: (a) clears published an **empty retained** payload, which *deletes* the
retained message, so an offline pager never learned about the clear and re-showed
its stored message; (b) the admin token (used by the bridge) had no `publish` on
`grandpa/message`, so the HA dashboard Send/Clear never left the home broker.
Fixed: tombstones (`{}`) everywhere, `publish` granted on that topic for the
admin and receiver tokens, and the admin console gained a Clear action. Verified
end-to-end on Flespi (HA publish → bridge → message; HA clear → retained `{}`; a
fresh subscriber sees the tombstone).
