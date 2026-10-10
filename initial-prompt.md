# Prompt: Design and Build a Remote Full-Screen Message Pager for an Elderly Relative

You are designing a practical, reliable system that turns an old Android phone into an **always-on, remotely controlled full-screen message pager** for an elderly relative (“Grandpa”). Research the current best options before proposing an implementation. Keep multiple viable approaches open rather than committing prematurely to one stack.

## Goal

The system must let me remotely send a text message that appears **full-screen** on Grandpa’s Android phone, with minimal or no possibility that he can accidentally interact with, dismiss, or navigate away from it.

Core requirements:

- Display a message full-screen in very large, high-contrast text.
- Messages must expire automatically; default duration should be **4 hours**, configurable per message.
- After expiry, the phone should return to a neutral idle screen.
- Grandpa should not be able to interact with the message, close the app, open other apps, or easily change settings.
- I must be able to monitor the device remotely: online/offline state, MQTT connection, battery/charging status, Wi-Fi status, current message state, and last heartbeat.
- I must be able to send or clear messages remotely.
- The system should be low-maintenance and resilient to phone reboots, temporary Wi-Fi loss, and app restarts.
- Prefer free or low-cost tools, open-source where practical, and solutions available in Romania/Europe.
- Avoid exposing insecure services directly to the public internet.

## User context

- I am technically capable: embedded software, Linux, Docker, self-hosting, Home Assistant, networking, MQTT, and basic mobile development.
- I prefer simple, robust systems over unnecessarily complex ones.
- I am comfortable with a small custom Android app if it is the cleanest solution.
- I already have a self-hosted home server and Home Assistant experience, but I would prefer to avoid maintaining a server if a simpler cloud/MQTT-based approach is better.
- The phone is an old Android device that will remain plugged in and connected to home Wi-Fi.

## Approaches to research and compare

Evaluate at least these approaches, including their tradeoffs:

1. **Pure MQTT approach**
   - Grandpa’s phone runs an MQTT client/display app.
   - My phone runs an MQTT publisher/dashboard app.
   - A free encrypted/private cloud MQTT broker relays messages.
   - Messages include an `expires_at` field enforced locally by the display app.
   - Diagnostics are published back over MQTT.

2. **Self-hosted web dashboard approach**
   - A small self-hosted web service stores active messages and expiry times.
   - Grandpa’s phone runs a locked kiosk browser pointed at the message page.
   - I access a private compose page remotely, for example via Tailscale.
   - The display page refreshes periodically and clears itself after expiry.

3. **Home Assistant-centered approach**
   - Home Assistant sends notifications or commands to an Android companion app.
   - Evaluate whether it can reliably produce a persistent full-screen message, prevent dismissal, and expire cleanly.
   - Evaluate Home Assistant Companion notification commands, persistent notifications, full-screen intent notifications, and their limitations.

4. **Commercial or semi-commercial kiosk approach**
   - Evaluate Fully Kiosk Browser, its free vs paid capabilities, remote management, MQTT/REST support, and licensing.
   - Evaluate other Android kiosk/MDM options, including open-source alternatives.

5. **Hybrid approaches**
   - For example: MQTT for messaging plus Android kiosk lockdown; or Home Assistant for monitoring plus a custom MQTT display app; or a web dashboard plus MQTT diagnostics.

For each approach, assess:

- Setup complexity
- Ongoing maintenance
- Reliability after reboot or network interruption
- Ability to prevent user interaction
- Remote monitoring quality
- Message expiry behavior
- Offline/reconnect behavior
- Security and privacy
- Cost
- Whether it works on old Android versions
- Whether it requires a custom app

## MQTT-specific requirements

If recommending MQTT, research and specify:

- A **free but encrypted** MQTT service. Prefer a private managed broker with TLS, username/password authentication, and access controls.
- Compare HiveMQ Cloud, EMQX Cloud, Flespi, public brokers, and self-hosted Mosquitto.
- Clearly distinguish public/test brokers from private brokers. Public brokers may expose messages to other users and should not be used for private family communication.
- Specify broker URL format, TLS port, authentication model, topic design, QoS, retained messages, and Last Will and Testament.
- Explain that MQTT message expiry may not be dependable across all clients or MQTT versions; prefer application-level expiry using an `expires_at` timestamp in the payload.
- Define a topic structure such as:
  - `grandpa/message` for incoming messages
  - `grandpa/status` for diagnostics
  - Optional command topic if remote clear/control is needed

