# Device hardening — always-on pager

How to keep an old Android phone (the reference device is a **Mi A1, Android 9**)
paging 24/7 for years without cooking its battery, starting a fire, or exposing
the family channel. Read this before deploying a receiver you intend to leave
plugged in permanently.

Two kinds of work:
- **Automated in this repo** — already done for you (telemetry, the charge
  controller, the app-level power behaviour). Nothing to do.
- **Manual** — physical/OS/network steps only you can perform on the device and
  the network. These are collected as a checklist at the end.

## What the repo already does

| Lever | Where |
|---|---|
| Reports `battery`, `charging` and **`battery_temp_c`** on `grandpa/status` every 30 s | `PagerService.kt` (`publishStatus`, `batteryTempC`) |
| Idle screen-off (device owner) with a guaranteed wake via full-screen intent; `FLAG_KEEP_SCREEN_ON` only while a message shows | `ReceiverActivity.kt`, `PagerService.kt` |
| Foreground service + 30 s watchdog + boot restart (resilience, not heat) | `PagerService.kt`, `BootReceiver.kt` |
| Kiosk lockdown (no power menu, status bar, recents) | `DeviceOwnerManager.kt` |
| Optional **charge controller** that drives a mains smart plug from the status topic | `tools/scripts/charge-controller.js` |

Battery temperature is also shown in the Android sender, the web sender status
panel and the admin device panel, so you can watch heat remotely.

## 1. Battery: keep it near 70%

Li-ion ages fastest at **100% + warm** — exactly the always-plugged case. Stock
Android 9 has **no charge limit** (Google only added it in Pixel Android 13+), so
you must add one. Best → worst:

**a) MQTT-driven smart plug (no root) — recommended.**
`grandpa/status` already carries the battery and charging state. Run
`tools/scripts/charge-controller.js` on any always-on host (Pi, server, NAS) and
point it at a smart plug's on/off URLs:

```bash
MQTT_HOST=mqtt.flespi.io MQTT_PORT=8883 \
MQTT_USER=<receiver-or-a-read-only-token> MQTT_PASS=<token> \
PLUG_ON_URL='http://plug.local/cm?cmnd=Power1%20On' \
PLUG_OFF_URL='http://plug.local/cm?cmnd=Power1%20Off' \
CHARGE_STOP_PCT=70 CHARGE_RESUME_PCT=65 \
node tools/scripts/charge-controller.js
```
It cuts the charger at 70% and restores it at 65%; the phone keeps paging on its
own battery the whole time (never powers off). Shallow 65↔70 cycling is gentle on
the cell. Run it without `PLUG_*_URL` first to watch the decisions in dry-run.
- Works with Tasmota, ESPHome, Shelly, or a Home Assistant webhook URL.
- Needs an external always-on device — the one downside.

**b) Root + ACC (Advanced Charging Controller / ACCA).**
Unlock the bootloader (wipes the device), flash Magisk, then:
```bash
acc -s s 70     # stop charging at 70%
acc -s r 65     # resume at 65%
acc -s t 40     # pause charging above 40 °C
```
Best control (on-device, temp-aware) but roots an EOL device and must be redone
after any re-flash.

**c) Dumb timer plug** — last resort. "On 3 h / off 5 h" lowers the average
state of charge but cannot read the battery, so it risks deep discharge and a
shutdown. Never let it approach 0%.

## 2. Heat

- Use a **slow 5 V/1 A charger**, not the stock fast one — less heat while
  charging.
- **No case.** Open, ventilated surface; never under cloth, against a wall, or in
  direct sun.
- Lower the **screen brightness** — the LCD backlight is the dominant idle heat
  source on a screen-on pager.
- Target **< 35 °C idle, never > 40 °C while charging**. Watch `battery_temp_c`
  in status, or `adb shell dumpsys battery` (temp is in tenths of °C).

## 3. Electrical / fire safety

A permanently powered device is a small but real fire/battery-swelling risk.

- Quality, certified charger and cable — cheap ones are the usual 24/7 failure.
- Non-flammable resting surface (ceramic / metal / stone), clear of curtains and
  paper; put a **smoke alarm** nearby.
- Surge-protected outlet.
- **Watch for swelling** (bulging back, screen lifting) — stop immediately,
  replace the battery. On 2017 hardware this is the likeliest failure.

## 4. Always-on OS settings (Android 9)

- Developer options: **Stay awake** is optional — the app sleeps the display when
  idle and wakes it on a new message (`power.screen_off_when_idle`); leave it off
  unless you want the panel lit 24/7. Turn **USB debugging off** once deployed.
