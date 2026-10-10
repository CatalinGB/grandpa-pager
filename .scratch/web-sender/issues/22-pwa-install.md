# 22 — Install as app (PWA)

Type: task
Status: resolved
Blocked by: 19

## Question

Let the sender be installed to the phone's home screen as an app from
Android Chrome/Firefox.

## Answer

**Install UI removed at the owner's request.** The install popup / instructions
are gone entirely (`#installBar`, its CSS, and all `beforeinstallprompt`/dismiss/
hint logic in `sender.js`). The page no longer nags about installing.

Kept for offline use: `manifest.webmanifest` + `sw.js` (service worker). The app
can still be installed from the browser's own menu/icon if the user chooses, but
the web app itself shows no install instructions.

Earlier behaviour (now removed): popup with a native install CTA on Chrome
Android and manual instructions on Firefox.

