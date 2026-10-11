# Grandpa Pager — Admin full config — Wayfinder Map

> Extends `.scratch/admin-mode/` (which left broker/tokens over MQTT explicitly
> out of scope). Spec: [`spec.md`](spec.md). Tickets: [`issues/`](issues/).

## Destination

Every receiver setting the admin might need after deployment — layout, device-owner
restrictions, broker/credentials, MQTT tuning, restart/reboot — is reachable from
the token-gated admin console over MQTT, without physical access to the in-situ
phone. Connection changes are guarded by probe + bounded auto-revert so a bad push
can never strand the device.

## Standing constraints (from the spec)

- Retained `grandpa/config` stays `schema: 1` and grows optional blocks; no secrets.
- Connection changes are a transactional `set_connection` command, never retained
  config; password never echoed in status.
- Topics and the client-id scheme stay constants.
- No layout/template engine — only tune already-existing knobs.

## Tickets

<!-- index: one line per ticket -->

- [01 Extend remote layout knobs](issues/01-layout-knobs.md) — done
- [02 Device-owner restriction toggles](issues/02-device-owner-restrictions.md) — done
- [03 Connection guardrail core](issues/03-connection-guardrail-core.md) — done
- [04 Connection change end-to-end](issues/04-connection-change-e2e.md) — done; device round-trip is a manual check (blocked by 03)
- [05 Device control commands](issues/05-device-control-commands.md) — done
- [06 Docs, ACL & full verification](issues/06-docs-acl-verify.md) — done (blocked by 01–05)

## Built & shipped

- Retained `grandpa/config` (schema 1) extended: message auto-size bounds,
  colours, idle clock/date format, warning text, and the `device_owner` toggle
  block. Mirror in `RemoteConfig.kt` + `admin.js`.
- Guarded `set_connection` command (`ConnectionConfig.kt`, `ConfigGuard.kt`,
  `PagerService`): probe → switch → commit or auto-revert, persisted across
  reboot; password never echoed. Console Connection section added.
- `restart_app` / `reboot_device` commands + console buttons, acked in
  `status.command`.
- 32 JVM unit tests green; debug APK builds.

## Remaining (manual)

- End-to-end on the test broker from the real device: a good candidate commits, a
  deliberately bad host reverts (ticket 04's last criterion). Not run here to
  avoid reconfiguring the connected in-situ phone.
- Mint/update the `grandpa-admin` ACL token to include `publish grandpa/command`
  (`docs/flespi-setup.md`).

