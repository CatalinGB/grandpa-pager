# 03: Connection guardrail core

**What to build:** The pure logic that makes a remote broker/credential change safe:
a candidate connection model, the `set_connection` command, and a state machine that
probes, switches, commits on a stable connection, and reverts if the device does not
reconnect within a bounded window — including resuming across a process restart.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] A connection model holds host, port, TLS, username, password and MQTT tuning (keepalive, timeout, auto-reconnect, QoS, max inflight); parse clamps each.
- [x] `set_connection` parses from `grandpa/command`, carrying a candidate plus `revert_after_s` and `confirm_after_s` (clamped to safe ranges).
- [x] A pure `ConfigGuard` state machine handles Request → probe → switch → commit, probe-fail → revert, and timeout → revert, exposing the current guardrail state.
- [x] The pending state (candidate, backup, since) is expressed so it can be persisted and resumed after a restart; an unconfirmed pending change reverts.
- [x] Unit tests cover the full transition table and the resume-reverts case.
