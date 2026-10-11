# Receiver configurability surface vs. "configure everything from admin"

Research date: 2026-10-11. Sources are the repository's own source code and docs;
every claim is cited inline as `path:line` (or `path:start-end`). Items marked
**INFERENCE** follow from two or more primary facts rather than one explicit
statement. This file is a primary-source inventory, not a design proposal.

Question: the receiver (the in-situ Android phone running as device-owner kiosk)
is hard to touch once deployed. The owner wants to configure **everything** from
the token-gated static web admin console over MQTT. What configurable surface
exists today, how is each knob set, and what is missing?

Scope of "receiver" here: `PagerService.kt`, `ReceiverActivity.kt`,
`RemoteConfig.kt`, `Config.kt`, `MqttController.kt`, `SetupActivity.kt`,
`DeviceOwnerManager.kt`, the layouts/XML, plus the web `admin.html`/`admin.js`
and the docs.

---

## TL;DR — the key answers

| Question | Answer |
|---|---|
| What is remotely configurable today (over MQTT `grandpa/config`)? | Exactly the 15 fields of the v1 `RemoteConfig` schema: display (name size, countdown, orientation), the arrival cue (glow, chime, vibrate, torch ×4), `defaults.duration_minutes`, `kiosk.locked`, `power.screen_off_when_idle`, `telemetry.interval_minutes`. `RemoteConfig.kt:8-28`. |
| Does the admin UI expose all of them? | **Yes** — every schema field has a control in `admin.js` (`admin.js:52-65`). |
| Broker host/port/TLS/username/token remotely settable? | **No.** They live in `SharedPreferences` (`Config.kt:24-46`) and are only writable from `SetupActivity` or ADB prefs import. Deliberately excluded from remote config (`decisions.md:66-74`). |
| Restart app / reboot device command? | **No.** The only remote command is `{"action":"clear"}` (`PagerService.kt:225-227`). No reboot/restart/reload action exists anywhere (grep of all `.kt`). |
| Is the text layout deployable remotely? | **Only partially.** Name font size and orientation are remote; message auto-size bounds, colors, idle clock ratio, date format and warning string are hardcoded in XML/Kotlin. No template/markup system exists. |
| Doesn't remote config already "cut the channel"? | Correct — and that is by design: it cannot touch broker/credentials/device-owner, so a bad config can't lock the phone off the network (`SECURITY.md:60-64`). |
| Versioning / ack? | Integer `schema` (only `1` accepted); numeric fields are **clamped**; bad schema → whole config rejected; the applied config is **echoed** in `grandpa/status.config` with `config_applied_at`/`config_error` (`PagerService.kt:239-256`, `:327-347`). |
| Notable dead field | `defaults.duration_minutes` is parsed/echoed but **never consumed by the receiver** — display expiry uses the message's own `duration_minutes`/`expires_at` (`PagerService.kt:274-285`). **INFERENCE:** the admin "Default duration" control currently has no on-device effect. |

---

## 0. Mental model: two separate configuration domains

1. **Device setup** (role, broker host/port, username, password, TLS, sender
   name). Stored in `SharedPreferences("pager")` via `Config.kt`. Set **only**
   locally: `SetupActivity` UI, or ADB prefs push (`runbook.md:48-58`), or the
   first-run screen. Never touched by MQTT.
2. **Remote config** (`RemoteConfig`, published to retained `grandpa/config`).
   Set **only** over MQTT by the admin console. Covers display, arrival cue,
   message default, kiosk lock, idle power and telemetry cadence.

`Config.remoteConfig()` returns the parsed remote config or defaults
(`Config.kt:83-85`). `Config.serverUri()` builds `ssl://`/`tcp://` from the
setup fields (`Config.kt:94-95`); `Config.effectiveClientId()` derives the MQTT
client id from role + `ANDROID_ID` (`Config.kt:97-104`).

---

## 1. Config inventory

### 1a. Remote config (`grandpa/config`, schema 1) — remotely settable

All rows come from `RemoteConfig.kt` (model + defaults) and `RemoteConfigCodec`
(parse/clamp). "Admin UI" cites the control that renders it in `admin.js`.

