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
- **Firefox Android**: it doesn't fire `beforeinstallprompt`, so after 5 s a hint
  shows: *“meniul browserului (⋮) → Adaugă pe ecranul principal”*.
- “Mai târziu” dismisses it for 7 days; once installed it never nags again.
- `?install=1` force-shows the popup (dev/testing).
