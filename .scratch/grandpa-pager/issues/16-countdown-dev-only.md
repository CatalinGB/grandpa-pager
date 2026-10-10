# 16 — Hide the expiry countdown in production

Type: task
Status: resolved
Blocked by: 13

## Question

The receiver should not show the "clears in …" countdown by default; only in the
dev build.

## Answer

`buildFeatures { buildConfig = true }` exposes `BuildConfig.DEBUG`;
`ReceiverActivity` sets `countdownView` visible only when `BuildConfig.DEBUG`.
Verified on the phone: the **debug** build shows `se șterge în 59m`; the
**release** build shows no countdown. The release build is signed with the debug
key so it stays sideloadable.