| Setting | Where (field/key) | Type / default / bounds | Scope | How set TODAY | Live vs restart | Admin UI? | Citation |
|---|---|---|---|---|---|---|---|
| Schema version | `schema` | Int / 1 / only `1` accepted | protocol | remote config | live; wrong value rejects whole config | no (hardcoded `1`, `admin.js:9`) | `RemoteConfig.kt:9,43-44,55` |
| Sender name size | `display.name_size_sp` | Int / 46 / clamp 20–120 | display | remote config | live (≤1 s tick) | yes `admin.js:54` | `RemoteConfig.kt:17,57`; `ReceiverActivity.kt:276` |
| Countdown visibility | `display.countdown` | Bool / false | display | remote config (OR `BuildConfig.DEBUG`) | live (≤1 s tick) | yes `admin.js:54` | `RemoteConfig.kt:17,58`; `ReceiverActivity.kt:194` |
| Orientation | `display.orientation` | String / `"auto"` / enum auto,portrait,landscape; junk→auto | display | remote config | live (≤1 s tick) | yes `admin.js:53` | `RemoteConfig.kt:17,59,95-100`; `ReceiverActivity.kt:277-285` |
| Glow duration | `cue.glow_ms` | Int / 9000 / clamp 0–20000 | cue | remote config | live; next new message | yes `admin.js:55` | `RemoteConfig.kt:18,62`; `ReceiverActivity.kt:301-307` |
| Chime | `cue.chime` | Bool / true | cue | remote config | live; next new message | yes `admin.js:56` | `RemoteConfig.kt:18,63`; `ReceiverActivity.kt:308-311` |
| Vibration | `cue.vibrate` | Bool / true | cue | remote config | live; next new message | yes `admin.js:56` | `RemoteConfig.kt:18,64`; `ReceiverActivity.kt:312-320` |
| Torch enabled | `cue.torch.enabled` | Bool / true | cue | remote config | live; next new message | yes `admin.js:56` | `RemoteConfig.kt:19,66`; `ReceiverActivity.kt:321` |
| Torch on-time | `cue.torch.on_ms` | Int / 1500 / clamp 200–5000 | cue | remote config | live; next new message | yes `admin.js:57` | `RemoteConfig.kt:19,67`; `ReceiverActivity.kt:348,356` |
| Torch off-time | `cue.torch.off_ms` | Int / 1500 / clamp 200–5000 | cue | remote config | live; next new message | yes `admin.js:58` | `RemoteConfig.kt:19,68`; `ReceiverActivity.kt:348,356` |
| Torch pulse count | `cue.torch.pulses` | Int / 3 / clamp 1–10 | cue | remote config | live; next new message | yes `admin.js:59` | `RemoteConfig.kt:19,69`; `ReceiverActivity.kt:348,356` |
| Default message duration | `defaults.duration_minutes` | Int / 240 / clamp 5–1440 | messages | remote config (parsed + echoed) | **inert on receiver** (see §5) | yes `admin.js:60` | `RemoteConfig.kt:20,72`; not read in `PagerService.kt:274-285` |
| Kiosk lock | `kiosk.locked` | Bool / true | kiosk | remote config | live (≤1 s tick, device-owner only) | yes `admin.js:63` | `RemoteConfig.kt:21,73`; `ReceiverActivity.kt:286-293` |
| Screen off when idle | `power.screen_off_when_idle` | Bool / true | power | remote config | live; wakes activity on change | yes `admin.js:62` | `RemoteConfig.kt:24,74`; `ReceiverActivity.kt:206`; `PagerService.kt:251` |
| Telemetry interval | `telemetry.interval_minutes` | Int / 15 / clamp 1–1440 | telemetry | remote config | live; timer restarted on apply | yes `admin.js:61` | `RemoteConfig.kt:27,75`; `PagerService.kt:379-392` |

Notes:
- `updated_at` appears in the documented schema (`architecture.md:103`) but is
  **not a field of `RemoteConfig`** and is dropped on `toJson`
  (`RemoteConfig.kt:8-28,34`). The receiver's schema has no timestamp field.
- Apply cadence: `ReceiverActivity.refresh()` re-reads `cfg.remoteConfig()` every
  second (`ReceiverActivity.kt:48-53,171-173`), so display/orientation/kiosk
  changes land within ~1 s when the activity is visible. `PagerService` applies
  cue/power/telemetry on message receipt (`PagerService.kt:239-256`).

### 1b. Device setup / broker (`Config.kt` SharedPreferences) — local only