Propose a JSON message schema including at least:

```json
{
  "id": "unique-message-id",
  "text": "Lunch is ready",
  "created_at": "ISO-8601 timestamp",
  "expires_at": "ISO-8601 timestamp"
}
```

And a diagnostics schema including:

```json
{
  "state": "online | showing | idle | offline | connection-lost",
  "battery": 0,
  "charging": false,
  "wifi": true,
  "mqtt": true,
  "current_message_id": "unique-message-id",
  "expires_at": "ISO-8601 timestamp",
  "last_seen": "ISO-8601 timestamp"
}
```

## Android display requirements

Research the best way to lock down an old Android phone for this use case:

- Kiosk mode
- Screen pinning
- Device owner / provisioning
- Default launcher replacement
- Keep screen on while charging
- Disable battery optimization
- Autostart after reboot
- Wi-Fi sleep policies
- Notification suppression
- Protection against leaving the app or changing settings

Compare:

- A custom Kotlin/Java MQTT display app
- Webview Kiosk or similar open-source kiosk browser
- Fully Kiosk Browser
- Tasker/MacroDroid-based solutions
- Home Assistant Companion app
- Any suitable existing open-source Android MQTT display/dashboard apps

If no existing app fully satisfies the requirement, specify the minimum custom Android app needed:

- MQTT over TLS
- Subscribe to message topic
- Parse JSON
- Show message full-screen
- Enforce expiry locally
- Return to idle screen
- Publish diagnostics
- Use MQTT Last Will for offline detection
- Reconnect automatically
- Survive reboot
- Remain locked/kiosk-like

Include a recommended Android minimum version and note compatibility constraints for old phones.

## Publisher/monitoring requirements

Research Android apps or methods I can use to:

- Publish messages to MQTT with TLS
- Set a TTL/expiry field
- Use retained messages
- View Grandpa’s status topic
- Receive alerts if the device goes offline
- Optionally integrate with Home Assistant, Node-RED, n8n, or another automation system

Compare at least:

- IoT MQTT Panel
- MQTT Client – Tasker & IoT&JS
- MQTT Dashboard
- Home Assistant MQTT integration
- Node-RED
- n8n
- Custom PWA/web interface

Recommend the simplest usable publisher interface for a non-technical family member if relevant.

## Deliverables

Produce:

1. **A decision matrix** comparing the viable approaches.
2. **A recommended architecture**, with a clear explanation of why it best balances simplicity, reliability, security, and maintainability.
3. **At least one simpler fallback architecture** if the primary recommendation proves too complex.
4. **A complete implementation plan**, including:
   - Exact apps/services to install
   - Broker/account setup
   - Topic and payload design
   - Android lockdown steps
   - Message publishing workflow
   - Expiry behavior
   - Remote monitoring setup
   - Failure modes and alerts
5. **Complete starter code or configuration** for the recommended solution:
   - If MQTT/custom app: provide Android project structure, Gradle dependencies, manifest highlights, MQTT service/client code, UI layout, expiry timer, diagnostics publisher, and Last Will configuration.
   - If web/kiosk: provide Docker Compose, backend code, HTML/CSS display page, compose page, and kiosk configuration.
   - If Home Assistant: provide YAML automations, notification payloads, helpers, and limitations.
6. **A testing checklist**, including:
   - Send message
   - Verify full-screen display
   - Test expiry after 4 hours
   - Test manual clear
   - Kill Wi-Fi and verify offline alert
   - Reboot phone and verify automatic recovery
   - Test retained-message delivery after reconnect
   - Test low battery / charging status reporting
   - Verify Grandpa cannot easily escape the app

## Constraints and preferences

- Prefer private TLS MQTT over public brokers.
- Prefer application-level expiry over relying solely on broker TTL.
- Prefer retained messages so the latest active message survives reconnects.
- Prefer automatic reconnection and reboot recovery.
- Prefer a solution that works even if my home server is offline, if practical.
- Do not make the message-composition interface publicly accessible without authentication.
- Do not rely on cloud services that require payment unless clearly justified and a free alternative is also provided.
- Be explicit about security limitations, especially for public MQTT brokers, remote-access exposure, and Android lockdown weaknesses.
- If a proposed tool is not truly open-source, free, or private, say so clearly.

Begin by researching the current state of the relevant Android apps, MQTT brokers, kiosk solutions, and Home Assistant capabilities. Then present a comparison, recommend the best architecture for this exact use case, and provide a complete implementation plan with working code/configuration.
