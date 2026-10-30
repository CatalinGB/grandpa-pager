# Runbook — Grandpa Pager

Everything needed to build, test, deploy and undo. Commands assume the repo root
and a connected, authorized Mi A1 (`adb devices` shows `device`).

## 0. Toolchain (already set up on this host)

- JDK 21 + Gradle 8.14.3 via `mise` (see `.mise.toml`).
- Android SDK at `~/Android/Sdk` (`platforms;android-34`, `build-tools;34.0.0`).
- Node (for the test broker + e2e client).

Reinstall from scratch if needed: `bash /tmp/opencode/setup-android.sh` (or the
equivalent commands in git history / shell history).

## 1. Build

```bash
cd android
ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleDebug testDebugUnitTest
# APK: android/app/build/outputs/apk/debug/app-debug.apk   (~1.2 MB)
# Tests: android/app/build/test-results/testDebugUnitTest/
```

(The Gradle wrapper pins Gradle 8.14.3; `mise exec -- gradle …` also works.)

## 2. Local TLS broker (test only)

```bash
cd tools/broker && npm install
setsid node server.js </dev/null >/tmp/broker.log 2>&1 &   # listens on 8883
# user/pass default grandpa/grandpa (MQTT_USER / MQTT_PASS to override)
```

Certs live in `tools/certs/` (CA + server cert for `127.0.0.1`/`localhost`).
The CA is bundled into the app as `app/src/main/res/raw/ca.crt`.

> The host and the phone are on different subnets, so the phone reaches the
> broker over USB: `adb reverse tcp:8883 tcp:8883`. Re-apply after every reboot.

## 3. Install & configure

```bash
S=$(adb devices | awk 'NR==2{print $1}')
adb -s $S install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s $S reverse tcp:8883 tcp:8883
```

Two ways to configure:

- **UI**: launch the app, pick the role, fill broker host/port/user/pass +
  sender name, Save & start.
- **ADB (scriptable)**: push a prefs file (debug builds are `run-as`-able):
  ```bash
  adb -s $S push tools/scripts/prefs-receiver.xml /data/local/tmp/pager.xml
  adb -s $S shell "run-as com.grandpapager cp /data/local/tmp/pager.xml shared_prefs/pager.xml"
  adb -s $S shell am start -n com.grandpapager/.ReceiverActivity   # receiver
  adb -s $S shell am start -n com.grandpapager/.SenderActivity     # sender
  ```
  `tools/scripts/prefs-receiver.xml` / `prefs-sender.xml` are examples.

## 4. E2E test (host client ↔ phone)

```bash
cd tools
export NODE_PATH=$PWD/broker/node_modules
node scripts/mqtt.js status                                  # receiver idle/showing
node scripts/mqtt.js send "Lunch is ready" 120 "Maria"       # duration-min, sender
node scripts/mqtt.js status                                  # state=showing, sender=Maria
node scripts/mqtt.js clear                                   # back to idle
```

Observed results (2026-10-10, Mi A1 / Android 9, via `adb reverse`):

| Test | Result | Evidence |
|---|---|---|
| TLS connect over USB | `connectComplete`, subscribed | `docs/evidence/`, broker log |
| Display message + sender | Full-screen "From: Maria / Lunch is ready / clears in 1h 59m" | `docs/evidence/01-message-displayed.png` |
| Remote clear | status → `idle`, screen → clock | broker log |
| Expiry (1 min) | alarm fired at `expires_at`, auto-cleared | `PagerService: clearMessage (expired)` |
| Reboot recovery | BootReceiver started service, message restored | `docs/evidence/02-after-reboot.png` |
| Offline (Last Will) | broker published `state:"offline"` on reboot | status read |
| Sender app publish | retained `grandpa/message` from `PhoneSender` | broker log |
| Device-owner kiosk | `Device Owner` set, `mLockTaskModeState=LOCKED` | `docs/evidence/03-kiosk-idle.png` |
| Variant-C message + attention glow | name centred, message auto-sized, amber glow pulse | `docs/evidence/07-attention-glow.png` |
| Idle one-row date + clock 1.3× | `SÂMBĂTĂ 10/10/2026` / `20:36` | `docs/evidence/08-idle-connected.png` |
| Connection warning in place | amber `Probleme cu conexiunea` on idle after broker killed | `docs/evidence/09-idle-connection-warning.png` |
| Unit tests | 8 passed | `PagerCoreTest.xml` |