- **Battery-optimization exemption — do this, it is the #1 reliability lever.**
  Without it, when the phone runs on battery Android's Doze/App Standby can freeze
  the process and delay or miss a message. The app cannot grant it silently:
  Android 9 exposes **no device-owner API** for the doze whitelist, so it is a
  one-time manual step. Two equivalent ways:
  1. **On the phone:** Settings → Apps → **Grandpa Pager** → Battery →
     *Battery optimization* → All apps → Grandpa Pager → **Don't optimize**.
     (The app also fires this dialog once at setup, and shows it if launched
     outside kiosk while not yet exempt.)
  2. **Over ADB (deterministic):**
     ```bash
     adb shell dumpsys deviceidle whitelist +com.grandpapager
     adb shell dumpsys deviceidle whitelist | grep grandpapager   # verify
     ```
  The app logs a warning on start if the exemption is missing. Note: while the
  pager is **plugged in**, Doze does not engage at all ("connecting a charger
  exits Doze"); the app also holds a `PARTIAL_WAKE_LOCK` + `WifiLock` and runs a
  foreground service, so the exemption matters mainly if it is ever on battery.
- Wi-Fi: "Keep Wi-Fi on during sleep = always"; disable Wi-Fi power saving and
  scan throttling. Set a **DHCP reservation** on the router. (The app also holds
  `WIFI_MODE_FULL_HIGH_PERF`, which does most of this at runtime.)
- Disable what you don't need: Bluetooth, NFC, location, auto-sync, Play Store
  auto-update. Fix the brightness (skip adaptive). *(First six are applied
  automatically by the app as device owner — see §7 of the top of this file /
  `DeviceOwnerManager` — this is the manual backstop.)*
- Enable Do-Not-Disturb and keep the app's channel at low importance so nothing
  overlays the pager.

## 5. Network & attack surface

The phone is now a 24/7 internet-facing node.

- **Biggest residual risk: the OS is EOL** — Android 9, last security patch
  ~2020. Contain rather than patch: put it on a **dedicated guest SSID / VLAN**,
  and if your router allows, restrict its egress to just `mqtt.flespi.io`.
- **No Google account** (already required to set device owner), no browser,
  email, or extra apps.
- **USB debugging off** closes the `adb` / `run-as` credential-read path
  (`SECURITY.md` finding 5). Physical access can still read the receiver token,
  so treat a lost phone as a **compromised token** and rotate it.

## 6. Physical

- Unattended 24/7: **cable-lock the phone** to furniture so theft doesn't walk
  off with a device-owner + broker token.
- Kiosk already removes power menu, status bar and recents
  (`DeviceOwnerManager.kt`).

## 7. Monitoring & maintenance

- Monthly: glance at `battery_temp_c`, the `charging` flag behaviour, and run one
  reboot-recovery test.
- Every ~2 years: expect to **replace the battery** — health degrades regardless
  of the 70% cap.
- Rotate broker tokens if the device is ever out of your control.

---

## Manual tasks — handoff checklist

Everything below is physical / account / network work that cannot be done from
the codebase. Tick as you go.

### Battery & charging
- [ ] Pick a charge-limiting approach: **(a) smart plug** (recommended, no root),
  (b) root + ACC, or (c) timer plug.
- [ ] If (a): set up a smart plug (Tasmota / ESPHome / Shelly / HA webhook), note
  its on/off URLs, and deploy `tools/scripts/charge-controller.js` on an
  always-on host with `CHARGE_STOP_PCT=70 CHARGE_RESUME_PCT=65`.
- [ ] If (b): unlock the Mi A1 bootloader (wipes it), flash Magisk, install ACC,
  set `acc -s s 70`, `-s r 65`, `-s t 40`. Re-provision the receiver afterwards.
- [ ] If (c): set a conservative timer schedule and verify it never approaches 0%.
- [ ] Verify the loop: confirm the plug really cuts at ~70% and restores at ~65%
  without the phone powering off.

### Heat
- [ ] Choose a slow 5 V/1 A charger and a good cable.
- [ ] Remove the case; place on an open, non-flammable, ventilated surface away
  from sun and soft furnishings.
- [ ] Set a low-ish, fixed screen brightness.
- [ ] Confirm `battery_temp_c` stays < 35 °C idle and < 40 °C charging.

### Electrical / fire
- [ ] Plug into a surge-protected outlet.
- [ ] Place a smoke alarm nearby.
- [ ] Note the battery-replacement date (~2 years) and inspect monthly for
  swelling.

### Android OS settings
- [ ] Developer options: enable **Stay awake** only if not using the smart plug.
- [ ] Turn **USB debugging OFF** after provisioning.
- [ ] Grant the app the **battery-optimization exemption** (UI path in §4, or
  `adb shell dumpsys deviceidle whitelist +com.grandpapager`); verify it took.
- [ ] Wi-Fi: "keep on during sleep = always"; add a **DHCP reservation**.
- [ ] Disable Bluetooth, NFC, location, auto-sync, Play Store auto-update.
- [ ] Enable Do-Not-Disturb; keep the pager notification at low importance.

### Network & security
- [ ] Put the phone on a **dedicated guest SSID / VLAN**.
- [ ] If possible, restrict its egress to `mqtt.flespi.io`.
- [ ] Confirm **no Google account** is on the device.
- [ ] Record which broker token the receiver holds, so you can rotate it if the
  phone is lost.
- [ ] (Optional, higher assurance) encrypt/protect the on-device token store.

### Physical
- [ ] Cable-lock the phone to furniture.

### Verification
- [ ] Send a test message and watch it appear; confirm status shows battery,
  charging and temperature.
- [ ] Reboot the phone and confirm the receiver comes back and re-applies config.
- [ ] Power-cycle the smart plug and confirm the pager keeps working on battery.
