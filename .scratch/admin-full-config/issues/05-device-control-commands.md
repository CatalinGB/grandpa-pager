# 05: Device control commands

**What to build:** The admin can restart the receiver app and reboot the phone from
the console, with each command acknowledged in status.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] `restart_app` and `reboot_device` parse from `grandpa/command`; unknown actions are still ignored.
- [x] `restart_app` restarts the foreground service and brings the receiver activity back.
- [x] `reboot_device` reboots via the device-owner API and reports "not device owner" when it cannot.
- [x] Each command's result is acknowledged in status (`last`, `received_at`, `result`).
- [x] The admin console has Restart app and Reboot device buttons with confirmation.
- [x] Unit tests cover parsing of the new actions and rejection of unknown ones.
