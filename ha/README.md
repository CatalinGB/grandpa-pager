# Home Assistant integration — Grandpa Pager

The pager is MQTT-native, so Home Assistant consumes it the same way the
`report-consumption` project does: a **Flespi ↔ home Mosquitto bridge**, retained
**MQTT discovery** entities, plus scripts/automations and a dedicated dashboard.

All of the HA objects below are already created on the instance
(`http://192.168.1.200:8123`, HA 2026.10.2), and the bridge is installed on the
Unraid host — see [Setup](#setup-the-bridge) if it ever has to be rebuilt.

## What was created

### Entities (MQTT discovery, device "Grandpa Pager")

| Entity | Meaning |
|---|---|
| `sensor.grandpa_pager_state` | `idle` / `showing` |
| `binary_sensor.grandpa_pager_mqtt_link` | app's broker link |
| `sensor.grandpa_pager_battery` / `binary_sensor.grandpa_pager_charging` | battery |
| `sensor.grandpa_pager_wi_fi` | Wi-Fi state |
| `sensor.grandpa_pager_current_message` / `sensor.grandpa_pager_current_sender` | what is on screen |
| `sensor.grandpa_pager_message_expires` | expiry time |
| `sensor.grandpa_pager_last_seen` | last status |
| `binary_sensor.grandpa_pager_device_owner` | kiosk owner present |
| `sensor.grandpa_pager_config_error` | rejected config, if any |
| `sensor.grandpa_pager_last_command` | app ack of the last command (e.g. `reboot_device: ok`) |
| `sensor.grandpa_pager_last_message` | newest history text |
| `sensor.grandpa_pager_history` | history count; `messages` attribute = full array |
| `button.grandpa_pager_clear_message` | clear the screen (empty retained `grandpa/message`) |
| `button.grandpa_pager_restart_app` | restart the app |
| `button.grandpa_pager_reboot_phone` | reboot the phone (device owner) |

Discovery payloads are in [`discovery/pager.json`](discovery/pager.json) and are
published **retained** under `homeassistant/.../config`. They are re-published by
`script.pager_publish_discovery`, which the start automation runs on every Home
Assistant start — a broker (or HA) restart therefore can no longer leave the
entities orphaned.

### Helpers

| Entity | Use |
|---|---|
| `input_text.pager_sender` | sender name shown on the phone (default "Home Assistant") |
| `input_text.pager_message` | message to send |
| `input_number.pager_duration` | minutes on screen (5–1440, default 240) |

### Scripts

`script.pager_send`, `script.pager_clear`, `script.pager_restart_app`,
`script.pager_reboot`, `script.pager_publish_discovery` — reference copies in
[`pager_scripts.yaml`](pager_scripts.yaml). The first four publish to
`grandpa/message` (JSON payload with `id`, `sender`, `text`, `created_at`,
`expires_at`, `duration_minutes`) and `grandpa/command`; the last one re-publishes
the retained discovery payloads (17 `mqtt.publish` calls) and is safe to run at
any time.

### Sending a message from HA

Three equivalent ways:

1. **Dashboard** — Pager tab → *Send a message* → fill From / Message / Minutes,
   tap the `Pager: send message` row.
2. **Automation / script** — call the script with fields (best for automation):
   ```yaml
   action: script.pager_send
   data:
     message: "Dinner is ready"
     sender: Casa          # optional, defaults to input_text.pager_sender
     duration_minutes: 90  # optional, defaults to input_number.pager_duration
   ```
   `message`, `sender` and `duration_minutes` are all optional; with no fields the
   script uses the dashboard helpers. A call **with** a `message` field does not
   clear the dashboard draft; a call from the form does.
3. **Developer Tools → Actions** — choose `script.pager_send` and fill the same
   fields.

The script is exposed like any other action, so any automation, script, or
conversation/LLM tool that can call scripts can send a message.

### Automations

`pager_automations.yaml`:

- **Pager — republish MQTT discovery on start** — 10 s after Home Assistant
  starts → runs `script.pager_publish_discovery`, so the entities come back even
  if the broker lost the retained discovery payloads.
- **Pager — restart app daily** — `00:00` every day → presses
  `button.grandpa_pager_restart_app`.
- **Pager — reboot phone every 3 days** — `00:00` on every third day → presses
  `button.grandpa_pager_reboot_phone`. No native "every N days" trigger exists,
  so the cadence is `{{ ((now().timestamp() // 86400) | int) % 3 == 0 }}`
  (epoch-day modulo; the phase is arbitrary but the interval is exactly 3 days).
  On a reboot day the phone also restarts the app, so the daily restart is
  redundant but harmless.

Times are local (`Europe/Bucharest`).

### Dashboard

**Grandpa Pager** (`/grandpa-pager`), two views:

- **Pager** — a Mushroom hero card (pigeon icon, state badge, colour-coded by the
  MQTT link), four status chips, an “On screen” quote, a quick send block
  (**inline Message** + From, a duration slider, and a Send tile — type, then tap
  Send), three action tiles (Clear / Restart / Reboot, reboot with confirmation)
  and a styled recent-messages list.
- **Web pages** — the sender and admin pages embedded as `iframe` cards
  (`https://catalingb.github.io/grandpa-pager/` and `…/admin.html`). Enter the
  token once inside each frame; it is stored for the GitHub Pages origin.

The polished cards use **Mushroom** (installed, v5.2.3); the resources are already
registered, so no setup is needed. After a layout change, hard-refresh the
frontend to pick it up.

## Setup the bridge

1. **Create a Flespi token** (Flespi UI → Tokens) named `grandpa-pager-home`:
   ```json
   {
     "name": "grandpa-pager-home",
     "access": {
       "type": 2,
       "acl": [
         { "uri": "mqtt", "topic": "grandpa/#", "actions": ["publish", "subscribe"] }
       ]
     },
     "origins": [],
     "ttl": 31536000,
     "expire": 0
   }
   ```
   It is a server-side token, not used by any browser (`origins: []`).
   The ACL must cover **publish** on `grandpa/message` as well as
   `grandpa/command`: Flespi drops an unauthorised publish silently, so the
   symptom is "Restart/Reboot work, Send/Clear do nothing".

2. **Install the Mosquitto bridge.** Copy
   [`flespi-bridge-grandpa.conf.example`](flespi-bridge-grandpa.conf.example) to
   the Mosquitto extensions directory, set `remote_username` to the token, then
   restart Mosquitto:
   - host (Unraid): `/mnt/secondpool/appdata/mosquitto/config/extensions/flespi-grandpa.conf`
   - container: `/mosquitto/config/extensions/flespi-grandpa.conf`

3. **Verify.** Within a minute the retained `grandpa/status` should populate the
   sensors. `mosquitto_sub -h 192.168.1.200 -t 'grandpa/#' -v` (from the host)
   shows the traffic.

## Testing without waiting for 00:00

Run the scripts from the dashboard, or `script.pager_restart_app` in Developer
Tools. To test the schedule, run the automation's actions manually; for the
3-day one, temporarily remove the condition.

## Notes / caveats

- **Bridge direction:** split on purpose. `in`: `grandpa/status`, `grandpa/last`,
  `grandpa/config` (what HA reads). `both`: `grandpa/message` (HA sends and sees
  messages). `out`: `grandpa/command` only. `grandpa/last` is **never** bridged
  out — a stale local copy could overwrite the real history array on Flespi; the
  web sender stays its only writer. Discovery topics live under
  `homeassistant/`, so they stay on the home broker.
- **Sending from HA does nothing, but Restart/Reboot work?** The bridge line is
  right (`topic grandpa/message both 1`) — the **Flespi token ACL is the
  problem**. Measured on 2026-10-25: the `grandpa-pager-home` token can
  *subscribe* to `grandpa/message` (family messages reach HA) and *publish* to
  `grandpa/command` (buttons work), but a publish to `grandpa/message` is
  silently dropped by Flespi (MQTT 3.1.1 has no negative PUBACK, so Mosquitto
  reports nothing).
  Fix in the Flespi UI: Tokens → `grandpa-pager-home` → allow **publish** on
  `grandpa/message` (or `grandpa/#`), then restart Mosquitto. Verify by sending
  from the dashboard and watching the phone.
  **Do not** work around it with a second bridge for the outbound direction:
  with `message in` on one connection and `message out` on another, Mosquitto
  echoes the topic between them and floods the phone; Flespi also refuses the
  extra session (`Connection Refused: not authorised`).
- **History:** the web sender is the writer of `grandpa/last`. A message sent
  from HA is shown on the phone, but it only enters the history array if a web
  sender page is open at that moment. The receiver does not care.
- **Entity IDs** are device-prefixed (`sensor.grandpa_pager_state`, etc.) because
  MQTT discovery used the device name; the dashboard references those exact IDs.
- **Everything shows `unavailable`?** The retained discovery payloads are missing
  from the home broker. Run `script.pager_publish_discovery`; the start automation
  does it automatically after the next HA restart. To make it permanently
  unnecessary, give Mosquitto `persistence true` and a `persistence_location` on a
  mapped volume so retained messages survive a broker restart.
- **`sensor.grandpa_pager_last_seen` stops advancing** while the phone is on: the
  app has stopped publishing (it can hang after days of uptime). Press
  `button.grandpa_pager_restart_app` (or the daily automation) — the command is
  acked within seconds and the status cadence resumes.
- **Reboot** requires the receiver to be device owner; otherwise the command is
  acknowledged as failed and the phone stays up.
- The iframes require the GitHub Pages CSP to allow framing — it does (no
  `frame-ancestors`), and HTTPS-in-HTTP is fine.
