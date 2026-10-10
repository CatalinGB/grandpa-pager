# 22 — Install as app (PWA)

Type: task
Status: resolved
Blocked by: 19

## Question

Let the sender be installed to the phone's home screen as an app from
Android Chrome/Firefox.

## Answer

The web sender is now a **PWA**:

- `manifest.webmanifest` — `Grandpa Pager`, `display: standalone`, amber theme,
  maskable + standard icons (`icon-192/512`, `icon-maskable-512`).
- `sw.js` — service worker (network-first with cache fallback) so the app is
  installable and works offline for the shell.
- `index.html` — `<link rel="manifest">`, `theme-color`, `apple-touch-icon`,
  and a bottom **install popup** (`#installBar`).

Behaviour:
- **Chrome/Edge Android**: `beforeinstallprompt` → the popup shows; “Instalează”
  triggers the native install (Add to Home screen).
- **Firefox Android**: it doesn't fire `beforeinstallprompt`, so the popup shows
  the manual path: **⋮ → „Mai multe” → „Adaugă pe ecranul principal”**.
- The popup appears **only on mobile** (it’s pointless on desktop and caused the
  “This app can’t be installed” confusion there).
- **Dismissal is permanent** (`gp_install_dismissed`); once dismissed or the hint
  shown once (`gp_hint_shown`) or installed, it never returns.
- The service worker caches each asset independently so install can’t fail on one
  404, and registers immediately.
- `?install=1` force-shows the CTA, `?install=help` the instructions (dev/testing).
