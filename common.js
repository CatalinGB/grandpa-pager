/* Grandpa Pager — shared browser helpers, loaded by both the sender and the
 * admin page before their own scripts. Exposes a single `GP` global. */
(function (root) {
  "use strict";

  var GP = {};

  GP.escapeHtml = function (s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c];
    });
  };

  GP.uuid = function () {
    if (root.crypto && root.crypto.randomUUID) return root.crypto.randomUUID();
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0, v = c === "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  };

  /* A receiver is stale relative to its configured status cadence (default
     15 min): 2 intervals + 1 min, never below 90 s (decisions.md D13). */
  GP.staleAfterSeconds = function (d) {
    var m = (d && d.config && d.config.telemetry && d.config.telemetry.interval_minutes) || 15;
    return Math.max(90, m * 60 * 2 + 60);
  };

  /* Interpret a grandpa/status payload for the UI. `grandpa/status` is retained,
     so a page can open onto a snapshot from long ago; callers must not present
     its message as live unless `showLive` is true. `expired` means the message's
     expires_at is in the past — the phone just hasn't republished (offline), so
     the screen is not showing it any more. */
  GP.statusInfo = function (d, nowMs) {
    nowMs = nowMs || Date.now();
    if (!d) return { online: false, stale: true, showing: false, showLive: false, expired: false, remainingMs: null, ageSeconds: 0 };
    var seen = d.last_seen ? new Date(d.last_seen).getTime() : 0;
    var age = Math.max(0, (nowMs - seen) / 1000);
    var online = d.state !== "offline" && age < GP.staleAfterSeconds(d);
    var hasExpiry = !!d.expires_at;
    var remainingMs = hasExpiry ? (new Date(d.expires_at).getTime() - nowMs) : null;
    var expired = hasExpiry && remainingMs <= 0;
    var showing = d.state === "showing" && !!d.current_message_id;
    return {
      online: online,
      stale: !online,
      showing: showing,
      showLive: showing && online && !expired,
      expired: expired,
      remainingMs: remainingMs,
      ageSeconds: Math.round(age)
    };
  };

  /* Invitation links carry a token (and usually a name) in the URL fragment
     (preferred — never sent to the server, never leaked via Referer) or the
     query string. `aliases` maps a field to its accepted parameter names. */
  GP.readInvite = function (aliases, requireField) {
    var q = new URLSearchParams(root.location.search);
    var h = new URLSearchParams((root.location.hash || "").replace(/^#/, ""));
    function pick(list) {
      for (var i = 0; i < list.length; i++) {
        var v = q.get(list[i]); if (v == null) v = h.get(list[i]);
        if (v) return String(v).trim();
      }
      return "";
    }
    var out = {}, any = false;
    for (var field in aliases) {
      out[field] = pick(aliases[field]);
      if (out[field]) any = true;
    }
    if (requireField && !out[requireField]) return null;
    return any ? out : null;
  };

  /* Strip the given params from the address bar so they do not linger in
     history, the clipboard or a screenshot URL. */
  GP.clearUrlParams = function (keys) {
    if (!root.history.replaceState) return;
    var q = new URLSearchParams(root.location.search);
    var h = new URLSearchParams((root.location.hash || "").replace(/^#/, ""));
    keys.forEach(function (k) { q.delete(k); h.delete(k); });
    var qs = q.toString(), hs = h.toString();
    root.history.replaceState(null, "", root.location.pathname + (qs ? "?" + qs : "") + (hs ? "#" + hs : ""));
  };

  /* Choose a UI locale from an allow-list. `?lang=` wins and is remembered; then
     the remembered choice; then the browser/system language; else "en". */
  GP.detectLocale = function (supported) {
    function two(v) { return v ? String(v).slice(0, 2).toLowerCase() : ""; }
    function ok(v) { return supported.indexOf(two(v)) !== -1; }
    try {
      var q = two(new URLSearchParams(root.location.search).get("lang"));
      if (ok(q)) { root.localStorage.setItem("gp_lang", q); return q; }
      var saved = two(root.localStorage.getItem("gp_lang"));
      if (ok(saved)) return saved;
    } catch (e) {}
    var nav = two(root.navigator && root.navigator.language);
    return ok(nav) ? nav : "en";
  };

  /* ---------- PWA install affordance ----------
     Capture `beforeinstallprompt` at load time (before any page script runs) so
     an early event is never missed, then let a page mount an Install button —
     or, on browsers that cannot install (Firefox Android, iOS Safari), a short
     "how to add to home screen" hint. */
  GP._install = { deferred: null, subs: [] };
  GP.onInstallAvailable = function (fn) {
    if (GP._install.deferred) fn(GP._install.deferred);
    GP._install.subs.push(fn);
  };
  (function () {
    if (!root.addEventListener) return;
    root.addEventListener("beforeinstallprompt", function (e) {
      if (e && e.preventDefault) e.preventDefault();
      GP._install.deferred = e;
      GP._install.subs.forEach(function (fn) { try { fn(e); } catch (err) {} });
    });
    root.addEventListener("appinstalled", function () {
      GP._install.deferred = null;
      GP._install.subs.forEach(function (fn) { try { fn(null); } catch (err) {} });
    });
  })();

  /* Already launched as an installed app? */
  GP.standalone = function () {
    try {
      return (root.matchMedia && root.matchMedia("(display-mode: standalone)").matches) ||
        root.navigator.standalone === true;
    } catch (e) { return false; }
  };

  /* Fill `containerId` with an install button (Chromium) or a manual hint. */
  GP.setupInstall = function (containerId) {
    var box = root.document && root.document.getElementById(containerId);
    if (!box || GP.standalone()) return;
    var loc = GP.detectLocale(["en", "ro"]);
    var STR = {
      en: {
        title: "Install the app", install: "Install",
        ios: "Tap the Share button, then “Add to Home Screen”.",
        firefox: "Open the menu (⋮) and tap “Add to Home screen”. Firefox on Android can’t install standalone apps.",
        inapp: "You’re in an in-app browser. Open this page in Chrome or Brave, then use the browser menu to install.",
        insecure: "Installing needs a secure address. Open the app at its https:// URL — a local http:// address can’t be installed on Android.",
        menu: "Open the browser menu (⋮) and tap “Install app”."
      },
      ro: {
        title: "Instalează aplicația", install: "Instalează",
        ios: "Apasă butonul Partajează, apoi „Adaugă la ecran”.",
        firefox: "Deschide meniul (⋮) și apasă „Adaugă la ecran”. Firefox pe Android nu poate instala aplicații standalone.",
        inapp: "Ești într-un browser integrat. Deschide pagina în Chrome sau Brave, apoi folosește meniul browserului pentru a instala.",
        insecure: "Instalarea necesită o adresă securizată. Deschide aplicația la adresa ei https:// — o adresă locală http:// nu poate fi instalată pe Android.",
        menu: "Deschide meniul browserului (⋮) și apasă „Instalează aplicația”."
      }
    };
    function T(k) { return (STR[loc] && STR[loc][k]) || STR.en[k] || k; }
    var ua = (root.navigator && root.navigator.userAgent) || "";
    var isIOS = /iPad|iPhone|iPod/.test(ua) || (/Macintosh/.test(ua) && "ontouchend" in root.document);
    var isFirefox = /Firefox|FxiOS/.test(ua);
    var isAndroid = /Android/.test(ua);
    // In-app browsers (a link tapped inside a chat/social app) cannot install PWAs.
    var isInApp = /(FBAN|FBAV|FB_IAB|Instagram|Line\/|WhatsApp|Twitter|SnapChat|MicroMessenger|; wv\))/i.test(ua);

    function hint(text) {
      box.innerHTML = '<div class="label" style="margin-top:0">' + GP.escapeHtml(T("title")) + '</div>' +
        '<div class="hint">' + GP.escapeHtml(text) + '</div>';
      box.hidden = false;
    }
    function button() {
      box.innerHTML = '<div class="label" style="margin-top:0">' + GP.escapeHtml(T("title")) + '</div>' +
        '<button class="primary" id="gpInstallBtn" style="width:100%">' + GP.escapeHtml(T("install")) + '</button>';
      var b = root.document.getElementById("gpInstallBtn");
      if (b) b.addEventListener("click", function () {
        var d = GP._install.deferred;
        if (!d) return;
        b.disabled = true;
        try { d.prompt(); } catch (e) {}
        var p = d.userChoice;
        if (p && p.then) p.then(function () { GP._install.deferred = null; box.hidden = true; });
        else box.hidden = true;
      });
      box.hidden = false;
    }

    if (GP._install.deferred) { button(); return; }
    GP.onInstallAvailable(function (e) { if (e) button(); else box.hidden = true; });
    // A non-secure origin (http:// on a LAN IP) can never install on Android:
    // no service worker, and Chrome/Brave show "This app cannot be installed".
    if (root.isSecureContext === false) { hint(T("insecure")); return; }
    if (isInApp) { hint(T("inapp")); return; }
    if (isIOS) { hint(T("ios")); return; }
    if (isFirefox) { hint(T("firefox")); return; }
    // Chromium: the event should arrive; if not (and we are not installed),
    // fall back to the menu instructions after a moment.
    if (isAndroid) {
      root.setTimeout(function () { if (!GP._install.deferred && !GP.standalone()) hint(T("menu")); }, 3000);
    }
  };

  root.GP = GP;
})(window);