| Setting | Where (field/key) | Type / default | How set TODAY | Admin UI? | Citation |
|---|---|---|---|---|---|
| Role | `role` | enum / `RECEIVER` | SetupActivity radio; ADB prefs | no | `Config.kt:20-22`; `SetupActivity.kt:53-63`; `prefs-receiver.xml` |
| Broker host | `brokerHost` | String / `"127.0.0.1"` | SetupActivity host field; ADB prefs | no | `Config.kt:24-26`; `SetupActivity.kt:58-64` |
| Broker port | `brokerPort` | Int / `8883` | SetupActivity port field; ADB prefs | no | `Config.kt:28-30`; `SetupActivity.kt:65` |
| Username | `username` | String / `""` | SetupActivity; ADB prefs | no | `Config.kt:32-34`; `SetupActivity.kt:66` |
| Password / token | `password` | String / `""` | SetupActivity (`textPassword`); ADB prefs | no | `Config.kt:36-38`; `SetupActivity.kt:67`; `activity_setup.xml:100-104` |
| Sender name | `senderName` | String / `""` | SetupActivity; SenderActivity; ADB prefs | no | `Config.kt:40-42`; `SetupActivity.kt:68`; `SenderActivity.kt:64` |
| Use TLS | `useTls` | Bool / `true` | SetupActivity checkbox; ADB prefs | no | `Config.kt:44-46`; `SetupActivity.kt:69`; `activity_setup.xml:120-126` |
| Server URI (derived) | `serverUri()` | `ssl://`/`tcp://` + host:port | derived from above | no | `Config.kt:94-95` |
| MQTT client id (derived) | `effectiveClientId()` | `grandpa-{rx,admin,fam}-{ANDROID_ID}` | derived | no | `Config.kt:97-104` |

`isConfigured()` is just `brokerHost.isNotBlank()` (`Config.kt:92`); the service
refuses to start otherwise (`PagerService.kt:95-98`).

### 1c. MQTT connection parameters — hardcoded / build-time

| Parameter | Value | Citation |
|---|---|---|
| cleanSession | `false` (persistent session) | `MqttController.kt:39` |
| automaticReconnect | `true` | `MqttController.kt:40` |
| keepAliveInterval | `30` s | `MqttController.kt:41` |
| connectionTimeout | `15` s | `MqttController.kt:42` |
| maxInflight | `20` | `MqttController.kt:43` |
| Subscribe QoS (receiver) | message/command/config = `1,1,1` | `MqttController.kt:84` |
| Publish QoS | `1` | `MqttController.kt:94` |
| Topics | constants `grandpa/message|command|config|status` | `PagerCore.kt:14-19` |
| Last Will | **none** (Flespi rejects Will for scoped tokens) | `MqttController.kt:48-53`; `decisions.md:82-86` |
| TLS trust | platform CAs + bundled `res/raw/ca.crt` (debug only); forces HTTPS endpoint id | `TlsUtil.kt:75-108,64-73`; `network_security_config.xml:3-8` |
| Reconnect watchdog | `30_000` ms constant | `PagerService.kt:55,354-367` |

None of these are exposed remotely.

### 1d. Device-owner / kiosk controls — provisioned, not remote (except `locked`)

| Control | Mechanism | How set TODAY | Citation |
|---|---|---|---|
| Device owner | `adb shell dpm set-device-owner .PagerDeviceAdminReceiver` | ADB only | `runbook.md:94-97`; `decisions.md:39-46` |
| Lock task packages / features(0) | `d.setLockTaskPackages`, `setLockTaskFeatures(0)` | applied by app at setup/startup, device-owner only | `DeviceOwnerManager.kt:41-58` |
| Persistent HOME | `addPersistentPreferredActivity` → ReceiverActivity | applied by app | `DeviceOwnerManager.kt:51-53` |
| Status bar disabled | `d.setStatusBarDisabled(true)` | applied by app | `DeviceOwnerManager.kt:54-56` |
| User restrictions (8 of them) + sync off + BT off | `applyRestrictions()` | applied by app; cleared only by release gesture | `DeviceOwnerManager.kt:71-98` |
| Release kiosk / clear device owner | `RELEASE_KIOSK` broadcast | `adb shell am broadcast -a com.grandpapager.RELEASE_KIOSK` | `ReceiverActivity.kt:375-392`; `runbook.md:185-192` |
| Admin escape gesture | 5 taps bottom-left → stop lock task + open launcher | on-device touch only | `ReceiverActivity.kt:79-108` |
| Kiosk on/off (lock task) | `kiosk.locked` remote config | **remote config** | `ReceiverActivity.kt:286-293` |
| Display sleep | `dpm.lockNow()` | app, gated by `power.screen_off_when_idle` + device owner | `ReceiverActivity.kt:206,237-242` |
| Battery-optimization exemption | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent | offered at setup save | `SetupActivity.kt:96-108` |