## 5. Deploy to Grandpa (production)

1. **Broker**: create a free EMQX Cloud Serverless deployment (or EU flespi).
   Note host, port `8883`, username/password. Add per-topic ACLs if available.
2. Configure the receiver app on Grandpa's phone with those credentials.
3. **Lock it down** (device owner; requires **no Google accounts** on the phone):
   ```bash
   adb shell dpm set-device-owner com.grandpapager/.PagerDeviceAdminReceiver
   # also whitelist battery optimization — the #1 reliability lever (no DO API on 9):
   adb shell dumpsys deviceidle whitelist +com.grandpapager
   adb shell dumpsys deviceidle whitelist | grep grandpapager   # verify
   ```
   Launch the receiver once — it applies lock task, persistent home, the safe
   user restrictions and disables the status bar automatically.
4. Power &amp; longevity: confirm the battery-optimization exemption took (above),
   and optionally enable **Stay awake** if you want the panel always lit (the app
   otherwise sleeps it when idle and wakes it on a message). Because the phone
   runs plugged in 24/7, cap its charge near 70% with
   `tools/scripts/charge-controller.js` (drives a smart plug from the
   `grandpa/status` battery readings) and complete the physical/thermal/network
   checklist in [`device-hardening.md`](device-hardening.md).

## 5d. Release signing (read before every release)

The release APK is signed with `android/pager-release.jks` (gitignored) via
`android/keystore.properties` (gitignored, see the `.example`). Its certificate
is:

```
SHA-256 1099d30fb2319c594f2a49ba53d44f1af4da128f269eac9a8e3efd5561329674
CN=Android Debug, O=Android, C=US
```

- **Never regenerate it.** Android identifies an installed app by this
  certificate; a new key makes every update fail with "App not installed" and
  forces a wipe/reinstall (and, on a device-owner pager, `dpm set-device-owner`
  again).
- **Back it up** (keystore + password, two places, offline). Losing it means the
  same wipe/reinstall for every future build.
- Verify a build before publishing:
  ```bash
  ~/Android/Sdk/build-tools/34.0.0/apksigner verify --print-certs \
      android/app/build/outputs/apk/release/app-release.apk | grep SHA-256
  ```
- The v1.1.0 release was signed with a *different* debug key
  (`aa253383…`) that is no longer available, so exactly one in-place update may
  be refused. After that one reinstall, all future releases have this stable key.

## 5c. Provision the pager without typing (QR / magic link)

The admin console can hand the phone its whole connection in one link.

1. Admin → **Settings → Provision the pager (QR)**: broker host, port, the
   **receiver** token, an optional display name → **Build setup link**.
2. The page shows an `https://…/setup.html#host=…&user=…&port=…&role=receiver`
   link and a QR. The payload lives in the URL **fragment**, so it is never sent
   to GitHub/Pages (and does not appear in server logs).
3. On the phone: leave the kiosk (five taps in the bottom-left corner), scan the
   QR with any scanner or open the link in a browser. `setup.html` forwards it to
   `grandpapager://setup?…`, which `SetupActivity` parses (`ProvisionLink`).
4. The app applies the configuration, is already device owner (a plain install is
   not, so kiosk/reboot stay off until `dpm set-device-owner`), starts the
   service and goes to the receiver screen. It then fetches the retained
   `grandpa/config`, so display/cue/kiosk/maintenance arrive automatically.

Notes:
- A **fresh install** applies the link immediately. A pager that already has a
  different broker/username shows a confirmation dialog (a stray link must not
  silently repoint a working device).
- The link contains the token: treat the QR/screenshot like a password.
- The same link works for `role=admin|family` (the sender app), which only needs
  the token and name.

## 5b. Release build

The project has a Gradle wrapper (`android/gradlew`), so CI and contributors do
not need a pre-installed Gradle.

