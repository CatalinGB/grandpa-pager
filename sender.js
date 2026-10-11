/* Grandpa Pager — web sender.
 * First run asks for the name + token (saved only in this browser). Afterwards
 * the page shows just the message box + duration picker, with a Settings box at
 * the bottom. Publishes the same payload schema as the Android sender.
 */
(function () {
  "use strict";

  var LS = { token: "gp_token", sender: "gp_sender", broker: "gp_broker" };
  var TOPIC = { message: "grandpa/message", command: "grandpa/command", status: "grandpa/status" };
  var DEFAULT_BROKER = "wss://mqtt.flespi.io:443";
  var DEFAULT_MIN = 240;
  var PRESETS = [60, 240, 480, 1440];

  var el = function (id) { return document.getElementById(id); };
  var client = null;
  var manualClose = false;      // set by "Forget token" so we stop auto-connecting
  var disconnectedSince = 0;    // epoch ms when the link dropped (0 = healthy)
  var lastAttempt = 0;          // epoch ms of the last connect() call
  var minutes = DEFAULT_MIN;
  var myLastId = null, myLastAt = 0, lastStatus = null;
  var lastMessageText = null;
  var userChangedDuration = false;

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
      sendButton: "Send to screen",
      clearButton: "Clear the message",
      deviceStatus: "Device status",
      noStatus: "No status yet.",
      settings: "Settings",
      token: "Token",
      reconnect: "Reconnect",
      forget: "Forget token",
      tokenSaved: "The token is saved only in this browser.",
      connConnected: "connected",
      connDisconnected: "disconnected",
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
      sendButton: "Trimite pe ecran",
      clearButton: "Șterge mesajul",
      deviceStatus: "Starea dispozitivului",
      noStatus: "Încă nu există status.",
      settings: "Setări",
      token: "Token",
      reconnect: "Reconectare",
      forget: "Uită tokenul",
      tokenSaved: "Tokenul este salvat doar în acest browser.",
      connConnected: "conectat",
      connDisconnected: "deconectat",
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
  function setMinutes(m) {
    minutes = Math.max(15, Math.min(1440, m));
    el("durValue").textContent = fmt(minutes);
    Array.prototype.forEach.call(el("presets").children, function (b) {
      b.classList.toggle("on", parseInt(b.dataset.min, 10) === minutes);
    });
  }
  function buildPresets() {
    PRESETS.forEach(function (m) {
      var b = document.createElement("button");
      b.dataset.min = m; b.textContent = fmt(m);
      b.addEventListener("click", function () { userChangedDuration = true; setMinutes(m); });
      el("presets").appendChild(b);
    });
  }

  /* ---------- status ---------- */
  function setConnected(ok) {
    var p = el("connPill");
    p.textContent = ok ? t("connConnected") : t("connDisconnected");
    p.className = "pill " + (ok ? "on" : "off");
  }
  function renderDiag(d) {
    var seen = d.last_seen ? new Date(d.last_seen).getTime() : 0;
    var age = Math.max(0, Math.round((Date.now() - seen) / 1000));
    var online = d.state !== "offline" && age < staleAfterSeconds(d);
    var temp = (typeof d.battery_temp_c === "number") ? " " + d.battery_temp_c.toFixed(1) + "°C" : "";
    var msg = d.text || lastMessageText || "";
    el("diag").textContent =
      t("diagState") + " " + d.state + (online ? "" : "  " + t("staleSuffix", { s: age })) + "\n" +
      t("diagMqtt") + " " + d.mqtt + "\n" +
      t("diagBattery") + " " + d.battery + "%" + (d.charging ? " " + t("chargingSuffix") : "") + temp + "\n" +
      t("diagWifi") + " " + d.wifi + "\n" +
      t("diagMessage") + " " + (d.sender || "-") + (msg ? " — " + msg : "") + "\n" +
      t("diagSeen") + " " + t("seenAgo", { s: age });
  }

  /* Adopt the admin-configured default duration until the user picks their own. */
  function applyRemoteDefault(d) {
    if (userChangedDuration) return;
    var m = d && d.config && d.config.defaults && d.config.defaults.duration_minutes;
    if (typeof m === "number") setMinutes(m);
  }

  /* Non-blocking notice that a message is already on the display (option B).
     Driven by grandpa/status (sender + expiry) so it works with the sender
     token; the message text is shown too if the token may read grandpa/message. */
  function bannerFrom(d) {
    return d && d.sender ? t("bannerFrom", { name: "<b>" + escapeHtml(d.sender) + "</b>" }) : "";
  }
  function bannerExpires(d) {
    if (!d || !d.expires_at) return "";
    var mins = Math.max(1, Math.round((new Date(d.expires_at).getTime() - Date.now()) / 60000));
    return t("bannerExpires", { m: mins });
  }
  function updateBannerFromStatus(d) {
    var b = el("banner");
    if (!d || d.state !== "showing" || !d.current_message_id) { b.hidden = true; return; }
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
      client.subscribe([TOPIC.status, TOPIC.message], { qos: 1 });
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
        try { lastStatus = JSON.parse(payload.toString()); renderDiag(lastStatus); updateBannerFromStatus(lastStatus); applyRemoteDefault(lastStatus); }
        catch (e) { el("diag").textContent = payload.toString(); }
      } else if (topic === TOPIC.message) {
        var raw = payload.toString();
        try { var m = raw ? JSON.parse(raw) : null; lastMessageText = (m && m.text) ? m.text : null; }
        catch (e) { lastMessageText = null; }
        renderBannerFromMessage(raw);
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
    myLastId = payload.id; myLastAt = Date.now();
    el("text").value = "";
    el("sendHint").textContent = t("sentMsg");
  }

  function clearDisplay() {
    if (!client || !client.connected) { el("sendHint").textContent = t("errNotConnected"); return; }
    client.publish(TOPIC.message, "", { qos: 1, retain: true });
    el("sendHint").textContent = t("clearedMsg");
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
    setMinutes(DEFAULT_MIN);

    el("setupName").value = localStorage.getItem(LS.sender) || "";
    el("setupBroker").value = localStorage.getItem(LS.broker) || DEFAULT_BROKER;
    el("sender").value = localStorage.getItem(LS.sender) || "";
    el("brokerUrl").value = localStorage.getItem(LS.broker) || DEFAULT_BROKER;
    el("token").value = localStorage.getItem(LS.token) || "";

    el("minus").addEventListener("click", function () { userChangedDuration = true; setMinutes(minutes - 60); });
    el("plus").addEventListener("click", function () { userChangedDuration = true; setMinutes(minutes + 60); });
    el("send").addEventListener("click", publishMessage);
    el("clear").addEventListener("click", clearDisplay);
    el("connect").addEventListener("click", connect);
    el("forget").addEventListener("click", forget);
    el("setupSave").addEventListener("click", setupSave);

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