Device admin declares `force-lock`, `disable-keyguard-features`, `wipe-data`,
`reset-password` (`device_admin.xml:3-8`) but the app never calls wipe/reset; a
**reboot API is not declared or used** (see §3).

### 1e. Layout / render constants — hardcoded (not in RemoteConfig)

| Constant | Value | Citation |
|---|---|---|
| Message auto-size | uniform, min 10sp / max 64sp / step 1sp | `activity_receiver.xml:43-46` |
| Name default size | 46sp (overridden by remote) | `activity_receiver.xml:28`; `ReceiverActivity.kt:276` |
| Idle date size / clock ratio | date fit-to-one-row; clock = date × 1.3 | `ReceiverActivity.kt:248-271` |
| Date format | locale weekday + fixed `dd/MM/yyyy` | `DateLabels.kt:20-25` |
| Warn text | localized string `conn_problem` | `ReceiverActivity.kt:226`; `values/strings.xml` |
| Colors | name `#FFFAB005`, message `#FFFFFFFF`, warn `#FFFFB300`, countdown `#FF9E9E9E`, bg black | `activity_receiver.xml:5,27,40,55,80,93` |
| Glow drawable | `glow_border` | `activity_receiver.xml:97-102`; `res/drawable/glow_border.xml` |
| Idle fonts | date 48sp / clock 64sp (initial XML; recomputed at runtime) | `activity_receiver.xml:57,70` |

---

## 2. MQTT / broker configuration specifically

- **Where the broker params live.** `Config.kt` stores `brokerHost`,
  `brokerPort`, `username`, `password`, `useTls` in
  `SharedPreferences("pager")` (`Config.kt:11-14,24-46`). `serverUri()` renders
  `ssl://host:port` or `tcp://host:port` (`Config.kt:94-95`), passed to Paho at
  `MqttController.kt:31`.
- **Hardcoded or in SetupActivity?** Defaults are hardcoded in the getters
  (`"127.0.0.1"`, `8883`, `""`, TLS `true`); the writable path is the
  `SetupActivity` form (`SetupActivity.kt:52-77`, `activity_setup.xml:59-126`)
  or a pushed prefs file (`runbook.md:48-58`, `tools/scripts/prefs-receiver.xml`).
