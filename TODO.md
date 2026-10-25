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

### Open: `grandpa/message` is bridged one way only (2026-10-25)

Checked both directions after the Mosquitto restart:

| Direction | Test | Result |
|---|---|---|
| Flespi → home | publish on Flespi, read `sensor` on `grandpa/message` | **works** (instant) |
| home → Flespi | `mqtt.publish` / Clear button, watch Flespi live + retained | **broken** — nothing arrives |
| home → Flespi (`grandpa/command`) | press Restart app | **works** (27 ms, app acked) |

So the session, token and bridge are healthy; it is specifically the
`grandpa/message` direction. Consequence: **Send and Clear from the HA dashboard
do not reach the phone** (the earlier "clear reached Flespi" check was probably
made with `mosquitto_sub` against the home broker, which does see the empty
message). This is not an app incompatibility — the app never receives the
payload at all.

Host-side fix: `topic grandpa/message both 1` in the bridge file (and make sure
the `grandpa-pager-home` token can publish `grandpa/#`), then restart Mosquitto.
Verify by sending from the dashboard and seeing the text on the phone.
