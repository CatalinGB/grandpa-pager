/* Grandpa Pager — web sender.
 * First run asks for the name + token (saved only in this browser). Afterwards
 * the page shows just the message box + duration picker, with a Settings box at
 * the bottom. Publishes the same payload schema as the Android sender.
 */
(function () {
  "use strict";

  var LS = { token: "gp_token", sender: "gp_sender", broker: "gp_broker", draft: "gp_draft", duration: "gp_duration" };
  var TOPIC = { message: "grandpa/message", command: "grandpa/command", status: "grandpa/status", last: "grandpa/last", config: "grandpa/config" };
  var DEFAULT_BROKER = "wss://mqtt.flespi.io:443";
  var DEFAULT_MIN = 240;
  var PRESETS = [60, 240, 480, 1440];

  var el = function (id) { return document.getElementById(id); };
  var client = null;
  var mqttUp = false;           // broker link up
  var manualClose = false;      // set by "Forget token" so we stop auto-connecting
  var disconnectedSince = 0;    // epoch ms when the link dropped (0 = healthy)
  var lastAttempt = 0;          // epoch ms of the last connect() call
  var minutes = DEFAULT_MIN;
  var myLastId = null, myLastAt = 0, lastStatus = null;
  var lastMessageText = null;
  var userChangedDuration = false;
  /* Message history, mirrored to the retained grandpa/last topic as a versioned
     array. `historyLoaded` gates maintenance until the retained array has been
     adopted, so a fresh page never clobbers the broker's history with its own
     partial view (the retained grandpa/message can arrive first). */
  var history = [], historySig = "", historyLoaded = false, pendingMessage = null, historyTimer = null;
  var historyCount = 10, DEFAULT_HISTORY = 10, remoteHistoryCount = null;
  var dialogOk = null, draftTimer = null;

  /* Shared helpers (common.js). */
  var escapeHtml = GP.escapeHtml;
  var uuid = GP.uuid;
  var staleAfterSeconds = GP.staleAfterSeconds;

  /* ---------- i18n (system language; English fallback) ---------- */
  var I18N = {
    en: {
      welcome: "Welcome",
      setupIntro: "Enter your name and token once. They are saved only in this browser.",
      yourName: "Your name",
      yourNameShown: "Your name (shown on Grandpa's screen)",
      namePlaceholder: "e.g. Maria",
      brokerToken: "Broker token",
      tokenPlaceholder: "token",
      broker: "Broker",
      save: "Save",
      adminLink: "Admin",
      mainIntro: "Send a message to Grandpa's screen.",
      message: "Message",
      messagePlaceholder: "Type the message…",
      durationQuestion: "How long should it stay on screen?",
      onScreenUntil: "On screen until {time}",
      composeHint: "Enter sends · Shift+Enter for a new line",
      sendButton: "Send to screen",
      clearButton: "Clear the message",
      clearTitle: "Clear the screen?",
      clearBody: "The message will disappear from Grandpa's screen.",
      clearConfirm: "Clear",
      cancelButton: "Cancel",
      deviceStatus: "Device status",
      noStatus: "No status yet.",
      settings: "Settings",
      token: "Token",
      reconnect: "Reconnect",
      forget: "Forget token",
      tokenSaved: "The token is saved only in this browser.",
      connConnected: "connected",
      connDisconnected: "disconnected",
      connPhoneOffline: "phone offline",
      connecting: "Connecting…",
      connectedMsg: "Connected.",
      reconnecting: "Reconnecting…",
      errorPrefix: "Error: ",
      errNoMqtt: "Error: mqtt.min.js failed to load.",
      errBrokerToken: "Broker or token missing.",
      errEnterName: "Enter your name (in Settings).",
      errSetupName: "Enter your name.",
      errTokenMissing: "Token missing.",
      errTypeMessage: "Type a message.",
      errNotConnected: "You are not connected.",
      sentMsg: "Sent — it is on the screen.",
      clearedMsg: "Screen cleared.",
      tokenForgotten: "Token forgotten. Enter it again.",
      inviteApplied: "Token applied — enter your name to start.",
      hourShort: "h",
      minShort: "min",
      diagState: "state:",
      diagMqtt: "mqtt:",
      diagBattery: "battery:",
      diagWifi: "wifi:",
      diagMessage: "message:",
      diagSeen: "seen:",
      diagLast: "last:",
      lastMessage: "Last message",
      history: "Message history",
      showHistory: "Show last {n} messages",
      noHistory: "No messages yet.",
      justNow: "just now",
      minutesAgo: "{n} min ago",
      hoursAgo: "{n} h ago",
      daysAgo: "{n} d ago",
      chargingSuffix: "(charging)",
      staleSuffix: "(STALE {s}s)",
      seenAgo: "{s}s ago",
      bannerOnScreen: "On screen now: a message",
      bannerMsg: "On screen now: “{text}”",
      bannerFrom: " from {name}",
      bannerExpires: " (expires in {m} min)",
      bannerReplaces: ". Sending replaces it.",
      bannerReplaced: "⚠ Your message was just replaced by someone else."
    },
    ro: {
      welcome: "Bun venit",
      setupIntro: "Introdu o dată numele și tokenul. Sunt salvate doar în acest browser.",
      yourName: "Numele tău",
      yourNameShown: "Numele tău (apare pe ecranul lui Bunicu)",
      namePlaceholder: "de ex. Maria",
      brokerToken: "Token broker",
      tokenPlaceholder: "token",
      broker: "Broker",
      save: "Salvează",
      adminLink: "Admin",
      mainIntro: "Trimite un mesaj pe ecranul lui Bunicu.",
      message: "Mesaj",
      messagePlaceholder: "Scrie mesajul…",
      durationQuestion: "Cât timp să rămână pe ecran?",
      onScreenUntil: "Pe ecran până la {time}",
      composeHint: "Enter trimite · Shift+Enter linie nouă",
      sendButton: "Trimite pe ecran",
      clearButton: "Șterge mesajul",
      clearTitle: "Ștergi ecranul?",
      clearBody: "Mesajul va dispărea de pe ecranul lui Bunicu.",
      clearConfirm: "Șterge",
      cancelButton: "Anulează",
      deviceStatus: "Starea dispozitivului",
      noStatus: "Încă nu există status.",
      settings: "Setări",
      token: "Token",
      reconnect: "Reconectare",
      forget: "Uită tokenul",
      tokenSaved: "Tokenul este salvat doar în acest browser.",
      connConnected: "conectat",
      connDisconnected: "deconectat",
      connPhoneOffline: "telefon offline",
      connecting: "Se conectează…",
      connectedMsg: "Conectat.",
      reconnecting: "Se reconectează…",
      errorPrefix: "Eroare: ",
      errNoMqtt: "Eroare: mqtt.min.js nu s-a putut încărca.",
      errBrokerToken: "Lipsește brokerul sau tokenul.",
      errEnterName: "Introdu numele tău (în Setări).",
      errSetupName: "Introdu numele tău.",
      errTokenMissing: "Lipsește tokenul.",
      errTypeMessage: "Scrie un mesaj.",
      errNotConnected: "Nu ești conectat.",
      sentMsg: "Trimis — este pe ecran.",
      clearedMsg: "Ecran șters.",
      tokenForgotten: "Token uitat. Introdu-l din nou.",
      inviteApplied: "Token aplicat — introdu numele pentru a începe.",
      hourShort: "h",
      minShort: "min",
      diagState: "stare:",
      diagMqtt: "mqtt:",
      diagBattery: "baterie:",
      diagWifi: "wifi:",
      diagMessage: "mesaj:",
      diagSeen: "văzut:",
      diagLast: "ultimul:",
      lastMessage: "Ultimul mesaj",
      history: "Istoric mesaje",
      showHistory: "Arată ultimele {n} mesaje",
      noHistory: "Încă niciun mesaj.",
      justNow: "chiar acum",
      minutesAgo: "acum {n} min",
      hoursAgo: "acum {n} h",
      daysAgo: "acum {n} z",
      chargingSuffix: "(se încarcă)",
      staleSuffix: "(ÎNVECHIT {s}s)",
      seenAgo: "acum {s}s",
      bannerOnScreen: "Pe ecran acum: un mesaj",
      bannerMsg: "Pe ecran acum: „{text}”",
      bannerFrom: " de la {name}",
      bannerExpires: " (expiră în {m} min)",
      bannerReplaces: ". Trimiterea îl înlocuiește.",
      bannerReplaced: "⚠ Mesajul tău tocmai a fost înlocuit de altcineva."
    }
  };
  var LOCALE = GP.detectLocale(["en", "ro"]);

  /** Translate [key] for the active locale, substituting {name} placeholders. */
  function t(key, params) {
    var s = (I18N[LOCALE] && I18N[LOCALE][key]) || I18N.en[key] || key;
    if (params) s = s.replace(/\{(\w+)\}/g, function (_, k) { return params[k] != null ? params[k] : ""; });
    return s;
  }

  /** Fill the static markup (data-i18n / data-i18n-ph) from the dictionary. */
  function applyStatic() {
    document.documentElement.lang = LOCALE;
    Array.prototype.forEach.call(document.querySelectorAll("[data-i18n]"), function (n) {
      n.textContent = t(n.getAttribute("data-i18n"));
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-i18n-ph]"), function (n) {
      n.setAttribute("placeholder", t(n.getAttribute("data-i18n-ph")));
    });
  }

  /* ---------- views ---------- */
  function showView(which) {
    el("setupView").hidden = which !== "setup";
    el("mainView").hidden = which !== "main";
  }
  function creds() {
    var name = (el("sender").value || localStorage.getItem(LS.sender) || "").trim();
    var token = (el("token").value || localStorage.getItem(LS.token) || "").trim();
    var broker = (el("brokerUrl").value || localStorage.getItem(LS.broker) || DEFAULT_BROKER).trim();
    return { name: name, token: token, broker: broker };
  }

  /* ---------- invitation links ----------
     A family member opens a link with the token (and optionally their name)
     baked in:  index.html#token=…&sender=Maria
     Parsing/stripping lives in common.js (GP.readInvite / GP.clearUrlParams). */
  var INVITE_ALIASES = { token: ["token", "t"], name: ["sender", "name", "n"], broker: ["broker", "b"] };
  var INVITE_ALL_KEYS = ["token", "t", "sender", "name", "n", "broker", "b"];
  function readInvite() { return GP.readInvite(INVITE_ALIASES, "token"); }
  function clearInviteParams() { GP.clearUrlParams(INVITE_ALL_KEYS); }

  /* ---------- duration (stepper B) ---------- */
  function fmt(m) {
    if (m >= 60) { var h = Math.floor(m / 60), mm = m % 60; return mm ? h + " " + t("hourShort") + " " + mm + " " + t("minShort") : h + " " + t("hourShort"); }
    return m + " " + t("minShort");
  }
  function two(n) { return ("0" + n).slice(-2); }
  /* The clock time the message would expire at if sent now — far more useful
     than a bare duration for "will it still be there when I arrive?". */
  function updateDurWhen() {
    if (!el("durWhen")) return;
    var d = new Date(Date.now() + minutes * 60000);
    el("durWhen").textContent = t("onScreenUntil", { time: two(d.getHours()) + ":" + two(d.getMinutes()) });
  }
  function persistDuration() { try { localStorage.setItem(LS.duration, minutes); } catch (e) {} }
  function setMinutes(m) {
    minutes = Math.max(15, Math.min(1440, m));
    el("durValue").textContent = fmt(minutes);
    Array.prototype.forEach.call(el("presets").children, function (b) {
      b.classList.toggle("on", parseInt(b.dataset.min, 10) === minutes);
    });
    updateDurWhen();
  }
  function buildPresets() {
    PRESETS.forEach(function (m) {
      var b = document.createElement("button");
      b.dataset.min = m; b.textContent = fmt(m);
      b.addEventListener("click", function () { userChangedDuration = true; setMinutes(m); persistDuration(); });
      el("presets").appendChild(b);
    });
  }

  /* ---------- status ---------- */
  /* The pill means "end-to-end reachable": green only when BOTH the broker link
     is up AND the phone's status is fresh (not stale/offline). Amber = broker up
     but the phone is not reachable; red = not connected to the broker at all. */
  function phoneOnline() {
    if (!lastStatus) return false;
    var seen = lastStatus.last_seen ? new Date(lastStatus.last_seen).getTime() : 0;
    var age = Math.max(0, (Date.now() - seen) / 1000);
    return lastStatus.state !== "offline" && age < staleAfterSeconds(lastStatus);
  }
  function updateConnPill() {
    var p = el("connPill");
    if (mqttUp && phoneOnline()) { p.textContent = t("connConnected"); p.className = "pill on"; }
    else if (mqttUp) { p.textContent = t("connPhoneOffline"); p.className = "pill warn"; }
    else { p.textContent = t("connDisconnected"); p.className = "pill off"; }
  }
  function setConnected(ok) {
    mqttUp = ok;
    updateConnPill();
    updateSendEnabled();
  }
  /* Composing offline is fine, but Send/Clear cannot work without the broker
     link: disable and dim them so a tap can't silently do nothing. */
  function updateSendEnabled() {
    var ok = !!mqttUp;
    var compose = el("composeCard");
    if (compose) compose.classList.toggle("offline", !ok);
    Array.prototype.forEach.call(document.querySelectorAll("#send, #clear"), function (b) { b.disabled = !ok; });
  }

  /* ---------- draft autosave ---------- */
  function saveDraft() {
    if (draftTimer) clearTimeout(draftTimer);
    draftTimer = setTimeout(function () {
      try { var v = el("text").value; if (v) localStorage.setItem(LS.draft, v); else localStorage.removeItem(LS.draft); } catch (e) {}
    }, 400);
  }
  function clearDraft() {
    if (draftTimer) { clearTimeout(draftTimer); draftTimer = null; }
    try { localStorage.removeItem(LS.draft); } catch (e) {}
  }

  /* ---------- confirm dialog (CSP-safe; replaces native confirm) ---------- */
  function showDialog(title, body, okLabel, onOk) {
    el("modalTitle").textContent = title;
    el("modalBody").textContent = body;
    el("modalOk").textContent = okLabel || t("clearConfirm");
    el("modal").hidden = false;
    dialogOk = onOk;
    el("modalOk").focus();
  }
  function closeDialog() { el("modal").hidden = true; dialogOk = null; }
  function renderDiag(d) {
    var info = GP.statusInfo(d);
    var temp = (typeof d.battery_temp_c === "number") ? " " + d.battery_temp_c.toFixed(1) + "°C" : "";
    var msg = d.text || lastMessageText || "";
    el("diag").textContent =
      t("diagState") + " " + d.state + (info.stale ? "  " + t("staleSuffix", { s: info.ageSeconds }) : "") + "\n" +
      t("diagMqtt") + " " + d.mqtt + "\n" +
      t("diagBattery") + " " + d.battery + "%" + (d.charging ? " " + t("chargingSuffix") : "") + temp + "\n" +
      t("diagWifi") + " " + d.wifi + "\n" +
      t("diagMessage") + " " + (d.sender || "-") + (msg ? " — " + msg : "") + "\n" +
      t("diagSeen") + " " + t("seenAgo", { s: info.ageSeconds });
  }

  /* Adopt the admin-configured default duration until the user picks their own. */
  function applyRemoteDefault(d) {
    if (userChangedDuration) return;
    var m = d && d.config && d.config.defaults && d.config.defaults.duration_minutes;
    if (typeof m === "number") setMinutes(m);
  }

  /* ---------- message history (retained grandpa/last array) ----------
     The sender is the writer: it mirrors every message it observes on
     grandpa/message into a capped, newest-first array on grandpa/last. The topic
     is reused (no new ACLs); the Android receiver never subscribes to it, so the
     payload shape is a private web contract. */
  function clampCount(n) { n = Math.round(n); return Math.max(1, Math.min(50, n)); }
  function resolveHistoryCount() {
    // The admin's retained grandpa/config is authoritative and reaches every
    // sender device; the status echo and localStorage are fallbacks (the receiver
    // does not echo unknown config fields).
    if (typeof remoteHistoryCount === "number" && isFinite(remoteHistoryCount)) return clampCount(remoteHistoryCount);
    var c = lastStatus && lastStatus.config && lastStatus.config.defaults && lastStatus.config.defaults.history_count;
    if (typeof c === "number" && isFinite(c)) return clampCount(c);
    try { var ls = parseInt(localStorage.getItem("gp_history_count"), 10); if (isFinite(ls)) return clampCount(ls); } catch (e) {}
    return DEFAULT_HISTORY;
  }
  /* The admin's config (retained) carries defaults.history_count. Reading it here
     makes the choice consistent across every sender, not just the admin's browser. */
  function applyRemoteHistoryCount(raw) {
    var o = null;
    try { o = raw ? JSON.parse(raw) : null; } catch (e) { return; }
    var n = o && o.defaults && o.defaults.history_count;
    if (typeof n === "number" && isFinite(n)) {
      var v = clampCount(n);
      if (v !== remoteHistoryCount) { remoteHistoryCount = v; historyCount = resolveHistoryCount(); renderHistory(); }
    }
  }
  function isHistoryMsg(m) { return !!m && typeof m === "object" && (m.id || m.text); }
  function parseHistory(raw) {
    try {
      var o = raw ? JSON.parse(raw) : null;
      if (!o) return [];
      if (Array.isArray(o)) return o.filter(isHistoryMsg);
      if (Array.isArray(o.messages)) return o.messages.filter(isHistoryMsg);
      if (o.text || o.sender) return [o]; // legacy single-object payload
    } catch (e) {}
    return [];
  }
  function historyIndexOf(arr, id) {
    for (var i = 0; i < arr.length; i++) { if (arr[i] && arr[i].id === id) return i; }
    return -1;
  }
  /* Publish the array only when it actually changed, so the retained echo of our
     own write does not trigger another write (no publish loop). */
  function commitHistory(arr) {
    arr = arr.slice(0, historyCount);
    var sig = JSON.stringify(arr);
    if (sig === historySig) return;
    history = arr; historySig = sig;
    renderHistory();
    if (client && client.connected) {
      client.publish(TOPIC.last, JSON.stringify({ v: 1, messages: arr }), { qos: 1, retain: true });
    }
  }
  function recordMessage(msg) {
    if (!isHistoryMsg(msg) || !msg.id) return;
    if (!historyLoaded) { pendingMessage = msg; return; } // wait for the retained array first
    var arr = history.slice();
    var i = historyIndexOf(arr, msg.id);
    if (i >= 0) arr[i] = msg; else arr.unshift(msg);
    commitHistory(arr);
  }
  function adoptHistory(raw) {
    if (historyTimer) { clearTimeout(historyTimer); historyTimer = null; }
    history = parseHistory(raw).slice(0, historyCount);
    historySig = JSON.stringify(history);
    historyLoaded = true;
    renderHistory();
    // A message published while no sender was online (e.g. from the phone) is not
    // in the retained array yet; fold it in now that we have the broker's copy.
    if (pendingMessage) {
      var pm = pendingMessage; pendingMessage = null;
      if (historyIndexOf(history, pm.id) < 0) recordMessage(pm);
    }
  }
  /* Retained delivery is immediate on a healthy link, but when no retained
     grandpa/last exists yet (the very first message ever) nothing arrives to
     unlock maintenance. Settle shortly after connect: if the retained array has
     still not shown up, treat history as empty and flush whatever was buffered. */
  function armHistorySettle() {
    if (historyTimer) clearTimeout(historyTimer);
    historyTimer = setTimeout(function () {
      historyTimer = null;
      if (historyLoaded) return;
      historyLoaded = true;
      if (pendingMessage) { var pm = pendingMessage; pendingMessage = null; recordMessage(pm); }
    }, 2500);
  }
  function relativeTime(iso) {
    if (!iso) return "";
    var t0 = new Date(iso).getTime();
    if (!isFinite(t0)) return String(iso);
    var s = Math.max(0, Math.round((Date.now() - t0) / 1000));
    if (s < 60) return t("justNow");
    var m = Math.round(s / 60);
    if (m < 60) return t("minutesAgo", { n: m });
    var h = Math.round(m / 60);
    if (h < 24) return t("hoursAgo", { n: h });
    var d = Math.round(h / 24);
    if (d < 7) return t("daysAgo", { n: d });
    return new Date(t0).toLocaleDateString();
  }
  function renderHistory() {
    var card = el("historyCard"), list = el("history"), link = el("historyLink");
    if (!card || !list) return;
    var shown = history.slice(0, historyCount);
    if (!shown.length) {
      card.hidden = true;
      if (link) link.hidden = true;
      renderLastRow();
      return;
    }
    card.hidden = false;
    if (link) { link.hidden = false; link.textContent = t("showHistory", { n: shown.length }); }
    list.innerHTML = shown.map(function (m) {
      return '<div class="histitem"><div class="histmeta"><b>' + escapeHtml(m.sender || "?") +
        '</b><span>' + escapeHtml(relativeTime(m.created_at)) + '</span></div>' +
        '<div class="histtext">' + escapeHtml(m.text || "") + '</div></div>';
    }).join("");
    renderLastRow();
  }
  function renderLastRow() {
    var row = el("lastRow"), out = el("lastMsg");
    if (!row || !out) return;
    var m = history[0];
    if (!m || !m.text) { row.hidden = true; return; }
    row.hidden = false;
    out.innerHTML = escapeHtml(m.text) + ' <span class="when">— ' +
      escapeHtml(m.sender || "?") + ", " + escapeHtml(relativeTime(m.created_at)) + "</span>";
  }

  /* Non-blocking notice that a message is already on the display (option B).
     Driven by grandpa/status (sender + expiry) so it works with the sender
     token; the message text is shown too if the token may read grandpa/message. */
  function bannerFrom(d) {
    return d && d.sender ? t("bannerFrom", { name: "<b>" + escapeHtml(d.sender) + "</b>" }) : "";
  }
  function bannerExpires(d) {
    var info = GP.statusInfo(d);
    if (info.remainingMs == null) return "";
    var mins = Math.max(1, Math.round(info.remainingMs / 60000));
    return t("bannerExpires", { m: mins });
  }
  /* Only a fresh, unexpired, actually-showing status is presented as live. The
     status topic is retained, so without this a page opening onto a stale
     "showing" snapshot would pin the old message with "(expires in 1 min)". */
  function updateBannerFromStatus(d) {
    var b = el("banner");
    var info = GP.statusInfo(d);
    if (!d || !info.showLive) { b.hidden = true; return; }
    var replaced = (myLastId && d.current_message_id !== myLastId && (Date.now() - myLastAt) < 6000)
      ? "<br>" + t("bannerReplaced") : "";
    b.hidden = false;
    var head = d.text
      ? t("bannerMsg", { text: "<b>" + escapeHtml(d.text) + "</b>" })
      : t("bannerOnScreen");
    b.innerHTML = head + bannerFrom(d) + bannerExpires(d) + t("bannerReplaces") + replaced;
  }

  function renderBannerFromMessage(raw) {
    var b = el("banner");
    var text = (raw == null) ? "" : String(raw);
    if (!text || text === "{}") { updateBannerFromStatus(lastStatus); return; }
    var d; try { d = JSON.parse(text); } catch (e) { return; }
    if (!d || !d.text) { updateBannerFromStatus(lastStatus); return; }
    var exp = d.expires_at ? new Date(d.expires_at).getTime() : 0;
    if (exp && exp <= Date.now()) { updateBannerFromStatus(lastStatus); return; } // expired → status decides
    var replaced = (myLastId && d.id && d.id !== myLastId && (Date.now() - myLastAt) < 6000)
      ? "<br>" + t("bannerReplaced") : "";
    b.hidden = false;
    b.innerHTML = t("bannerMsg", { text: "<b>" + escapeHtml(d.text) + "</b>" }) +
      bannerFrom(d) + bannerExpires(d) + t("bannerReplaces") + replaced;
  }

  /* ---------- mqtt ---------- */
  /* A fresh, unique clientId per connection. The sender runs clean sessions, so
     a persisted id buys nothing and, worse, makes two instances that share
     localStorage (two tabs, or the installed PWA + a tab) collide: the broker
     drops the older session on each CONNECT and the two kick each other off
     forever — the connect/disconnect flicker. A per-connection random id makes
     each instance independent. */
  function clientId() {
    return "grandpa-web-" + Math.random().toString(16).slice(2) + "-" + Date.now().toString(16);
  }

  function connect() {
    var c = creds();
    if (!c.broker || !c.token) { el("sendHint").textContent = t("errBrokerToken"); return; }
    localStorage.setItem(LS.broker, c.broker);
    localStorage.setItem(LS.token, c.token);
    localStorage.setItem(LS.sender, c.name);

    manualClose = false;
    disconnectedSince = 0;
    lastAttempt = Date.now();
    if (client) { try { client.end(true); } catch (e) {} }
    setConnected(false);
    el("sendHint").textContent = t("connecting");

    client = mqtt.connect(c.broker, {
      username: c.token,
      clientId: clientId(),
      clean: true,
      keepalive: 30,
      reconnectPeriod: 5000,
      connectTimeout: 12000,
      protocolVersion: 4,
    });
    client.on("connect", function () {
      disconnectedSince = 0;
      setConnected(true);
      el("sendHint").textContent = t("connectedMsg");
      // Re-adopt the broker's retained history before maintaining it again.
      historyLoaded = false; pendingMessage = null;
      historyCount = resolveHistoryCount();
      client.subscribe([TOPIC.status, TOPIC.message, TOPIC.last], { qos: 1 });
      // The sender token needs `subscribe grandpa/config` for the admin's
      // history_count to reach every device; a separate call means a denied
      // config grant cannot affect the subscriptions above.
      try { client.subscribe([TOPIC.config], { qos: 1 }); } catch (e) {}
      armHistorySettle();
    });
    client.on("reconnect", function () { el("sendHint").textContent = t("reconnecting"); });
    client.on("close", function () {
      setConnected(false);
      if (!disconnectedSince) disconnectedSince = Date.now();
    });
    client.on("offline", function () {
      setConnected(false);
      if (!disconnectedSince) disconnectedSince = Date.now();
    });
    client.on("error", function (err) { setConnected(false); el("sendHint").textContent = t("errorPrefix") + err.message; });
    client.on("message", function (topic, payload) {
      if (topic === TOPIC.status) {
        try {
          lastStatus = JSON.parse(payload.toString());
          historyCount = resolveHistoryCount();
          renderDiag(lastStatus); updateBannerFromStatus(lastStatus); applyRemoteDefault(lastStatus); updateConnPill();
          renderHistory();
        } catch (e) { el("diag").textContent = payload.toString(); }
      } else if (topic === TOPIC.message) {
        var raw = payload.toString();
        var m = null;
        try { m = raw ? JSON.parse(raw) : null; lastMessageText = (m && m.text) ? m.text : null; }
        catch (e) { lastMessageText = null; m = null; }
        renderBannerFromMessage(raw);
        if (m) recordMessage(m);
        if (lastStatus) renderDiag(lastStatus);
      } else if (topic === TOPIC.last) {
        adoptHistory(payload.toString());
        if (lastStatus) renderDiag(lastStatus);
      } else if (topic === TOPIC.config) {
        applyRemoteHistoryCount(payload.toString());
      }
    });
  }

  /* Auto-connect. The page can be opened before the network is up, restored from
     the back/forward cache, or resumed after the OS suspended the tab (which
     throttles JS timers, so mqtt.js's own reconnect never fires) — in all of
     those the one-shot connect() leaves the pill stuck on "disconnected" until a
     manual refresh. ensureConnected() heals any of them without a reload. It is
     idempotent: it never tears down a healthy link and never stacks retries. */
  function ensureConnected() {
    if (manualClose || !localStorage.getItem(LS.token)) return;
    if (!client) { connect(); return; }
    if (client.connected) return;
    if (client.reconnecting) return;        // mqtt.js is already retrying
    if (Date.now() - lastAttempt < 13000) return; // let the current attempt finish
    // Not connected and not retrying: rebuild a client that has been dead for a
    // while, otherwise just nudge the existing one to retry now.
    if (disconnectedSince && Date.now() - disconnectedSince > 60000) { connect(); return; }
    try { client.reconnect(); } catch (e) { connect(); }
  }

  function publishMessage() {
    var name = (localStorage.getItem(LS.sender) || el("sender").value || "").trim();
    var text = el("text").value.trim();
    if (!name) { el("sendHint").textContent = t("errEnterName"); return; }
    if (!text) { el("sendHint").textContent = t("errTypeMessage"); return; }
    if (!client || !client.connected) { el("sendHint").textContent = t("errNotConnected"); return; }

    var now = Date.now();
    var payload = {
      id: uuid(),
      sender: name,
      text: text,
      created_at: new Date(now).toISOString().replace(/\.\d+Z$/, "Z"),
      expires_at: new Date(now + minutes * 60000).toISOString().replace(/\.\d+Z$/, "Z"),
      duration_minutes: minutes,
    };
    client.publish(TOPIC.message, JSON.stringify(payload), { qos: 1, retain: true });
    // Mirror it into history (grandpa/last). A clear empties grandpa/message but
    // never touches grandpa/last, so the last message stays retrievable.
    recordMessage(payload);
    myLastId = payload.id; myLastAt = Date.now();
    el("text").value = "";
    clearDraft();
    el("sendHint").textContent = t("sentMsg");
  }

  function clearDisplay() {
    if (!client || !client.connected) { el("sendHint").textContent = t("errNotConnected"); return; }
    showDialog(t("clearTitle"), t("clearBody"), t("clearConfirm"), function () {
      client.publish(TOPIC.message, "", { qos: 1, retain: true });
      el("sendHint").textContent = t("clearedMsg");
    });
  }

  /* ---------- first run ---------- */
  function setupSave() {
    var name = el("setupName").value.trim();
    var token = el("setupToken").value.trim();
    var broker = el("setupBroker").value.trim() || DEFAULT_BROKER;
    if (!name) { el("setupHint").textContent = t("errSetupName"); return; }
    if (!token) { el("setupHint").textContent = t("errTokenMissing"); return; }
    localStorage.setItem(LS.sender, name);
    localStorage.setItem(LS.token, token);
    localStorage.setItem(LS.broker, broker);
    el("sender").value = name;
    el("token").value = token;
    el("brokerUrl").value = broker;
    connect();
    showView("main");
  }

  function forget() {
    manualClose = true;
    localStorage.removeItem(LS.token);
    if (client) { try { client.end(true); } catch (e) {} client = null; }
    setConnected(false);
    disconnectedSince = 0;
    el("token").value = "";
    el("setupToken").value = "";
    showView("setup");
    el("setupHint").textContent = t("tokenForgotten");
  }

  /* ---------- service worker (offline app shell) ---------- */
  function registerSW() {
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("./sw.js").catch(function () {});
    }
  }

  /* ---------- wire up ---------- */
  function init() {
    applyStatic();
    registerSW();
    if (typeof mqtt === "undefined") { el("setupHint").textContent = t("errNoMqtt"); showView("setup"); return; }

    buildPresets();
    // Remember the duration this browser last chose; otherwise take the default.
    var storedDur = parseInt(localStorage.getItem(LS.duration), 10);
    if (isFinite(storedDur)) { userChangedDuration = true; setMinutes(storedDur); }
    else { setMinutes(DEFAULT_MIN); }

    el("setupName").value = localStorage.getItem(LS.sender) || "";
    el("setupBroker").value = localStorage.getItem(LS.broker) || DEFAULT_BROKER;
    el("sender").value = localStorage.getItem(LS.sender) || "";
    el("brokerUrl").value = localStorage.getItem(LS.broker) || DEFAULT_BROKER;
    el("token").value = localStorage.getItem(LS.token) || "";
    // Restore a half-typed message (an accidental reload must not lose it).
    var draft = localStorage.getItem(LS.draft) || "";
    if (draft) el("text").value = draft;

    el("minus").addEventListener("click", function () { userChangedDuration = true; setMinutes(minutes - 60); persistDuration(); });
    el("plus").addEventListener("click", function () { userChangedDuration = true; setMinutes(minutes + 60); persistDuration(); });
    el("send").addEventListener("click", publishMessage);
    el("clear").addEventListener("click", clearDisplay);
    el("connect").addEventListener("click", connect);
    el("forget").addEventListener("click", forget);
    el("setupSave").addEventListener("click", setupSave);
    // Enter sends; Shift+Enter inserts a newline.
    el("text").addEventListener("keydown", function (e) {
      if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); publishMessage(); }
    });
    el("text").addEventListener("input", saveDraft);
    // Confirm dialog wiring.
    el("modalCancel").addEventListener("click", closeDialog);
    el("modalOk").addEventListener("click", function () { var fn = dialogOk; closeDialog(); if (fn) fn(); });
    el("modal").addEventListener("click", function (e) { if (e.target === el("modal")) closeDialog(); });
    document.addEventListener("keydown", function (e) { if (e.key === "Escape" && !el("modal").hidden) closeDialog(); });
    updateSendEnabled();
    // Offer an "Install app" button (or a hint on browsers that can't install).
    if (GP.setupInstall) GP.setupInstall("installCard");

    // "Show last N messages" scrolls to (and opens) the history section below.
    if (el("historyLink")) el("historyLink").addEventListener("click", function (e) {
      e.preventDefault();
      var det = el("historyDetails"); if (det) det.open = true;
      if (el("historyCard") && el("historyCard").scrollIntoView) {
        el("historyCard").scrollIntoView({ behavior: "smooth", block: "start" });
      }
    });
    // The admin page (same origin) mirrors the count to localStorage; follow it
    // live when it changes in another tab.
    window.addEventListener("storage", function (e) {
      if (e.key === "gp_history_count") { historyCount = resolveHistoryCount(); renderHistory(); }
    });

    // Heal a dropped/never-established link without a manual refresh: when the
    // tab is shown again, the network returns, the page is restored from the
    // bfcache, and as a slow safety net on a timer.
    window.addEventListener("online", ensureConnected);
    window.addEventListener("focus", ensureConnected);
    window.addEventListener("pageshow", ensureConnected);
    document.addEventListener("visibilitychange", function () {
      if (!document.hidden) ensureConnected();
    });
    setInterval(ensureConnected, 15000);
    // Re-evaluate reachability so the pill flips to "phone offline" when the
    // phone's status goes stale even though the broker link is still up.
    setInterval(updateConnPill, 10000);
    // Keep the "on screen until HH:MM" line honest as time passes.
    setInterval(updateDurWhen, 30000);

    if (applyInvite()) return;
    if (localStorage.getItem(LS.sender) && localStorage.getItem(LS.token)) {
      showView("main");
      connect();
    } else {
      showView("setup");
    }
  }

  /* A link carrying the token (and usually the name) onboards in one tap. Read
     it before the stored-credentials branch above, then strip it from the URL. */
  function applyInvite() {
    var inv = readInvite();
    if (!inv) return false;
    localStorage.setItem(LS.token, inv.token);
    if (inv.broker) localStorage.setItem(LS.broker, inv.broker);
    if (inv.name) localStorage.setItem(LS.sender, inv.name);
    el("setupToken").value = inv.token;
    el("token").value = inv.token;
    if (inv.broker) { el("setupBroker").value = inv.broker; el("brokerUrl").value = inv.broker; }
    if (inv.name) { el("setupName").value = inv.name; el("sender").value = inv.name; }
    clearInviteParams();
    if (inv.name) { showView("main"); connect(); return true; }
    showView("setup");
    el("setupHint").textContent = t("inviteApplied");
    return true;
  }

  document.addEventListener("DOMContentLoaded", init);

  /* ---- dev self-test hook: ?selftest=1&broker=…&token=…&sender=…&text=… ---- */
  var q = new URLSearchParams(location.search);
  if (q.get("selftest") === "1") {
    document.addEventListener("DOMContentLoaded", function () {
      var url = q.get("broker") || DEFAULT_BROKER;
      var token = q.get("token") || localStorage.getItem(LS.token) || "";
      client = mqtt.connect(url, { username: token, clientId: "selftest-" + Math.random().toString(16).slice(2), clean: true, reconnectPeriod: 0, connectTimeout: 10000 });
      client.on("connect", function () {
        var now = Date.now();
        var payload = { id: uuid(), sender: q.get("sender") || "SELFTEST", text: q.get("text") || "web-sender selftest", created_at: new Date(now).toISOString(), expires_at: new Date(now + 60000).toISOString(), duration_minutes: 1 };
        client.publish(TOPIC.message, JSON.stringify(payload), { qos: 1, retain: true }, function () {
          document.title = "SELFTEST_OK"; el("diag").textContent = "SELFTEST_OK";
        });
      });
      client.on("error", function (e) { document.title = "SELFTEST_ERR " + e.message; el("diag").textContent = "SELFTEST_ERR " + e.message; });
    });
  }
})();
