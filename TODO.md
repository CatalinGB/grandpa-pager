# TODO

Backlog / known issues that are not yet resolved.

## PWA install fails on Android Chrome/Brave ("This app cannot be installed")

**Status:** open — site-side verified good; blocked on device-side diagnosis.

**Symptom:** On Android, Chrome and Brave show *"This app cannot be installed"* and
only offer a home-screen **shortcut**. The same URL installs fine in desktop
Chrome (Linux). Reproduced by the owner on the GitHub Pages HTTPS site.

**Verified good (site side, 2026-10):**
- `https://catalingb.github.io/grandpa-pager/` serves the up-to-date build
  (`sender.js?v=15`, `common.js?v=4`).
- Manifest is complete: `name`/`short_name`, `start_url`, `display: standalone`,
  `prefer_related_applications: false`, 192 + 512 icons (`purpose: any` + maskable).
- Icons are **8-bit PNG** (were 16-bit — re-encoded; WebAPK minting rejects 16-bit).
- Service worker registers and is active; manifest served as
  `application/manifest+json`; HTTPS.
- Chrome CDP `Page.getInstallabilityErrors` → `[]` for `index.html` and
  `admin.html`, including with an Android Chrome UA override.
- Meets all criteria in https://web.dev/articles/install-criteria.

**Conclusion:** the app meets Chrome's installability criteria, so the failure is
device/browser side, not the app.

**Remaining hypotheses (device side):**
- Chrome/Brave install PWAs as a **WebAPK minted via Google Play services**; if
  Play services is missing/disabled, or a **DNS/adblock/firewall filter blocks
  Google's endpoints** (common on self-hosted/pi-hole/AdGuard setups), minting
  fails → shortcut only.
- Opened inside an **in-app browser / Custom Tab** (link tapped in a chat app).
- **Enterprise / parental policy or a work profile** disabling installs.
- **Older Chrome** tripping on **relative** `start_url`/`scope` in WebAPK minting.

**Next steps:**
1. On the phone, enable USB debugging → desktop Chrome `chrome://inspect/#devices`
   → inspect the tab → **Application → Manifest** → read the exact
   installability errors. Paste them here.
2. Quick discriminator: install **https://airhorner.com/** on the same phone. If
   that also fails, it's device/account/network; if it works, keep digging on our
   side.
3. Try **absolute** `start_url` / `scope` / explicit `id` in the manifests
   (`/grandpa-pager/…`) as an older-Chrome workaround. Safe for the current
   fixed GitHub Pages path, but drops portability (custom domain / different
   repo name would need edits).

**Related uncommitted change:** `common.js` now shows an "installing needs a
secure https:// address" hint when `window.isSecureContext === false` (the
`http://` case that produces the same Android message). This does **not** apply to
the owner's case (they are on HTTPS) but is a good guard — commit/push when
convenient.
