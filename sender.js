/* Grandpa Pager — web sender.
 * First run asks for the name + token (saved only in this browser). Afterwards
 * the page shows just the message box + duration picker, with a Settings box at
 * the bottom. Publishes the same payload schema as the Android sender.
 */
(function () {
  "use strict";

  var LS = { token: "gp_token", sender: "gp_sender", broker: "gp_broker", client: "gp_client" };
  var TOPIC = { message: "grandpa/message", command: "grandpa/command", status: "grandpa/status" };
  var DEFAULT_BROKER = "wss://mqtt.flespi.io:443";
  var DEFAULT_MIN = 240;
  var PRESETS = [60, 240, 480, 1440];

  var el = function (id) { return document.getElementById(id); };
  var client = null;
  var minutes = DEFAULT_MIN;
  var myLastId = null, myLastAt = 0;

  function escapeHtml(s) { return String(s).replace(/[&<>"]/g, function (c) { return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]; }); }

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

  /* ---------- duration (stepper B) ---------- */
  function fmt(m) {
    if (m >= 60) { var h = Math.floor(m / 60), mm = m % 60; return mm ? h + " h " + mm + " min" : h + " h"; }
    return m + " min";
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
      b.addEventListener("click", function () { setMinutes(m); });
      el("presets").appendChild(b);
    });
  }

  /* ---------- status ---------- */
  function setConnected(ok) {
    var p = el("connPill");
    p.textContent = ok ? "conectat" : "neconectat";
    p.className = "pill " + (ok ? "on" : "off");
  }
  function renderDiag(d) {
    var seen = d.last_seen ? new Date(d.last_seen).getTime() : 0;
    var age = Math.max(0, Math.round((Date.now() - seen) / 1000));
    var online = d.state !== "offline" && age < 90;
    el("diag").textContent =
      "stare:    " + d.state + (online ? "" : "   (ÎNVECHIT " + age + "s)") + "\n" +
      "mqtt:     " + d.mqtt + "\n" +
      "baterie:  " + d.battery + "%" + (d.charging ? " (se încarcă)" : "") + "\n" +
      "wifi:     " + d.wifi + "\n" +
      "mesaj:    " + (d.sender || "-") + "\n" +
      "văzut:    " + age + "s în urmă";
  }

  /* Non-blocking notice: shows any message already on the display (option B). */
  function renderBanner(raw) {
    var b = el("banner");
    var text = (raw == null) ? "" : String(raw);
    if (!text || text === "{}") { b.hidden = true; return; }
    var d; try { d = JSON.parse(text); } catch (e) { b.hidden = true; return; }
    if (!d || !d.text) { b.hidden = true; return; }

    var who = d.sender ? " de la <b>" + escapeHtml(d.sender) + "</b>" : "";
    var exp = "";
    if (d.expires_at) {
      var mins = Math.max(0, Math.round((new Date(d.expires_at).getTime() - Date.now()) / 60000));
      exp = " (expiră în " + Math.max(1, mins) + " min)";
    }
    var replaced = (myLastId && d.id && d.id !== myLastId && (Date.now() - myLastAt) < 6000)
      ? "<br>⚠ Mesajul tău tocmai a fost înlocuit de altcineva."
      : "";
    b.hidden = false;
    b.innerHTML = "Pe ecran acum: „<b>" + escapeHtml(d.text) + "</b>”" + who + exp + ". Dacă trimiți, îl înlocuiești." + replaced;
  }

  /* ---------- mqtt ---------- */
  function uuid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0, v = c === "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }
  function clientId() {
    var id = localStorage.getItem(LS.client);
    if (!id) { id = "grandpa-web-" + Math.random().toString(16).slice(2) + Date.now().toString(16); localStorage.setItem(LS.client, id); }
    return id;
  }

  function connect() {
    var c = creds();
    if (!c.broker || !c.token) { el("sendHint").textContent = "Lipsește brokerul sau tokenul."; return; }
    localStorage.setItem(LS.broker, c.broker);
    localStorage.setItem(LS.token, c.token);
    localStorage.setItem(LS.sender, c.name);

    if (client) { try { client.end(true); } catch (e) {} }
    setConnected(false);
    el("sendHint").textContent = "Se conectează…";

    client = mqtt.connect(c.broker, {
      username: c.token,
      clientId: clientId(),
      clean: true,
      reconnectPeriod: 5000,
      connectTimeout: 12000,
      protocolVersion: 4,
    });
    client.on("connect", function () {
      setConnected(true);
      el("sendHint").textContent = "Conectat.";
      client.subscribe([TOPIC.status, TOPIC.message], { qos: 1 });
    });
    client.on("reconnect", function () { el("sendHint").textContent = "Se reconectează…"; });
    client.on("close", function () { setConnected(false); });
    client.on("error", function (err) { setConnected(false); el("sendHint").textContent = "Eroare: " + err.message; });
    client.on("message", function (topic, payload) {
      if (topic === TOPIC.status) {
        try { renderDiag(JSON.parse(payload.toString())); }
        catch (e) { el("diag").textContent = payload.toString(); }
      } else if (topic === TOPIC.message) {
        renderBanner(payload.toString());
      }
    });
  }

  function publishMessage() {
    var name = (localStorage.getItem(LS.sender) || el("sender").value || "").trim();
    var text = el("text").value.trim();
    if (!name) { el("sendHint").textContent = "Scrie numele tău (în Setări)."; return; }
    if (!text) { el("sendHint").textContent = "Scrie un mesaj."; return; }
    if (!client || !client.connected) { el("sendHint").textContent = "Nu ești conectat."; return; }

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
    el("sendHint").textContent = "Trimis — apare pe ecran.";
  }

  function clearDisplay() {
    if (!client || !client.connected) { el("sendHint").textContent = "Nu ești conectat."; return; }
    client.publish(TOPIC.message, "", { qos: 1, retain: true });
    el("sendHint").textContent = "Ecran șters.";
  }

  /* ---------- first run ---------- */
  function setupSave() {
    var name = el("setupName").value.trim();
    var token = el("setupToken").value.trim();
    var broker = el("setupBroker").value.trim() || DEFAULT_BROKER;
    if (!name) { el("setupHint").textContent = "Scrie numele tău."; return; }
    if (!token) { el("setupHint").textContent = "Lipsește tokenul."; return; }
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
    localStorage.removeItem(LS.token);
    if (client) { try { client.end(true); } catch (e) {} client = null; }
    setConnected(false);
    el("token").value = "";
    el("setupToken").value = "";
    showView("setup");
    el("setupHint").textContent = "Token uitat. Introdu-l din nou.";
  }

  /* ---------- PWA: service worker + "install as app" popup ---------- */
  function initPwa() {
    if ("serviceWorker" in navigator) {
      window.addEventListener("load", function () {
        navigator.serviceWorker.register("./sw.js").catch(function () {});
      });
    }
    var bar = el("installBar"), text = el("installText"), go = el("installGo"), no = el("installNo");
    if (!bar) return;
    var deferred = null;

    var standalone = function () {
      return (window.matchMedia && matchMedia("(display-mode: standalone)").matches) || window.navigator.standalone === true;
    };
    var dismissed = function () {
      var t = parseInt(localStorage.getItem("gp_install_dismiss") || "0", 10);
      return t && (Date.now() - t) < 7 * 24 * 3600 * 1000; // nag at most weekly
    };
    function instructions() {
      var ua = navigator.userAgent;
      if (/Android/i.test(ua)) {
        return /Firefox/i.test(ua)
          ? "În Firefox: apasă meniul ⋮ (sus-dreapta) → <b>Instalează</b> sau <b>Adaugă pe ecranul principal</b>."
          : "În Chrome: apasă meniul ⋮ (sus-dreapta) → <b>Instalează aplicația</b> sau <b>Adaugă pe ecranul principal</b>.";
      }
      if (/iPhone|iPad|iPod/i.test(ua)) {
        return "În Safari: butonul Partajare ⤴ → <b>Adaugă la ecranul principal</b>.";
      }
      return "Pe calculator nu se instalează ca aplicație. Deschide pagina pe telefon (Chrome sau Firefox) și alege <b>Adaugă pe ecranul principal</b>.";
    }
    function promptCta() {
      text.textContent = "Instalează „Grandpa Pager” ca aplicație pe telefon.";
      go.hidden = false;
      bar.hidden = false;
    }

    // Dev/testing: ?install=1 → CTA popup, ?install=help → instructions.
    var force = new URLSearchParams(location.search).get("install");
    if (force === "1") { promptCta(); }
    else if (force === "help") { text.innerHTML = instructions(); go.hidden = true; bar.hidden = false; }

    window.addEventListener("beforeinstallprompt", function (e) {
      e.preventDefault();
      deferred = e;
      if (!standalone() && !dismissed()) promptCta();
    });
    window.addEventListener("appinstalled", function () { localStorage.setItem("gp_installed", "1"); bar.hidden = true; });

    go.addEventListener("click", function () {
      if (deferred) {
        deferred.prompt();
        deferred.userChoice.then(function () { deferred = null; bar.hidden = true; });
      } else {
        // No native prompt (Firefox, some Brave builds, desktop): show how-to.
        text.innerHTML = instructions();
        go.hidden = true;
      }
    });
    no.addEventListener("click", function () {
      localStorage.setItem("gp_install_dismiss", String(Date.now()));
      bar.hidden = true;
    });

    // Phones without an auto prompt (Firefox/Android, iOS): surface the manual path.
    if (!standalone() && !localStorage.getItem("gp_installed") && !dismissed() &&
        /Android|iPhone|iPad|iPod/i.test(navigator.userAgent)) {
      setTimeout(function () {
        if (bar.hidden && !deferred) { text.innerHTML = instructions(); go.hidden = true; bar.hidden = false; }
      }, 5000);
    }
  }

  /* ---------- wire up ---------- */
  function init() {
    initPwa();
    if (typeof mqtt === "undefined") { el("setupHint").textContent = "Eroare: mqtt.min.js nu s-a încărcat."; showView("setup"); return; }

    buildPresets();
    setMinutes(DEFAULT_MIN);

    el("setupName").value = localStorage.getItem(LS.sender) || "";
    el("setupBroker").value = localStorage.getItem(LS.broker) || DEFAULT_BROKER;
    el("sender").value = localStorage.getItem(LS.sender) || "";
    el("brokerUrl").value = localStorage.getItem(LS.broker) || DEFAULT_BROKER;
    el("token").value = localStorage.getItem(LS.token) || "";

    el("minus").addEventListener("click", function () { setMinutes(minutes - 60); });
    el("plus").addEventListener("click", function () { setMinutes(minutes + 60); });
    el("send").addEventListener("click", publishMessage);
    el("clear").addEventListener("click", clearDisplay);
    el("connect").addEventListener("click", connect);
    el("forget").addEventListener("click", forget);
    el("setupSave").addEventListener("click", setupSave);

    if (localStorage.getItem(LS.sender) && localStorage.getItem(LS.token)) {
      showView("main");
      connect();
    } else {
      showView("setup");
    }
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
