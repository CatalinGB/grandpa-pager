# 04: Connection change end-to-end

**What to build:** The admin can change the broker and MQTT tuning from the console;
the receiver probes the candidate, switches, and either commits or auto-reverts,
with the guardrail state visible in the console and the password never exposed.

**Blocked by:** 03 (connection guardrail core).

**Status:** done (device round-trip pending manual check)

- [x] `MqttController` can be reconfigured from a connection model rather than only the on-device setup values.
- [x] The receiver persists the active connection, a backup, and the pending state, and resumes/reverts it after a reboot.
- [x] The receiver probes the candidate before switching and reports the guardrail state (idle/pending/committed/reverted) plus `password_set` in status — never the password.
- [x] The admin console has a Connection section with a confirmation step, a visible revert window, and a `password_set` badge.
- [ ] An end-to-end change on the test broker commits; a deliberately bad host reverts.