- **Can they be changed remotely today?** **No.** Remote config only reaches
  `RemoteConfig` fields; there is no code path from `grandpa/config` (or
  `grandpa/command`) to `Config.brokerHost`/`username`/`password`/`useTls`.
  `Config` has setters for all of them but nothing remote invokes them.
  Deliberate: `decisions.md:66-74` ("Config cannot change broker/credentials …
  so it cannot cut the channel"), `SECURITY.md:60-64`, `README.md:206-207`,
  `architecture.md:188-191`, `admin-mode/map.md:25-26`.
- **What `Config.kt` does.** Persists the setup credentials + the received
  remote-config JSON and its apply metadata, plus the persisted active message
  and receiver state so expiry survives reboot (`Config.kt:48-91`). It is the
  single source of truth for both config domains, backed by `SharedPreferences`.
- **Admin page broker is hardcoded.** The admin console connects to a
  compile-time `wss://mqtt.flespi.io:443` (`admin.js:6`); the admin UI has **no
  broker field** (unlike the sender, which allows a broker URL —
  `sender.js:11,30`). So relocating the broker also requires editing
  `admin.js` and redeploying the Pages site.

---

## 3. Command surface

Remote commands are handled in `PagerService.handleMessage` /
`PagerService.onMessage`:

- `grandpa/command` → parsed by `PagerJson.toCommand` (Gson →
  `CommandPayload`), then `if (action == "clear") clearMessage("command")`
  (`PagerService.kt:225-227`; payload type `PagerCore.kt:38`; parser
  `PagerCore.kt:131-132`).
- `grandpa/message` empty/`{}` also clears (`PagerService.kt:221-223`).
- Local (non-MQTT) control intents, used by the other roles and alarms:
  `ACTION_SEND`, `ACTION_CLEAR`, `ACTION_EXPIRY` (`PagerService.kt:43-45,102-114`).
- Local broadcast `ACTION_RELEASE_KIOSK` tears down kiosk + device owner
  (`ReceiverActivity.kt:137-140,375-392`).

**Only `"clear"` exists.** A repo-wide grep for `reboot|restart|reload` in the
Kotlin sources returns only doc comments about surviving reboot — no action.
There is **no** "restart app", **no** "reboot device", and **no** explicit
"reload config" command:
- Config reload is implicit: the retained `grandpa/config` is re-delivered on
  every reconnect (`MqttController.kt:58,81-89`) and `ReceiverActivity` re-reads
  prefs every second (`ReceiverActivity.kt:171-173`).
- A device reboot would need `DevicePolicyManager.reboot()` (device-owner only);
  the app declares no such call and `device_admin.xml:3-8` lists no reboot
  policy. **INFERENCE:** adding a `reboot` command is technically feasible for a
  device owner but is unimplemented and not exposed in the admin UI.

---

## 4. "Text layout" / display rendering

How a message is composed (`ReceiverActivity.refresh`, `:171-230`):

1. **Sender name** — `nameView.text = cfg.currentSender` (`:187`), size from
   remote config `display.name_size_sp` (`:276`). Rendered from the message's
   mandatory `sender` field (`architecture.md:79`).
2. **Message text** — `messageView.text = cfg.currentMessageText` (`:190`),
   centred, in a `TextView` with `autoSizeTextType="uniform"`, 10–64sp
   (`activity_receiver.xml:32-46`). The auto-size range is **not** remote-tunable.
3. **Countdown** — `clears_in` string, visible iff `BuildConfig.DEBUG ||
   rc.display.countdown` (`:193-194`).
4. **Idle** — one-row uppercase weekday + `dd/MM/yyyy`, clock 30 % larger
   (`DateLabels.kt:20-28`; `ReceiverActivity.kt:248-271`); both hardcoded.
5. **Warning** — localized `conn_problem`, amber (`ReceiverActivity.kt:224-227`).

**Remote-configurable parts:** name font size, orientation, countdown visibility,
and the arrival cue (glow/chime/vibrate/torch) after layout. **Hardcoded parts:**
message auto-size bounds, all colors, idle clock ratio and date format, warning
text, padding/gravity, and the overall view tree.

**Template/markup system:** none. Message text is plain `String` (JSON field
`text`, `PagerCore.kt:32`); there is no HTML/Markdown/template rendering anywhere
in the receiver. "Deploy the text layout" from admin would today mean only
choosing among the small set of already-parameterised knobs (size/orientation/
countdown); anything richer (per-element sizes, colors, alignment, idle layout,
a template) requires new schema fields + new rendering code, not just a config
publish.

---

## 5. Gap analysis vs. "configure everything from admin"

Prioritised, with the reason each item is currently local-only.

| # | Gap | Why it is local-only today | Citation |
|---|---|---|---|
| 1 | **Broker host/port/TLS/username/token** | Deliberate security invariant: remote config must not be able to cut the channel. No remote write path to `Config` credentials. | `decisions.md:66-74`; `SECURITY.md:60-64`; `README.md:206-207` |
| 2 | **Restart app / reboot device** | No remote command exists; only `clear`. Reboot needs device-owner `DevicePolicyManager.reboot()` and a command handler; neither present. | `PagerService.kt:225-227`; `device_admin.xml:3-8` |
| 3 | **Arbitrary layout / template** | Layout is a fixed XML tree + hardcoded constants; only size/orientation/countdown bubble through `RemoteConfig`. | `activity_receiver.xml`; `ReceiverActivity.kt:248-271` |
| 4 | **MQTT connection params** (keepalive, timeout, reconnect, QoS, inflight, clientId, topics) | Hardcoded constants in `MqttController`/`PagerCore`; not modelled in `RemoteConfig`. | `MqttController.kt:38-53,84,94`; `PagerCore.kt:14-19` |
| 5 | **Device-owner restrictions / lock-task features / hardening** | Hardcoded list applied at setup; only the coarse `kiosk.locked` toggle is remote. | `DeviceOwnerManager.kt:41-58,71-98` |
| 6 | **`defaults.duration_minutes` is inert** | Parsed + echoed but the receiver's expiry uses the incoming message's duration; the field is not read in `showMessage`. | `RemoteConfig.kt:72`; `PagerService.kt:274-285` |
| 7 | **Admin console broker is hardcoded** | `admin.js` has no broker field; changing broker forces editing/redeploying Pages. | `admin.js:6` |
| 8 | **App OTA / update path** | Explicitly deferred; no update mechanism over MQTT. | `architecture.md:207`; `.scratch/grandpa-pager/map.md:69-71` |
| 9 | **Config version/history/audit** | Only an integer `schema`; no monotonic version, no who-applied-what record. | `.scratch/admin-mode/map.md:50-54` |
| 10 | **Warning/branding strings, colors, idle format** | Hardcoded resources/XML, not in the schema. | `activity_receiver.xml`; `DateLabels.kt` |

**INFERENCE on effort:**
- Gaps 1–2 are the ones that make the phone "unreachable-from-admin *to fix*":
  if a wrong broker host is ever pushed it could strand the device, but
  if a **reset/reboot/revert** command existed, most remote-change risk would be
  recoverable. Today neither a credential path nor a restart/reboot path exists.
- A "text layout deploy" would require a new schema version with a layout/template
  block and new rendering code in `ReceiverActivity`; the current schema cannot
  express it.

---

## 6. Safety / validation notes

- **Parse.** `RemoteConfigCodec.parse` returns `null` if the JSON is blank
  (`RemoteConfig.kt:40`), if `schema` is absent/non-primitive/≠1 (`:43-44`), or
  on any exception (`:77-79`). A `null` result is a rejection.
- **Clamp/ignore.** `int()` clamps to `[min,max]` and defaults on wrong type
  (`RemoteConfig.kt:82-87`); `bool()` defaults on wrong type (`:89-93`);
  `orient()` falls back to `"auto"` for anything not in the enum
  (`:95-100`). Unknown fields are simply not read (Gson `JsonObject` lookups
  only touch known keys). So a partially-valid config is **clamped**, never
  half-applied in the sense of throwing.
- **Rejected config behaviour.** On `null`, `PagerService.applyRemoteConfig`
  sets `remoteConfigError = "schema invalid"`, keeps the previously stored
  config untouched, publishes status, and does not apply anything
  (`PagerService.kt:239-256`). On success it stores the re-serialised config,
  stamps `remoteConfigAppliedAt`, clears the error, and re-arms the status timer
  (`:245-254`).
- **Ack / versioning via status.** The receiver echoes the effective config in
  `grandpa/status.config`, plus `config_applied_at` and `config_error`
  (`PagerService.kt:341-343`; `PagerCore.kt:51-53`; `architecture.md:113-120`).
  The admin UI prefills from `status.config` when not dirty (`admin.js:116-121`).
  There is **no** monotonic config version — only the `schema` integer.
- **By-design security constraints.** "Remote config can only toggle kiosk lock,
  never broker/credentials or device-owner status" (`architecture.md:188-191`);
  `decisions.md:66-74`; `SECURITY.md:60-64`. The admin token is a narrow ACL
  token (pub/sub `grandpa/config`, sub `grandpa/status`, origin-locked,
  `sessionStorage`) — never Master (`flespi-setup.md:86-104`; `admin.js:105`).
- **Unit tests.** `PagerCoreTest.kt:105-128` covers schema rejection, clamping
  (`interval_minutes` 999999→1440, 0→1), and parsing of `power`/`telemetry`.

---

## Corrections / discrepancies found while reading

- **`defaults.duration_minutes` has no receiver effect** (see §5 #6). Documented
  as "per device via `defaults.duration_minutes` (admin)" in
  `architecture.md:135-136`, but the code path does not read it. **INFERENCE:**
  either a bug or the field is intended for a future sender-side consumer.
- **`updated_at` is documented but unimplemented** (`architecture.md:103` vs
  `RemoteConfig.kt:8-28`).
- **`PagerService` KDoc** still says the receiver subscribes to
  `grandpa/message + grandpa/command` (`PagerService.kt:29`) while the code also
  subscribes `grandpa/config` (`MqttController.kt:84`) — stale comment only.

---

## Confirmed scope (2026-10-11)

Owner confirmed all gaps are in scope, with these decisions:

- **In scope:** broker server + credentials, restart/reboot, text layout,
  MQTT connection tuning, device-owner restrictions — all reachable from admin.
- **Broker/credentials safety model:** guardrails + auto-revert. Test-connect to
  the new broker before committing; if the receiver does not (re)connect within a
  bound, revert to the previous working config so the phone is never stranded.
- **Restart scope:** both — soft app/service restart **and** device-owner reboot.

Open question still to settle during design: what exactly "text layout" means
(the small parameter set vs. a richer layout/template block — see §4).

## Bottom line

Today the admin can already tune the whole **presentation/cue/power/kiosk-lock**
surface of the receiver over MQTT, with clamping and an ack. What it cannot do —
by deliberate security design, and because no plumbing exists — is change the
**broker/credentials**, **restart or reboot** the device, or **deploy an
arbitrary layout/template**. Those three are the gaps standing between the current
"tune the display" admin and the owner's "configure everything from admin" goal.
