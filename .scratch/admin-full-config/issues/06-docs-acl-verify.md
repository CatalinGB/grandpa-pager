# 06: Docs, ACL & full verification

**What to build:** The deployment documentation matches the new surface, the admin
token's ACL includes publishing commands, and the full build + test suite is green.

**Blocked by:** 01, 02, 03, 04, 05.

**Status:** done

- [x] `docs/architecture.md`, `docs/decisions.md`, `docs/runbook.md`, `docs/flespi-setup.md`, `README.md` and `SECURITY.md` describe the extended config, the guarded connection change and the new commands.
- [x] `docs/flespi-setup.md` documents the admin token ACL change (publish `grandpa/command`).
- [x] The receiver unit tests pass and the debug build compiles.
- [x] The research note `.scratch/grandpa-pager/research/receiver-admin-config-surface.md` is updated to reflect the now-closed gaps.
