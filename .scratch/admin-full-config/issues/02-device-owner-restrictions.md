# 02: Device-owner restriction toggles

**What to build:** The admin can toggle each device-owner hardening restriction
(accounts, Bluetooth, location, printing, sync, status bar, lock task) from the
console, and the phone applies the resulting set, reporting unsupported/failed
changes in status instead of silently ignoring them.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] The retained config gains an optional `device_owner` block of booleans; absent keys keep today's defaults.
- [x] A pure mapping turns the block into the desired restriction/feature set (unit-tested), separate from the Android calls.
- [x] `DeviceOwnerManager` applies the desired set (add and clear) so relaxing a restriction works, not just tightening.
- [x] Not-device-owner is reported as unsupported in status, never silently ignored.
- [x] The admin console exposes toggles for each restriction.
