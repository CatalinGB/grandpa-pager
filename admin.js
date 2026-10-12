/* Grandpa Pager — admin mode. Publishes a versioned config to grandpa/config
 * (retained) with the admin token, and reads the receiver's status back to
 * prefill. The token is kept in localStorage so the console stays signed in
 * across tabs and restarts (the phone may be out of physical reach). */
(function () {
  "use strict";
  var BROKER = "wss://mqtt.flespi.io:443";
  var TOPIC = { config: "grandpa/config", command: "grandpa/command", status: "grandpa/status", message: "grandpa/message", last: "grandpa/last" };
  var DEF = {
    schema: 1,
    display: {
      name_size_sp: 58, countdown: false, orientation: "auto",
      message_size_min_sp: 10, message_size_max_sp: 64,
      name_color: "#FFFAB005", per_sender_colors: true, message_color: "#FFFFFFFF",
      warn_color: "#FFFFB300", countdown_color: "#FF9E9E9E",
      idle_clock_scale: 1.3, idle_date_format: "auto", warn_text: null
    },
    cue: { glow_ms: 9000, chime: true, vibrate: false, torch: { enabled: true, on_ms: 1500, off_ms: 1500, pulses: 3 } },
    defaults: { duration_minutes: 240, history_count: 10 },
    telemetry: { interval_minutes: 15 },
    power: { screen_off_when_idle: true },
    kiosk: { locked: true },
    device_owner: {
      status_bar_disabled: true, lock_task: true,
      restrict_accounts: true, restrict_bluetooth: true,
      restrict_location: true, restrict_printing: true, restrict_sync: true,
      restrict_mobile_networks: true, restrict_tethering: true, restrict_roaming: true
    }
  };
  var el = function (id) { return document.getElementById(id); };
  var cfg = JSON.parse(JSON.stringify(DEF));
  var dirty = false, client = null, lastAttempt = 0, pendingOk = null, revertTimer = null;
  var lastMsgSender = null, lastMsgText = null, lastStatusObj = null, mqttUp = false;
  /* Message history, read from the retained grandpa/last array the web sender
     maintains. The admin is read-only here (its token has no publish on
     grandpa/last). */
  var history = [], historyCount = 10;

  /* ---------- i18n (system language; English fallback) ---------- */
  var I18N = {
    en: {
      unlockIntro: "Enter the admin token to configure the pager.",
      tokenPhAdmin: "admin token",
      connectBtn: "Connect",
      unlockHint: "The token is saved in this browser (localStorage).",
      connConnected: "connected", connPhoneOffline: "phone offline", connDisconnected: "disconnected",
      secPreview: "Live preview", unsaved: "Unsaved changes",
      secDisplay: "Display", secCue: "New-message cue", secMessages: "Messages", secTelemetry: "Telemetry",
      secPower: "Power", secKiosk: "Kiosk", secDeviceOwner: "Device owner",
      resetBtn: "Reset", applyBtn: "Apply to phone", jsonLabel: "JSON",
      secInvite: "Invite a sender",
      inviteIntro: "Build a one-tap link (and QR) that carries the sender token, so a family member is ready after opening it.",
      inviteToken: "Sender token", tokenPhSender: "sender token", inviteName: "Name (optional)",
      inviteBroker: "Broker", inviteBuild: "Build invite", copyBtn: "Copy link",
      inviteHintEmpty: "Enter the sender token first.", inviteCopied: "Link copied.",
      inviteNoQr: "QR unavailable — share the link instead.",
      secConn: "Connection (guarded)",
      connIntro: "A broker/credential change is transactional: the phone tests it, then commits or auto-reverts within the window.",
      connHost: "Host", connPort: "Port", connTls: "Use TLS", connUser: "Username", connPass: "Password",
      connKeep: "Keepalive (s)", connTimeout: "Timeout (s)", connAuto: "Auto-reconnect", connQos: "QoS",
      connInflight: "Max inflight", connRevert: "Revert after (s)", connChange: "Change connection…",
      secActions: "Device actions", restartApp: "Restart app", rebootDevice: "Reboot device",
      cancelBtn: "Cancel", okBtn: "OK",
      lblOrientation: "Orientation", lblNameSize: "Name size", lblCountdown: "Show received/expires time",
      lblMsgMin: "Message size min", lblMsgMax: "Message size max", lblNameColor: "Name colour",
      lblPerSender: "Colour names per sender", descPerSender: "a distinct colour per sender; off = the one colour above",
      lblMsgColor: "Message colour", lblWarnColor: "Warning colour", lblCountdownColor: "Countdown colour",
      lblIdleScale: "Idle clock scale", lblIdleDate: "Idle date format", lblWarnText: "Warning text",
      phWarnText: "default (localized)",
      lblGlow: "Glow", lblSound: "Sound", lblVibration: "Vibration", lblTorch: "Torch",
      lblOn: "On", lblOff: "Off", lblPulses: "Pulses",
      lblDuration: "Default duration", lblHistoryCount: "History count", lblStatusInterval: "Status interval (min)",
      lblScreenOff: "Screen off when idle", descScreenOff: "sleep the display; wake on a new message",
      lblKiosk: "Locked (kiosk)", descKiosk: "off = open the launcher",
      lblLockTask: "Lock task", descLockTask: "configure full kiosk lock task", lblHideStatus: "Hide status bar",
      lblBlockAccounts: "Block account changes", lblDisableBt: "Disable Bluetooth", lblBlockLocation: "Block location",
      lblBlockPrinting: "Block printing", lblDisableSync: "Disable auto-sync",
      lblBlockMobile: "Block mobile-network config", lblBlockTethering: "Block tethering", lblBlockRoaming: "Block data roaming",
      enterToken: "Enter the token.", connecting: "Connecting…", errPrefix: "Error: ",
      notConnected: "You are not connected.", enterHost: "Enter a host.",
      appliedLoaded: "current config loaded from the phone", appliedSent: "Sent — the phone will apply it and confirm in status.",
      connSent: "Sent — the phone will test it and commit or revert within {n}s.",
      connTesting: "Testing… {n}s left", connDone: "Done — check the state above.",
      actionRequested: "{action} requested.",
      configError: "Config error from the phone: {msg}",
      battery: "battery", charging: "charging", onBattery: "on battery", wifi: "wifi", mqtt: "mqtt",
      deviceOwner: "device owner", message: "message", seenAgo: "{s}s ago", stale: "(stale)",
      online: "connected", offline: "offline",
      lastMessage: "Last message", history: "Message history", showHistory: "Show last {n} messages",
      justNow: "just now", minutesAgo: "{n} min ago", hoursAgo: "{n} h ago", daysAgo: "{n} d ago",
      connStateLabel: "state", passwordSet: "password set", passwordNotSet: "password not set",
      dlgChangeConnTitle: "Change the receiver's broker?",
      dlgChangeConnBody: "It will test the new connection and revert automatically within {n}s if it cannot connect.",
      dlgRestartTitle: "Restart the receiver app?", dlgRestartBody: "The screen will briefly go blank while it restarts.",
      dlgRebootTitle: "Reboot the phone?", dlgRebootBody: "It will be offline for about a minute."
    },
    ro: {
      unlockIntro: "Introdu tokenul de admin pentru a configura pagerul.",
      tokenPhAdmin: "token admin",
      connectBtn: "Conectare",
      unlockHint: "Tokenul este salvat în acest browser (localStorage).",
      connConnected: "conectat", connPhoneOffline: "telefon offline", connDisconnected: "deconectat",
      secPreview: "Previzualizare", unsaved: "Modificări nesalvate",
      secDisplay: "Afișaj", secCue: "Semnal mesaj nou", secMessages: "Mesaje", secTelemetry: "Telemetrie",
      secPower: "Energie", secKiosk: "Kiosk", secDeviceOwner: "Proprietar dispozitiv",
      resetBtn: "Resetează", applyBtn: "Aplică pe telefon", jsonLabel: "JSON",
      secInvite: "Invită un expeditor",
      inviteIntro: "Creează un link cu un singur tap (și cod QR) care conține tokenul de expeditor, ca un membru al familiei să fie gata după deschidere.",
      inviteToken: "Token expeditor", tokenPhSender: "token expeditor", inviteName: "Nume (opțional)",
      inviteBroker: "Broker", inviteBuild: "Creează invitația", copyBtn: "Copiază linkul",
      inviteHintEmpty: "Introdu mai întâi tokenul de expeditor.", inviteCopied: "Link copiat.",
      inviteNoQr: "Codul QR nu este disponibil — distribuie linkul.",
      secConn: "Conexiune (protejată)",
      connIntro: "O schimbare de broker/credențiale este tranzacțională: telefonul o testează, apoi o confirmă sau revine automat în fereastra dată.",
      connHost: "Host", connPort: "Port", connTls: "Folosește TLS", connUser: "Utilizator", connPass: "Parolă",
      connKeep: "Keepalive (s)", connTimeout: "Timeout (s)", connAuto: "Reconectare automată", connQos: "QoS",
      connInflight: "Max inflight", connRevert: "Revenire după (s)", connChange: "Schimbă conexiunea…",
      secActions: "Acțiuni dispozitiv", restartApp: "Repornește aplicația", rebootDevice: "Repornește telefonul",
      cancelBtn: "Anulează", okBtn: "OK",
      lblOrientation: "Orientare", lblNameSize: "Mărime nume", lblCountdown: "Arată ora primirii/expirării",
      lblMsgMin: "Mărime mesaj min", lblMsgMax: "Mărime mesaj max", lblNameColor: "Culoare nume",
      lblPerSender: "Culori pe expeditor", descPerSender: "o culoare distinctă per expeditor; oprit = culoarea de mai sus",
      lblMsgColor: "Culoare mesaj", lblWarnColor: "Culoare avertizare", lblCountdownColor: "Culoare numărătoare",
      lblIdleScale: "Scală ceas inactiv", lblIdleDate: "Format dată inactiv", lblWarnText: "Text avertizare",
      phWarnText: "implicit (localizat)",
      lblGlow: "Strălucire", lblSound: "Sunet", lblVibration: "Vibrație", lblTorch: "Lanternă",
      lblOn: "Aprins", lblOff: "Stins", lblPulses: "Pulsuri",
      lblDuration: "Durată implicită", lblHistoryCount: "Număr istoric", lblStatusInterval: "Interval status (min)",
      lblScreenOff: "Stinge ecranul când e inactiv", descScreenOff: "adoarme ecranul; se trezește la un mesaj nou",
      lblKiosk: "Blocat (kiosk)", descKiosk: "oprit = deschide launcherul",
      lblLockTask: "Blochează task", descLockTask: "configurare completă kiosk lock task", lblHideStatus: "Ascunde bara de status",
      lblBlockAccounts: "Blochează schimbarea conturilor", lblDisableBt: "Dezactivează Bluetooth", lblBlockLocation: "Blochează locația",
      lblBlockPrinting: "Blochează tipărirea", lblDisableSync: "Dezactivează sincronizarea",
      lblBlockMobile: "Blochează config. rețele mobile", lblBlockTethering: "Blochează tethering", lblBlockRoaming: "Blochează roaming date",
      enterToken: "Introdu tokenul.", connecting: "Se conectează…", errPrefix: "Eroare: ",
      notConnected: "Nu ești conectat.", enterHost: "Introdu un host.",
      appliedLoaded: "configurația curentă a fost încărcată de pe telefon", appliedSent: "Trimis — telefonul o va aplica și va confirma în status.",
      connSent: "Trimis — telefonul o va testa și va confirma sau reveni în {n}s.",
      connTesting: "Se testează… {n}s rămase", connDone: "Gata — verifică starea de mai sus.",
      actionRequested: "{action} solicitat.",
      configError: "Eroare de configurare de la telefon: {msg}",
      battery: "baterie", charging: "se încarcă", onBattery: "pe baterie", wifi: "wifi", mqtt: "mqtt",
      deviceOwner: "proprietar dispozitiv", message: "mesaj", seenAgo: "acum {s}s", stale: "(învechit)",
      online: "conectat", offline: "offline",
      lastMessage: "Ultimul mesaj", history: "Istoric mesaje", showHistory: "Arată ultimele {n} mesaje",
      justNow: "chiar acum", minutesAgo: "acum {n} min", hoursAgo: "acum {n} h", daysAgo: "acum {n} z",
      connStateLabel: "stare", passwordSet: "parolă setată", passwordNotSet: "parolă nesetată",
      dlgChangeConnTitle: "Schimbi brokerul receptorului?",
      dlgChangeConnBody: "Va testa noua conexiune și va reveni automat în {n}s dacă nu se poate conecta.",
      dlgRestartTitle: "Repornești aplicația receptorului?", dlgRestartBody: "Ecranul va deveni scurt negru în timpul repornirii.",
      dlgRebootTitle: "Repornești telefonul?", dlgRebootBody: "Va fi offline circa un minut."
    }
  };
  var LOCALE = GP.detectLocale(["en", "ro"]);
  function tr(key, params) {
    var s = (I18N[LOCALE] && I18N[LOCALE][key]) || I18N.en[key] || key;
    if (params) s = s.replace(/\{(\w+)\}/g, function (_, k) { return params[k] != null ? params[k] : ""; });
    return s;
  }
  function applyStatic() {
    document.documentElement.lang = LOCALE;
    Array.prototype.forEach.call(document.querySelectorAll("[data-i18n]"), function (n) {
      n.textContent = tr(n.getAttribute("data-i18n"));
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-i18n-ph]"), function (n) {
      n.setAttribute("placeholder", tr(n.getAttribute("data-i18n-ph")));
    });
    var label = LOCALE === "en" ? "RO" : "EN";
    [el("langBtn"), el("langBtn0")].forEach(function (b) { if (b) b.textContent = label; });
  }
  function setLocale(loc) {
    LOCALE = loc;
    try { localStorage.setItem("gp_lang", loc); } catch (e) {}
    applyStatic();
    build();
    renderHistory();
    if (lastStatusObj) renderDevice(lastStatusObj);
    updateConnPill();
  }
  function toggleLocale() { setLocale(LOCALE === "en" ? "ro" : "en"); }

  /* The history count rides in the receiver config (defaults.history_count) and
     is published in grandpa/config, which the sender pages subscribe to — so it
     reaches every device. The receiver drops unknown fields, so it is not echoed
     in status.config; the localStorage mirror below is only a same-origin
     fallback for the case where the sender's config grant is not yet in place. */
  function storedHistoryCount() {
    try { var n = parseInt(localStorage.getItem("gp_history_count"), 10); return isFinite(n) ? Math.max(1, Math.min(50, n)) : null; } catch (e) { return null; }
  }
  function applyStoredHistoryCount(c) {
    var n = storedHistoryCount();
    if (n != null && c && c.defaults) c.defaults.history_count = n;
  }
  applyStoredHistoryCount(cfg);
  historyCount = (cfg.defaults && cfg.defaults.history_count) || 10;
  function parseHistory(raw) {
    function ok(m) { return !!m && typeof m === "object" && (m.id || m.text); }
    try {
      var o = raw ? JSON.parse(raw) : null;
      if (!o) return [];
      if (Array.isArray(o)) return o.filter(ok);
      if (Array.isArray(o.messages)) return o.messages.filter(ok);
      if (o.text || o.sender) return [o]; // legacy single-object payload
    } catch (e) {}
    return [];
  }
  function relativeTime(iso) {
    if (!iso) return "";
    var t0 = new Date(iso).getTime();
    if (!isFinite(t0)) return String(iso);
    var s = Math.max(0, Math.round((Date.now() - t0) / 1000));
    if (s < 60) return tr("justNow");
    var m = Math.round(s / 60);
    if (m < 60) return tr("minutesAgo", { n: m });
    var h = Math.round(m / 60);
    if (h < 24) return tr("hoursAgo", { n: h });
    var d = Math.round(h / 24);
    if (d < 7) return tr("daysAgo", { n: d });
    return new Date(t0).toLocaleDateString();
  }
  function renderHistory() {
    var card = el("historyCard"), list = el("history"), link = el("historyLink");
    if (!card || !list) return;
    var n = Math.max(1, Math.min(50, historyCount || 10));
    var shown = history.slice(0, n);
    if (!shown.length) {
      card.hidden = true;
      if (link) link.hidden = true;
      renderLastRow();
      return;
    }
    card.hidden = false;
    if (link) { link.hidden = false; link.textContent = tr("showHistory", { n: shown.length }); }
    list.innerHTML = shown.map(function (m) {
      return '<div class="histitem"><div class="histmeta"><b>' + GP.escapeHtml(m.sender || "?") +
        '</b><span>' + GP.escapeHtml(relativeTime(m.created_at)) + '</span></div>' +
        '<div class="histtext">' + GP.escapeHtml(m.text || "") + '</div></div>';
    }).join("");
    renderLastRow();
  }
  function renderLastRow() {
    var row = el("lastRow"), out = el("lastMsg");
    if (!row || !out) return;
    var m = history[0];
    if (!m || !m.text) { row.hidden = true; return; }
    row.hidden = false;
    out.innerHTML = GP.escapeHtml(m.text) + ' <span class="when">— ' +
      GP.escapeHtml(m.sender || "?") + ", " + GP.escapeHtml(relativeTime(m.created_at)) + "</span>";
  }

  /* The admin token is persisted in localStorage (the phone is often out of
     physical reach, so re-entering it on every tab is painful). A token left in
     sessionStorage by an older build is migrated once. */
  function loadAdminToken() {
    try { return localStorage.getItem("gp_admin") || sessionStorage.getItem("gp_admin") || ""; } catch (e) { return ""; }
  }
  function storeAdminToken(t) {
    try { localStorage.setItem("gp_admin", t); sessionStorage.removeItem("gp_admin"); } catch (e) {}
  }

  /* Shared helper (common.js). */
  var staleAfterSeconds = GP.staleAfterSeconds;

  var get = function (path) { return path.split(".").reduce(function (o, k) { return o[k]; }, cfg); };
  function setDirty(v) { dirty = v; if (el("dirtyBadge")) el("dirtyBadge").hidden = !v; }
  function setPath(path, v) { var p = path.split("."); var o = cfg; for (var i = 0; i < p.length - 1; i++) o = o[p[i]]; o[p[p.length - 1]] = v; setDirty(true); }
  /* Deep-merge a (possibly older) phone config over the defaults, so fields the
     receiver never sent — e.g. per_sender_colors on an older build — still show
     their true default instead of an undefined/"off" control. */
  function deepMerge(base, over) {
    if (!over || typeof over !== "object") return base;
    Object.keys(over).forEach(function (k) {
      var v = over[k];
      if (v && typeof v === "object" && !Array.isArray(v)) {
        base[k] = deepMerge(base[k] && typeof base[k] === "object" ? base[k] : {}, v);
      } else if (v !== undefined) {
        base[k] = v;
      }
    });
    return base;
  }
  function fmtH(m) { return m >= 60 ? ((m % 60) ? +(m / 60).toFixed(1) : (m / 60)) + " h" : m + " min"; }
  function fmtMs(ms) { return (ms / 1000) + " s"; }
  function fmtOf(fmt, v) { return fmt === "ms" ? fmtMs(v) : fmt === "h" ? fmtH(v) : "" + v; }
  /* #AARRGGBB (the receiver's colour format) -> a CSS rgba() string. */
  function argbToCss(v) {
    v = String(v || "");
    if (v.length === 9 && v.charAt(0) === "#") {
      var a = parseInt(v.slice(1, 3), 16) / 255;
      var r = parseInt(v.slice(3, 5), 16), g = parseInt(v.slice(5, 7), 16), b = parseInt(v.slice(7, 9), 16);
      return "rgba(" + r + "," + g + "," + b + "," + a.toFixed(3) + ")";
    }
    return v || "#fff";
  }

  /* ---- control markup (no re-render on change; handlers update in place) ----
     Controls carry `data-act`; a single delegated listener (see wireControls)
     dispatches them. No inline event handlers, so a strict CSP
     (`script-src 'self'`, no 'unsafe-inline') is possible. Labels arrive already
     translated so build() stays declarative. */
  function sw(path, label, desc) {
    var v = get(path);
    return '<div class="row"><div><div>' + label + '</div>' + (desc ? '<div class="sub">' + desc + '</div>' : '') + '</div>' +
      '<button class="sw ' + (v ? "on" : "") + '" data-act="toggle" data-path="' + path + '"></button></div>';
  }
  function slider(path, label, min, max, step, fmt) {
    var v = get(path);
    return '<div class="row"><div>' + label + '</div>' +
      '<input type="range" data-act="slide" data-path="' + path + '" data-fmt="' + (fmt || "") + '" min="' + min + '" max="' + max + '" step="' + step + '" value="' + v + '">' +
      '<span class="val">' + (fmt ? fmtOf(fmt, v) : v) + '</span></div>';
  }
  function seg(path, label, opts) {
    var v = get(path);
    return '<div class="row"><div>' + label + '</div><div class="seg">' +
      opts.map(function (o) { return '<button data-act="choose" data-path="' + path + '" data-val="' + o + '" class="' + (o === v ? "on" : "") + '">' + o + '</button>'; }).join("") + '</div></div>';
  }
  function stepper(path, label, min, max, step, fmt) {
    var v = get(path);
    return '<div class="row"><div>' + label + '</div><div class="stepper">' +
      '<button data-act="step" data-path="' + path + '" data-delta="-' + step + '" data-min="' + min + '" data-max="' + max + '" data-step="' + step + '" data-fmt="' + (fmt || "") + '">−</button>' +
      '<b>' + (fmt ? fmtOf(fmt, v) : v) + '</b>' +
      '<button data-act="step" data-path="' + path + '" data-delta="' + step + '" data-min="' + min + '" data-max="' + max + '" data-step="' + step + '" data-fmt="' + (fmt || "") + '">+</button></div></div>';
  }
  function color(path, label) {
    var v = get(path) || "#FFFFFFFF";
    var hex = (v.length === 9) ? ("#" + v.slice(3)) : "#FFFFFF";
    return '<div class="row"><div>' + label + '</div>' +
      '<input type="color" data-act="color" data-path="' + path + '" value="' + hex + '">' +
      '<span class="val">' + v + '</span></div>';
  }
  function textField(path, label, placeholder) {
    var v = get(path); if (v === undefined || v === null) v = "";
    return '<div class="row"><div>' + label + '</div>' +
      '<input type="text" data-act="text" data-path="' + path + '" value="' + v + '" placeholder="' + (placeholder || "") + '"></div>';
  }

  function build() {
    el("sec-display").innerHTML = seg("display.orientation", tr("lblOrientation"), ["auto", "portrait", "landscape"]) +
      slider("display.name_size_sp", tr("lblNameSize"), 20, 120, 2) + sw("display.countdown", tr("lblCountdown")) +
      slider("display.message_size_min_sp", tr("lblMsgMin"), 8, 200, 2) +
      slider("display.message_size_max_sp", tr("lblMsgMax"), 8, 200, 2) +
      color("display.name_color", tr("lblNameColor")) + sw("display.per_sender_colors", tr("lblPerSender"), tr("descPerSender")) +
      color("display.message_color", tr("lblMsgColor")) +
      color("display.warn_color", tr("lblWarnColor")) + color("display.countdown_color", tr("lblCountdownColor")) +
      slider("display.idle_clock_scale", tr("lblIdleScale"), 1, 3, 0.1) +
      seg("display.idle_date_format", tr("lblIdleDate"), ["auto", "dmy", "mdy", "ymd"]) +
      textField("display.warn_text", tr("lblWarnText"), tr("phWarnText"));
    el("sec-cue").innerHTML = slider("cue.glow_ms", tr("lblGlow"), 0, 20000, 500, "ms") +
      sw("cue.chime", tr("lblSound")) + sw("cue.vibrate", tr("lblVibration")) + sw("cue.torch.enabled", tr("lblTorch")) +
      slider("cue.torch.on_ms", tr("lblOn"), 200, 5000, 100, "ms") +
      slider("cue.torch.off_ms", tr("lblOff"), 200, 5000, 100, "ms") +
      stepper("cue.torch.pulses", tr("lblPulses"), 1, 10, 1);
    el("sec-defaults").innerHTML = stepper("defaults.duration_minutes", tr("lblDuration"), 5, 1440, 30, "h") +
      stepper("defaults.history_count", tr("lblHistoryCount"), 1, 50, 1);
    el("sec-telemetry").innerHTML = stepper("telemetry.interval_minutes", tr("lblStatusInterval"), 1, 1440, 5);
    el("sec-power").innerHTML = sw("power.screen_off_when_idle", tr("lblScreenOff"), tr("descScreenOff"));
    el("sec-kiosk").innerHTML = sw("kiosk.locked", tr("lblKiosk"), tr("descKiosk"));
    el("sec-device_owner").innerHTML =
      sw("device_owner.lock_task", tr("lblLockTask"), tr("descLockTask")) +
      sw("device_owner.status_bar_disabled", tr("lblHideStatus")) +
      sw("device_owner.restrict_accounts", tr("lblBlockAccounts")) +
      sw("device_owner.restrict_bluetooth", tr("lblDisableBt")) +
      sw("device_owner.restrict_location", tr("lblBlockLocation")) +
      sw("device_owner.restrict_printing", tr("lblBlockPrinting")) +
      sw("device_owner.restrict_sync", tr("lblDisableSync")) +
      sw("device_owner.restrict_mobile_networks", tr("lblBlockMobile")) +
      sw("device_owner.restrict_tethering", tr("lblBlockTethering")) +
      sw("device_owner.restrict_roaming", tr("lblBlockRoaming"));
    preview();
  }
  function preview() {
    el("jsonPreview").textContent = JSON.stringify(cfg, null, 1);
    // Keep the sender pages (same origin) in step with the chosen count.
    try { if (cfg.defaults && typeof cfg.defaults.history_count === "number") localStorage.setItem("gp_history_count", cfg.defaults.history_count); } catch (e) {}
    renderPreview();
  }

  /* ---- live receiver preview ---- */
  function fitFont(node, minPx, maxPx, maxH) {
    var px = maxPx;
    node.style.fontSize = px + "px";
    var guard = 0;
    while (px > minPx && node.offsetHeight > maxH && guard++ < 400) { px -= 1; node.style.fontSize = px + "px"; }
  }
  function renderPreview() {
    var box = el("receiverPreview"); if (!box) return;
    var d = cfg.display || {};
    var scale = 0.6;
    var name = el("pvName"), msg = el("pvMsg"), cnt = el("pvCount"), warn = el("pvWarn");
    if (name) { name.style.color = argbToCss(d.name_color); name.style.fontSize = Math.round((d.name_size_sp || 58) * scale) + "px"; }
    if (msg) {
      msg.style.color = argbToCss(d.message_color);
      var maxPx = Math.max(4, Math.round((d.message_size_max_sp || 64) * scale));
      var minPx = Math.max(3, Math.round((d.message_size_min_sp || 10) * scale));
      if (minPx > maxPx) minPx = maxPx;
      fitFont(msg, minPx, maxPx, Math.max(48, box.clientHeight - 74));
    }
    if (cnt) { cnt.style.color = argbToCss(d.countdown_color); cnt.hidden = !d.countdown; }
    if (warn) {
      warn.style.color = argbToCss(d.warn_color);
      var hasWarn = !!(d.warn_text && String(d.warn_text).trim());
      warn.hidden = !hasWarn;
      if (hasWarn) warn.textContent = d.warn_text;
    }
  }

  /* ---- in-place handlers ---- */
  function toggle(btn) { var p = btn.dataset.path; var v = !get(p); setPath(p, v); btn.classList.toggle("on", v); preview(); }
  function slide(input) { var p = input.dataset.path; var v = +input.value; setPath(p, v); var s = input.nextElementSibling; if (s) s.textContent = input.dataset.fmt ? fmtOf(input.dataset.fmt, v) : v; preview(); }
  function choose(btn) { var p = btn.dataset.path; setPath(p, btn.dataset.val); Array.prototype.forEach.call(btn.parentNode.children, function (b) { b.classList.toggle("on", b === btn); }); preview(); }
  function step(btn) {
    var p = btn.dataset.path, min = +btn.dataset.min, max = +btn.dataset.max, step = +btn.dataset.step;
    var v = Math.max(min, Math.min(max, get(p) + (+btn.dataset.delta)));
    setPath(p, v);
    var b = btn.parentNode.querySelector("b"); if (b) b.textContent = btn.dataset.fmt ? fmtOf(btn.dataset.fmt, v) : v;
    preview();
  }
  function pickColor(input) {
    var p = input.dataset.path;
    var v = "#FF" + input.value.slice(1).toUpperCase();
    setPath(p, v);
    var s = input.nextElementSibling; if (s) s.textContent = v;
    preview();
  }
  function text(input) {
    var p = input.dataset.path;
    setPath(p, input.value.trim() ? input.value : null);
    preview();
  }

  /* A device is "stale" relative to its configured status cadence (shared
     helper GP.staleAfterSeconds; decisions.md D13). */
  function phoneOnline() {
    var s = lastStatusObj;
    if (!s) return false;
    var seen = s.last_seen ? new Date(s.last_seen).getTime() : 0;
    var age = Math.max(0, (Date.now() - seen) / 1000);
    return s.state !== "offline" && age < staleAfterSeconds(s);
  }
  /* The pill means end-to-end: green only when the broker link is up AND the
     phone is reachable; amber = broker up but phone offline; red = no broker. */
  function updateConnPill() {
    var p = el("connPill");
    if (mqttUp && phoneOnline()) { p.textContent = tr("connConnected"); p.className = "pill on"; }
    else if (mqttUp) { p.textContent = tr("connPhoneOffline"); p.className = "pill warn"; }
    else { p.textContent = tr("connDisconnected"); p.className = "pill off"; }
  }
  function setMqtt(ok) { mqttUp = ok; updateConnPill(); }

  function renderDevice(s) {
    if (!s) { el("devStatus").textContent = "—"; return; }
    var info = GP.statusInfo(s);
    var parts = [];
    parts.push('<span class="pill ' + (info.online ? "on" : "off") + '">' + (info.online ? tr("online") : tr("offline")) + '</span>');
    parts.push("<span>" + tr("battery") + " " + s.battery + "%" + (typeof s.battery_temp_c === "number" ? " · " + s.battery_temp_c.toFixed(1) + "°C" : "") + "</span>");
    parts.push("<span>" + (s.charging ? tr("charging") : tr("onBattery")) + "</span>");
    parts.push("<span>" + tr("wifi") + " " + s.wifi + "</span>");
    parts.push("<span>" + tr("mqtt") + " " + s.mqtt + "</span>");
    if (typeof s.device_owner === "boolean") parts.push("<span>" + tr("deviceOwner") + " " + s.device_owner + "</span>");
    // Only present the on-screen message as live when the status is fresh and the
    // message has not expired; the retained status can otherwise be hours old.
    var mSender = s.sender || lastMsgSender;
    var mText = s.text || lastMsgText;
    if (info.showLive && (mSender || mText)) {
      parts.push("<span>" + tr("message") + ": " + (mText ? "“" + GP.escapeHtml(mText) + "” " : "") + (mSender ? "— " + GP.escapeHtml(mSender) : "") + "</span>");
    }
    parts.push("<span>" + tr("seenAgo", { s: info.ageSeconds }) + (info.stale ? " " + tr("stale") : "") + "</span>");
    el("devStatus").innerHTML = parts.join('<span class="sep">·</span>');
  }

  function renderConfigError(s) {
    var bar = el("configError"); if (!bar) return;
    if (s && s.config_error) { bar.hidden = false; bar.textContent = tr("configError", { msg: s.config_error }); }
    else { bar.hidden = true; bar.textContent = ""; }
  }

  function connect() {
    var token = el("adminToken").value.trim() || loadAdminToken();
    if (!token) { el("unlockHint").textContent = tr("enterToken"); return; }
    storeAdminToken(token);
    lastAttempt = Date.now();
    if (client) { try { client.end(true); } catch (e) {} }
    el("unlockHint").textContent = tr("connecting");
    client = mqtt.connect(BROKER, { username: token, clientId: "grandpa-admin-" + Math.random().toString(16).slice(2), clean: true, keepalive: 30, reconnectPeriod: 5000, connectTimeout: 12000, protocolVersion: 4 });
    client.on("connect", function () {
      el("unlock").hidden = true; el("admin").hidden = false;
      setMqtt(true);
      client.subscribe([TOPIC.status, TOPIC.config, TOPIC.message, TOPIC.last], { qos: 1 });
    });
    client.on("error", function (e) { el("unlockHint").textContent = tr("errPrefix") + e.message; });
    client.on("close", function () { setMqtt(false); });
    client.on("message", function (topic, payload) {
      if (topic === TOPIC.message) {
        var raw = payload.toString();
        try {
          var m = raw ? JSON.parse(raw) : null;
          lastMsgSender = (m && m.sender) ? m.sender : null;
          lastMsgText = (m && m.text) ? m.text : null;
        } catch (e) { lastMsgSender = null; lastMsgText = null; }
        if (lastStatusObj) renderDevice(lastStatusObj);
        return;
      }
      if (topic === TOPIC.last) {
        history = parseHistory(payload.toString());
        historyCount = (cfg.defaults && cfg.defaults.history_count) || 10;
        renderHistory();
        if (lastStatusObj) renderDevice(lastStatusObj);
        return;
      }
      if (topic !== TOPIC.status) return;
      var s; try { s = JSON.parse(payload.toString()); } catch (e) { return; }
      lastStatusObj = s;
      renderDevice(s);
      updateConnPill();
      renderConfigError(s);
      if (s.connection) renderConnection(s.connection);
      if (s.command && s.command.last) el("actionHint").textContent = s.command.last + ": " + (s.command.result || "");
      if (!dirty && s.config) {
        cfg = deepMerge(JSON.parse(JSON.stringify(DEF)), s.config);
        // The receiver drops unknown config fields, so history_count is usually
        // absent from the echoed config; restore the admin's stored choice.
        if (!(s.config.defaults && typeof s.config.defaults.history_count === "number")) applyStoredHistoryCount(cfg);
        cfg.schema = cfg.schema || 1;
        historyCount = (cfg.defaults && cfg.defaults.history_count) || 10;
        build(); renderHistory();
        el("applied").textContent = tr("appliedLoaded") + (s.config_applied_at ? "  ·  " + s.config_applied_at : "");
      }
    });
  }

  /* Auto-connect (same rationale as the sender): heal a dropped or
     never-established link when the tab is resumed, the network returns, or the
     page is restored from the bfcache — without a manual refresh. Idempotent. */
  function ensureConnected() {
    if (!loadAdminToken()) return;
    if (!client) { connect(); return; }
    if (client.connected || client.reconnecting) return;
    if (Date.now() - lastAttempt < 13000) return;
    try { client.reconnect(); } catch (e) { connect(); }
  }

  function apply() {
    if (!client || !client.connected) { el("applied").textContent = tr("notConnected"); return; }
    client.publish(TOPIC.config, JSON.stringify(cfg), { qos: 1, retain: true });
    setDirty(false);
    el("applied").textContent = tr("appliedSent");
  }
  function reset() { cfg = JSON.parse(JSON.stringify(DEF)); setDirty(true); build(); }

  /* ---- modal dialog (replaces native confirm(); CSP-safe DOM) ---- */
  function showDialog(title, body, okLabel, onOk) {
    el("modalTitle").textContent = title;
    el("modalBody").textContent = body;
    el("modalOk").textContent = okLabel || tr("okBtn");
    el("modal").hidden = false;
    pendingOk = onOk;
    el("modalOk").focus();
  }
  function closeDialog() { el("modal").hidden = true; pendingOk = null; }

  /* ---- guarded connection change + device actions (grandpa/command) ---- */
  function renderConnection(c) {
    if (revertTimer) { clearInterval(revertTimer); revertTimer = null; }
    el("connState").textContent =
      tr("connStateLabel") + ": " + c.state + " · " + c.host + ":" + c.port + (c.use_tls ? " tls" : "") +
      " · " + (c.password_set ? tr("passwordSet") : tr("passwordNotSet")) +
      (c.last_error ? " · " + c.last_error : "");
    if (!el("cHost").value) el("cHost").value = c.host;
    if (!el("cPort").value) el("cPort").value = c.port;
    el("cTls").checked = !!c.use_tls;
    // The username is intentionally never echoed back (for Flespi it *is* the
    // token), so a blank field always means "keep the current value".
  }
  function startRevertCountdown(secs) {
    if (revertTimer) clearInterval(revertTimer);
    var left = secs;
    function tick() {
      if (left <= 0) { clearInterval(revertTimer); revertTimer = null; el("connState").textContent = tr("connDone"); return; }
      el("connState").textContent = tr("connTesting", { n: left });
      left--;
    }
    tick();
    revertTimer = setInterval(tick, 1000);
  }
  function applyConn() {
    if (!client || !client.connected) { el("connState").textContent = tr("notConnected"); return; }
    var host = el("cHost").value.trim();
    if (!host) { el("connState").textContent = tr("enterHost"); return; }
    var revert = parseInt(el("cRevert").value, 10) || 90;
    showDialog(tr("dlgChangeConnTitle"), tr("dlgChangeConnBody", { n: revert }), tr("connChange"), function () {
      var payload = {
        action: "set_connection",
        candidate: {
          host: host,
          port: parseInt(el("cPort").value, 10) || 8883,
          use_tls: el("cTls").checked,
          username: el("cUser").value.trim(),
          password: el("cPass").value,
          keepalive_s: parseInt(el("cKeep").value, 10) || 30,
          connect_timeout_s: parseInt(el("cTimeout").value, 10) || 15,
          auto_reconnect: el("cAuto").checked,
          qos: parseInt(el("cQos").value, 10),
          max_inflight: parseInt(el("cInflight").value, 10) || 20
        },
        revert_after_s: revert
      };
      client.publish(TOPIC.command, JSON.stringify(payload), { qos: 1, retain: false });
      el("connState").textContent = tr("connSent", { n: revert });
      startRevertCountdown(revert);
    });
  }
  function sendAction(action, titleKey, bodyKey) {
    if (!client || !client.connected) { el("actionHint").textContent = tr("notConnected"); return; }
    showDialog(tr(titleKey), tr(bodyKey), tr("okBtn"), function () {
      client.publish(TOPIC.command, JSON.stringify({ action: action }), { qos: 1, retain: false });
      el("actionHint").textContent = tr("actionRequested", { action: action });
    });
  }

  function showForm() { el("unlock").hidden = true; el("admin").hidden = false; build(); renderHistory(); }

  /* Invitation link: admin.html#token=<admin-token> (fragment preferred — never
     sent to the server nor leaked in Referer; ?token= also works). Parsing and
     stripping are shared with the sender (common.js). */
  function readInvite() {
    var inv = GP.readInvite({ token: ["token", "t"] }, "token");
    return inv ? inv.token : "";
  }
  function clearInviteParams() { GP.clearUrlParams(["token", "t"]); }

  /* ---- invite a sender (link + QR) ---- */
  function invStore(k, v) { try { if (v) localStorage.setItem(k, v); } catch (e) {} }
  function buildInvite() {
    var token = el("invToken").value.trim();
    if (!token) { el("invHint").textContent = tr("inviteHintEmpty"); el("invOut").hidden = true; return; }
    var name = el("invName").value.trim();
    var broker = el("invBroker").value.trim() || "wss://mqtt.flespi.io:443";
    var base = location.href.split("#")[0].split("?")[0].replace(/admin\.html$/, "index.html");
    var params = ["token=" + encodeURIComponent(token)];
    if (name) params.push("sender=" + encodeURIComponent(name));
    if (broker) params.push("broker=" + encodeURIComponent(broker));
    var link = base + "#" + params.join("&");
    el("invLink").value = link;
    el("invOut").hidden = false;
    invStore("gp_inv_token", token); invStore("gp_inv_name", name); invStore("gp_inv_broker", broker);
    renderQr(link);
  }
  function renderQr(text) {
    var box = el("invQr"); if (!box) return;
    if (typeof qrcode !== "function") { box.hidden = true; el("invHint").textContent = tr("inviteNoQr"); return; }
    try {
      var qr = qrcode(0, "M");
      qr.addData(text);
      qr.make();
      box.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 8, scalable: true });
      box.hidden = false;
    } catch (e) { box.hidden = true; el("invHint").textContent = tr("inviteNoQr"); }
  }
  function copyInvite() {
    var v = el("invLink").value;
    function done() { el("invHint").textContent = tr("inviteCopied"); }
    function fallback() { el("invLink").select(); try { document.execCommand("copy"); done(); } catch (e) {} }
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(v).then(done, fallback);
    else fallback();
  }

  /* One delegated listener for every generated control (no inline handlers, so
     a strict CSP is possible). Buttons dispatch on click, inputs on input. */
  function wireControls() {
    el("admin").addEventListener("click", function (e) {
      var b = e.target.closest("button[data-act]");
      if (!b) return;
      var act = b.getAttribute("data-act");
      if (act === "toggle") toggle(b);
      else if (act === "choose") choose(b);
      else if (act === "step") step(b);
    });
    el("admin").addEventListener("input", function (e) {
      var i = e.target;
      var act = i.getAttribute("data-act");
      if (act === "slide") slide(i);
      else if (act === "color") pickColor(i);
      else if (act === "text") text(i);
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    var q = new URLSearchParams(location.search);
    applyStatic();
    wireControls();
    if (q.get("demo") === "1") { showForm(); el("applied").textContent = "demo mode (no broker)"; }
    el("unlockBtn").addEventListener("click", connect);
    el("apply").addEventListener("click", apply);
    el("reset").addEventListener("click", reset);
    el("applyConn").addEventListener("click", applyConn);
    el("restartApp").addEventListener("click", function () { sendAction("restart_app", "dlgRestartTitle", "dlgRestartBody"); });
    el("rebootDevice").addEventListener("click", function () { sendAction("reboot_device", "dlgRebootTitle", "dlgRebootBody"); });
    el("langBtn").addEventListener("click", toggleLocale);
    el("langBtn0").addEventListener("click", toggleLocale);
    el("modalCancel").addEventListener("click", closeDialog);
    el("modalOk").addEventListener("click", function () { var fn = pendingOk; closeDialog(); if (fn) fn(); });
    el("modal").addEventListener("click", function (e) { if (e.target === el("modal")) closeDialog(); });
    document.addEventListener("keydown", function (e) { if (e.key === "Escape" && !el("modal").hidden) closeDialog(); });
    el("invBuild").addEventListener("click", buildInvite);
    el("invCopy").addEventListener("click", copyInvite);
    // Restore the last invite details (the sender token is a shareable token).
    try {
      if (localStorage.getItem("gp_inv_token")) el("invToken").value = localStorage.getItem("gp_inv_token");
      if (localStorage.getItem("gp_inv_name")) el("invName").value = localStorage.getItem("gp_inv_name");
      if (localStorage.getItem("gp_inv_broker")) el("invBroker").value = localStorage.getItem("gp_inv_broker");
    } catch (e) {}
    window.addEventListener("online", ensureConnected);
    window.addEventListener("focus", ensureConnected);
    window.addEventListener("pageshow", ensureConnected);
    document.addEventListener("visibilitychange", function () { if (!document.hidden) ensureConnected(); });
    window.addEventListener("beforeunload", function (e) { if (dirty) { e.preventDefault(); e.returnValue = ""; return ""; } });
    setInterval(ensureConnected, 15000);
    setInterval(updateConnPill, 10000);
    if (el("historyLink")) el("historyLink").addEventListener("click", function (e) {
      e.preventDefault();
      var det = el("historyDetails"); if (det) det.open = true;
      if (el("historyCard") && el("historyCard").scrollIntoView) {
        el("historyCard").scrollIntoView({ behavior: "smooth", block: "start" });
      }
    });
    window.addEventListener("storage", function (e) {
      if (e.key === "gp_history_count") {
        historyCount = storedHistoryCount() || historyCount;
        renderHistory();
      }
    });
    // Offer an "Install app" button (or a hint on browsers that can't install).
    if (GP.setupInstall) GP.setupInstall("installCard");
    var saved = loadAdminToken();
    var invite = readInvite();
    if (invite) { storeAdminToken(invite); clearInviteParams(); saved = invite; }
    if (saved) el("adminToken").value = saved;
    if (saved && !q.get("demo")) connect();

    if (q.get("selftest") === "1") runSelfTest();
  });

  /* headless test: exercise the in-place handlers and report PASS/FAIL */
  function runSelfTest() {
    showForm();
    var checks = [];
    function t(name, fn, expect) { try { fn(); var ok = expect ? get(expect.path) === expect.val : true; checks.push((ok ? "OK " : "FAIL ") + name); } catch (e) { checks.push("ERR " + name + " " + e.message); } }
    // Real user-like interactions: .click() and dispatched 'input' events.
    var kioskBtn = el("sec-kiosk").querySelector("button.sw");
    t("kiosk toggles off (click)", function () { kioskBtn.click(); }, { path: "kiosk.locked", val: false });
    var nameSlider = el("sec-display").querySelector('input[data-path="display.name_size_sp"]');
    t("name slider sets 100 (input event)", function () { nameSlider.value = 100; nameSlider.dispatchEvent(new Event("input", { bubbles: true })); }, { path: "display.name_size_sp", val: 100 });
    var lbl = nameSlider.nextElementSibling;
    checks.push(((lbl && lbl.textContent === "100") ? "OK " : "FAIL ") + "slider number shows 100 (is '" + (lbl ? lbl.textContent : "?") + "')");
    var glow = el("sec-cue").querySelector('input[data-path="cue.glow_ms"]');
    t("glow slider sets 4000 (input event)", function () { glow.value = 4000; glow.dispatchEvent(new Event("input", { bubbles: true })); }, { path: "cue.glow_ms", val: 4000 });
    checks.push(((glow.nextElementSibling && glow.nextElementSibling.textContent === "4 s") ? "OK " : "FAIL ") + "glow shows '4 s' (is '" + glow.nextElementSibling.textContent + "')");
    var portrait = el("sec-display").querySelector('button[data-val="portrait"]');
    t("orientation portrait (click)", function () { portrait.click(); }, { path: "display.orientation", val: "portrait" });
    var plus = el("sec-defaults").querySelectorAll("button")[1];
    t("duration +30 (click)", function () { plus.click(); }, { path: "defaults.duration_minutes", val: 270 });
    var torchOff = el("sec-cue").querySelector('button[data-path="cue.torch.enabled"]');
    t("torch toggles off (click)", function () { torchOff.click(); }, { path: "cue.torch.enabled", val: false });
    // The live preview reflects the change.
    t("preview reflects name size", function () { renderPreview(); }, null);
    checks.push(((el("pvName").style.fontSize === "60px") ? "OK " : "FAIL ") + "preview name font-size (is '" + el("pvName").style.fontSize + "')");
    var fails = checks.filter(function (c) { return c.indexOf("OK ") !== 0; }).length;
    document.title = fails ? ("SELFTEST_FAIL " + fails) : "SELFTEST_OK";
    el("jsonBox").open = true;
    var pre = el("jsonPreview"); pre.textContent = "SELFTEST: " + (fails ? "FAIL" : "OK") + "\n" + checks.join("\n") + "\n\n" + JSON.stringify(cfg, null, 1);
  }
})();
