# Spec — Full remote configuration of the receiver from the admin console

Type: spec
Status: ready-for-agent
Effort: `admin-full-config` (revisits the `admin-mode` out-of-scope item "broker
URL / tokens over MQTT")

Vocabulary (established in `.scratch/admin-mode/`): **receiver** (the in-situ
Android phone, device owner, kiosk), **admin console** (the token-gated static
web page), **remote config** (retained JSON on `grandpa/config`), **device setup**
(role/broker/token held on-device), **status** (retained JSON on `grandpa/status`),
**command** (`grandpa/command`), **guardrail** (the auto-revert safety net).

Research backing this spec:
`.scratch/grandpa-pager/research/receiver-admin-config-surface.md`.

---

## Problem Statement

The receiver is deployed in situ, running as a kiosk device owner. Once it is
there, the owner cannot easily reach the phone to change settings or update the
app. Anything that today lives only in the on-device Setup screen, in hardcoded
Kotlin constants, or in ADB commands is effectively frozen for the life of the
deployment.

Concretely, the owner cannot currently, from the admin console:

- change the broker (server, port, TLS, credentials) the receiver connects to;
- tune MQTT connection behaviour (keepalive, timeout, reconnect, QoS, inflight);
- restart the app or reboot the phone;
- adjust the parts of the message/idle layout that are still hardcoded
  (message auto-size bounds, colours, idle clock scale, date format, warning text);
- change the device-owner hardening restrictions that are applied at setup.

The admin can already tune the display/cue/power/kiosk-lock surface, but the
remaining knobs require physical access — which defeats the purpose of the
console for an in-situ device.

## Solution

Extend the remote configuration so that **every receiver setting the admin might
need after deployment is reachable from the admin console.** Three mechanisms are
added:

1. **Retained remote config (schema-compatible, extended).** The existing
   `grandpa/config` payload gains the layout knobs and device-owner restriction
   block. No secrets. Applied live/clamped as today.

2. **Transactional connection command.** Broker + MQTT tuning changes travel as a
   `connection` command, not as retained config, because they carry a secret and
   need a commit-or-revert lifecycle. The receiver validates a **candidate**,
   backs up the currently-working connection, switches, and **auto-reverts** if it
   does not establish a connection within a bounded window. The phone can never be
   stranded by a bad push.

3. **Device control commands.** `restart_app` and `reboot_device` commands let the
   admin restart the service or reboot the phone remotely, with an acknowledgement.

The admin console exposes all of this, shows the guardrail state, and never
displays or retrieves the broker password (it can only report "set" / "not set").

## User Stories

1. As the owner, I want to change the MQTT broker host from the admin console, so
   that I can migrate the deployment to a different broker without touching the phone.
2. As the owner, I want to change the broker port and TLS mode from the admin
   console, so that I can match a broker's endpoint without physical access.
3. As the owner, I want to set the broker username and password from the admin
   console, so that I can rotate credentials on an in-situ device.
4. As the owner, I want the receiver to test the new broker before fully
   committing it, so that an unreachable broker does not replace a working one.
5. As the owner, I want the receiver to automatically revert to the last
   working connection if it cannot connect within a bounded window, so that a
   bad change can never strand the phone off the network.
6. As the owner, I want the auto-revert to survive a process crash or reboot, so
   that a half-applied change is still recovered on the next start.
7. As the owner, I want to see the connection guardrail state (idle / pending /
   committed / reverted) in the admin console, so that I know whether a change took.
8. As the owner, I want the broker password never to be echoed in status or
   retrievable by the admin console, so that a leaked admin session does not leak
   the token.
9. As the owner, I want to tune MQTT keepalive from the admin console, so that I
   can adapt the link without a rebuild.
10. As the owner, I want to tune the MQTT connection timeout from the admin
    console, so that a slow link does not cause premature failures.
11. As the owner, I want to toggle automatic reconnect from the admin console, so
    that I can match the network's behaviour.
12. As the owner, I want to set the MQTT QoS and max inflight from the admin
    console, so that I can balance reliability and memory without a rebuild.
13. As the owner, I want to restart the receiver app from the admin console, so
    that I can clear a stuck state without physical access.
14. As the owner, I want to reboot the phone from the admin console, so that I
    can recover the device remotely.
15. As the owner, I want a restart/reboot command to be acknowledged in status,
    so that I can tell it was received before the device went away.
16. As the owner, I want to tune the message auto-size minimum and maximum from
    the admin console, so that long and short messages fit the way I want.
17. As the owner, I want to set the sender-name colour and the message colour
    from the admin console, so that contrast suits the room without a rebuild.
18. As the owner, I want to set the warning colour and countdown colour from the
    admin console, so that the screen reads clearly in the light of the room.
19. As the owner, I want to set the idle clock scale from the admin console, so
    that the date/clock screen is readable.
20. As the owner, I want to set the idle date format from the admin console, so
    that the date reads the way the resident expects.
21. As the owner, I want to override the connection-warning text from the admin
    console, so that I can use wording the resident understands.
22. As the owner, I want to toggle each device-owner hardening restriction from
    the admin console, so that I can relax or tighten the lockdown remotely.
23. As the owner, I want a failed or unsupported restriction change to be reported
    in status rather than silently ignored, so that I know the real state.
24. As the owner, I want the receiver to reject or clamp bad values as it does
    today, so that a malformed config cannot half-apply.
25. As the owner, I want the admin console to keep working over the current broker
    even while a broker change is being tested, so that I can observe the result.
26. As the owner, I want a clear confirmation step in the admin console before a
    broker/credential push, so that I do not do it by accident.
27. As the admin, I want the connection fields and layout fields grouped in the
    console, so that I can find them quickly.
28. As the admin, I want the console to show whether a password is set (without
    showing it), so that I know if I need to re-enter one.
29. As the owner, I want all existing behaviour (message, clear, cue, kiosk lock,
    telemetry) to keep working unchanged, so that the extension is safe.

## Implementation Decisions

### Wire schema — retained config stays schema 1, extended additively

- The retained `grandpa/config` payload keeps `"schema": 1`. New blocks are
  **optional**; a receiver that does not know them ignores them (forward
  compatible), and a receiver that knows them falls back to defaults when they are
  absent (backward compatible). This avoids rejecting configs between a new admin
  and an old receiver.
- Add an optional block for the extended layout:

```json
{
  "schema": 1,
  "display": {
    "name_size_sp": 46, "countdown": false, "orientation": "auto",
    "message_size_min_sp": 10, "message_size_max_sp": 64,
    "name_color": "#FFFAB005", "message_color": "#FFFFFFFF",
    "warn_color": "#FFFFB300", "countdown_color": "#FF9E9E9E",
    "idle_clock_scale": 1.3, "idle_date_format": "auto", "warn_text": null
  },
  "device_owner": {
    "status_bar_disabled": true,
    "lock_task": true,
    "restrict_accounts": true, "restrict_bluetooth": true,
    "restrict_location": true, "restrict_printing": true,
    "restrict_sync": true
  }
}
```

- Colours are `#AARRGGBB`; invalid values fall back to the current default.
  `idle_date_format` accepts a small enum plus `auto` (locale). `warn_text: null`
  keeps the localized string.
- New `device_owner` fields are booleans; missing → current default behaviour.
  `kiosk.locked` remains as-is and is not duplicated.
- Layout + restriction fields are validated/clamped in `RemoteConfigCodec`, the
  existing pure seam.

### Connection changes are a transactional command, never retained config

- A broker change carries a secret and needs a commit/revert lifecycle, so it does
  **not** go through `grandpa/config`. It is a command:

```json
{ "action": "set_connection",
  "candidate": {
    "host": "mqtt.example.com", "port": 8883, "use_tls": true,
    "username": "...", "password": "...",
    "keepalive_s": 30, "connect_timeout_s": 15, "auto_reconnect": true,
    "qos": 1, "max_inflight": 20
  },
  "revert_after_s": 90, "confirm_after_s": 15 }
```

- The password is stored only in device setup storage and is never serialized into
  retained config or status. Status reports `password_set: true/false`, host, port,
  TLS and the guardrail state — never the value.
- Topics and the client-id scheme stay constants (changing topics would strand the
  web admin and sender; the client id is derived from role + device id). Attempting
  them is out of scope.
- `revert_after_s` / `confirm_after_s` are clamped to safe ranges.

### New pure module `ConfigGuard` (the one genuinely new seam)

A JVM-testable state machine, mirroring the style of `PagerCore` (pure Kotlin, no
Android). It owns the connection-change lifecycle so the dangerous logic is not
tangled with Android networking. Shape:

```text
State:  Active | Pending(candidate, backup, since, revertAfter, confirmAfter) | ...
Events: Request(candidate) | ProbeOk | ProbeFail | Connected | Disconnected | Tick(now)
Output: effect  (KeepActive | StartProbe | SwitchTo(candidate) | Commit | RevertTo(backup) | ReportState)
```

- **Request** → persist `backup = active`, write candidate, record `since = now`,
  emit `StartProbe`.
- **ProbeOk** → `SwitchTo(candidate)`; start the confirm window.
- **ProbeFail** → `RevertTo(backup)` (nothing was switched; just report).
- **Connected** and stable for `confirm_after_s` → `Commit` (clear backup).
- **Tick** past `since + revert_after_s` without a committed connection →
  `RevertTo(backup)`.
- The pending state (candidate, backup, `since`) is **persisted**, so on process
  start / boot the machine resumes: a pending change that never confirmed is
  reverted.
- The machine is pure; the Android layer feeds it events and executes the emitted
  effects (probe, switch, revert, status publish).

### Device control commands

- Extend the command vocabulary parsed by `PagerJson.toCommand` (existing pure
  seam): add `set_connection`, `restart_app`, `reboot_device`. Unknown actions are
  ignored, as today.
- `restart_app`: stop and restart the foreground `PagerService` and recreate the
  receiver activity. Acknowledge in status first (best effort) so the admin sees
  receipt before the blip.
- `reboot_device`: call the device-owner reboot API. Only valid when the app is
  device owner; otherwise report `error: "not device owner"`. The reboot API is
  available on the supported minimum OS (API 24).
- Both are acknowledged via a status field, e.g.
  `command: { last, received_at, result }`.

### Device-owner restrictions become config-driven

- A pure mapping function turns the `device_owner` block into the desired
  restriction/feature set; `DeviceOwnerManager`'s Android call becomes a thin
  wrapper that applies it. Not device owner → report unsupported; never silently
  ignored.
- Removing the device owner itself is never a remote action (unchanged invariant).

### Admin console

- Adds a **Connection** section (host, port, TLS, username, password, keepalive,
  timeout, auto-reconnect, QoS, inflight) with a **confirmation step** and a visible
  **guardrail window**; shows guardrail state from status and `password_set` as a
  badge, never the password.
- Adds the extended **Layout** fields and the **Device owner** toggles.
- Adds **Restart app** and **Reboot device** buttons.
- The admin MQTT token's ACL must additionally allow **publish to
  `grandpa/command`** (Infra note; the token is minted outside the repo per
  `docs/flespi-setup.md`).

### Status / acknowledgement

`DiagnosticsPayload` gains a `connection` sub-object
(`state`, `host`, `port`, `use_tls`, `password_set`, `last_error`) and a
`command` sub-object (`last`, `received_at`, `result`). Secrets are never included.

## Testing Decisions

- **What makes a good test:** exercise the pure functions' external behaviour only
  (inputs → outputs / state transitions); never assert on Android calls or private
  internals.
- **Seams under test** (agreed up front):
  1. `RemoteConfigCodec` — new layout fields parse, clamp, default, and fall back
     on invalid colours/enums; secrets are never serialized.
  2. `PagerJson.toCommand` — new actions parse; unknown actions are ignored.
  3. `ConfigGuard` — the full transition table: request→probe→switch→commit;
     probe fail→revert; timeout→revert; and **resume-after-restart** reverts a
     pending, unconfirmed candidate.
  4. Restriction mapping — `device_owner` config block → desired restriction set.
- **Prior art:** `PagerCoreTest` (JUnit, plain asserts, no mocks) is the template;
  new tests are added alongside it or in sibling test files of the same style.
- The Android wrappers (actual reboot, actual `MqttController` reconnect, actual
  `DevicePolicyManager` calls) stay thin and are verified manually on-device via
  the runbook, not unit-tested.

## Out of Scope

- **Changing MQTT topics** — would strand the web admin and sender; topics stay
  constants.
- **Changing the client-id scheme** — stays derived from role + device id.
- **An arbitrary layout/template engine** — the owner chose to only tune the
  existing, already-hardcoded knobs; no HTML/Markdown/positional template system.
- **App OTA / self-update over MQTT.**
- **Flespi platform operations from the browser** (Master-only; stays off the page).
- **Per-admin tokens, config history / audit, config version history beyond
  `schema`.**
- **Wiring `defaults.duration_minutes` on the receiver** — it is inert today and is
  a sender-side concern; noted, not addressed here.
- **Removing / re-provisioning the device owner from admin.**

## Further Notes

- The broker change is the highest-risk operation in the whole system; the
  guardrail (probe + bounded auto-revert + persistence across reboot) is what makes
  it acceptable. The admin UI should make the revert window explicit and require an
  extra confirmation.
- Because the password can never be read back, rotating the broker password is a
  full re-entry, not an edit.
- The admin token ACL change (publish `grandpa/command`) is a prerequisite for the
  device-control commands and must land with, or before, the app change.
- Relationship to prior work: `.scratch/admin-mode/map.md` listed "Changing the
  broker URL or tokens over MQTT" as **out of scope**; this spec deliberately
  revisits that decision and supersedes it, gated by the guardrail above.