```bash
cd android
ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleRelease testDebugUnitTest
# APK: android/app/build/outputs/apk/release/app-release.apk
```

**Signing.** By default the release APK falls back to the **debug key** (fine for
personal sideloading). For a real release, create a keystore once and add a
gitignored `android/keystore.properties`:

```bash
keytool -genkeypair -v -keystore android/release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias grandpa
```

```properties
# android/keystore.properties  (gitignored — never commit)
storeFile=release.jks
storePassword=…
keyAlias=grandpa
keyPassword=…
```

**GitHub Releases.** Pushing a `v*` tag builds the release APK and attaches it to
a GitHub Release (`.github/workflows/release.yml`). If the repo secrets
`KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` are set, the
APK is signed with that key; otherwise it uses the debug key. Every push/PR runs
`.github/workflows/ci.yml` (debug build + unit tests).

> Changing the signing key later changes the app signature: the old install must
> be uninstalled first (`adb uninstall com.grandpapager`).

## 5c. Admin mode (remote config)

The Pages app has an **admin console** (`admin.html`) gated by the scoped
`grandpa-admin` token (see `flespi-setup.md`). It publishes a versioned config to
the retained `grandpa/config`; the receiver validates, clamps, persists, applies
and echoes it in `status.config`.

The receiver **re-subscribes on every (re)connect** and re-applies the persisted
config at boot, so settings survive reboot/offline. Configurable: display
(`name_size_sp`, `countdown`, `orientation`), the arrival cue, the default
duration, `telemetry.interval_minutes` (status cadence, default 15) and
`power.screen_off_when_idle`. To test on the local broker (broker URL is
hard-coded to Flespi in `admin.js`, so point a copy at the local WSS for local
testing):

```bash
# clear the retained config, or set one directly
NODE_PATH=tools/broker/node_modules node -e '
const mqtt=require("mqtt");const c=mqtt.connect("mqtts://127.0.0.1:8883",{username:"grandpa",password:"grandpa",rejectUnauthorized:false});
c.on("connect",()=>c.publish("grandpa/config",JSON.stringify({schema:1,display:{name_size_sp:90,countdown:true},telemetry:{interval_minutes:1},power:{screen_off_when_idle:true}}),{qos:1,retain:true},()=>c.end()));'
# then read the ack:
NODE_PATH=tools/broker/node_modules node tools/scripts/mqtt.js status
```

### Verifying power & telemetry on-device

```bash
S=$(adb devices | awk 'NR==2{print $1}')
adb -s $S install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s $S shell dumpsys deviceidle whitelist +com.grandpapager   # battery exemption
adb -s $S shell dpm set-device-owner com.grandpapager/.PagerDeviceAdminReceiver
adb -s $S reboot && adb -s $S wait-for-device                     # kiosk re-arms + applies config

adb -s $S shell dumpsys power | grep mWakefulness                 # Dozing when idle
# send a message; the screen should wake and show it, then go back to Dozing on clear
adb -s $S logcat -s PagerService MqttController DeviceOwnerManager # "status interval = N min", "restrictions applied"
adb -s $S shell dumpsys user | grep -A20 Restrictions             # no_bluetooth, no_modify_accounts, …
```

## 6. Undo / maintenance

Exit kiosk and clear device owner (from a running receiver):

```bash
adb shell am broadcast -a com.grandpapager.RELEASE_KIOSK
```

This un-whitelists lock task, clears the persistent home, restores the status
bar, and calls `clearDeviceOwnerApp`. Verified working.

Completely remove: `adb uninstall com.grandpapager`.

## 7. Troubleshooting

- **`unauthorized`**: accept the RSA prompt on the phone (`adb kill-server` +
  replug if stuck).
- **`connect` never completes in the app**: re-run `adb reverse tcp:8883 tcp:8883`
  (lost on reboot) and confirm the broker is up (`ss -ltn | grep 8883`).
- **Screen captured black**: the device was asleep — `adb shell input keyevent
  KEYCODE_WAKEUP` before `screencap`.
- **`dpm remove-active-admin` fails** (`non-test admin`): expected — use the
  `RELEASE_KIOSK` broadcast (the app must clear its own device-owner status).
