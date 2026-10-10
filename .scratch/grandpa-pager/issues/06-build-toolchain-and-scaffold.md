# 06 — Build toolchain + app scaffold

Type: task
Status: resolved
Blocked by:

## Question

How do we get a reproducible Android build with no root/Docker access?

## Answer

User-space toolchain: JDK 21 + Gradle 8.14.3 via `mise` (project-scoped
`.mise.toml`), Android SDK via `sdkmanager` into `~/Android/Sdk`
(`platforms;android-34`, `build-tools;34.0.0`). Kotlin 2.2.20, AGP 8.7.3, no
AndroidX (plain `android.*` widgets) to keep the build lean. `assembleDebug` +
`testDebugUnitTest` succeed (APK ~1.1 MB, 8 tests pass). Setup script archived at
`/tmp/opencode/setup-android.sh`; see `docs/runbook.md`.
