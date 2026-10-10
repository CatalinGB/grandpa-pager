/* Grandpa Pager — web sender.
 * Static, zero-backend. Connects to the broker over WSS with MQTT.js and
 * publishes the SAME payload schema as the Android sender (including `sender`).
 * The token is stored only in this browser's localStorage.
 */
(function () {
  "use strict";

  var LS = {
    token: "gp_token",
    sender: "gp_sender",
    broker: "gp_broker",
    client: "gp_client",
  };
  var TOPIC = { message: "grandpa/message", command: "grandpa/command", status: "grandpa/status" };
  var DEFAULT_BROKER = "wss://mqtt.flespi.io:443";
  var DEFAULT_MIN = 240;
  var PRESETS = [60, 240, 480, 1440];

  var el = function (id) { return document.getElementById(id); };
  var client = null;
  var minutes = DEFAULT_MIN;

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
      b.dataset.min = m;
      b.textContent = fmt(m);
      b.addEventListener("click", function () { setMinutes(m); });
      el("presets").appendChild(b);
    });
  }

  /* ---------- status pill / diagnostics ---------- */
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
      "expiră:   " + (d.expires_at || "-") + "\n" +
      "văzut:    " + age + "s în urmă";
  }

  /* ---------- mqtt ---------- */
  function uuid() {
    if (crypto && crypto.randomUUID) return crypto.randomUUID();
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
    var url = el("brokerUrl").value.trim() || DEFAULT_BROKER;
    var token = el("token").value.trim();
    if (!url || !token) { el("sendHint").textContent = "Completează brokerul și tokenul."; return; }

    localStorage.setItem(LS.broker, url);
    localStorage.setItem(LS.token, token);

    if (client) { try { client.end(true); } catch (e) {} }
    setConnected(false);
    el("sendHint").textContent = "Se conectează…";

    client = mqtt.connect(url, {
      username: token,
      clientId: clientId(),
      clean: true,
      reconnectPeriod: 5000,
      connectTimeout: 12000,
      protocolVersion: 4,
    });

    client.on("connect", function () {
      setConnected(true);
      el("sendHint").textContent = "Conectat.";
      client.subscribe(TOPIC.status, { qos: 1 });
    });
    client.on("reconnect", function () { el("sendHint").textContent = "Se reconectează…"; });
    client.on("close", function () { setConnected(false); });
    client.on("error", function (err) { setConnected(false); el("sendHint").textContent = "Eroare: " + err.message; });
    client.on("message", function (topic, payload) {
      if (topic !== TOPIC.status) return;
      try { renderDiag(JSON.parse(payload.toString())); }
      catch (e) { el("diag").textContent = payload.toString(); }
    });
  }

  function publishMessage() {
    var sender = el("sender").value.trim();
    var text = el("text").value.trim();
    if (!sender) { el("sendHint").textContent = "Scrie numele tău."; return; }
    if (!text) { el("sendHint").textContent = "Scrie un mesaj."; return; }
    if (!client || !client.connected) { el("sendHint").textContent = "Nu ești conectat."; return; }

    localStorage.setItem(LS.sender, sender);
    var now = Date.now();
    var payload = {
      id: uuid(),
      sender: sender,
      text: text,
      created_at: new Date(now).toISOString().replace(/\.\d+Z$/, "Z"),
      expires_at: new Date(now + minutes * 60000).toISOString().replace(/\.\d+Z$/, "Z"),
      duration_minutes: minutes,
    };
    client.publish(TOPIC.message, JSON.stringify(payload), { qos: 1, retain: true });
    el("text").value = "";
    el("sendHint").textContent = "Trimis — apare pe ecran.";
  }

  function clearDisplay() {
    if (!client || !client.connected) { el("sendHint").textContent = "Nu ești conectat."; return; }
    client.publish(TOPIC.message, "", { qos: 1, retain: true });
    el("sendHint").textContent = "Ecran șters.";
  }

  /* ---------- wire up ---------- */
  function init() {
    if (typeof mqtt === "undefined") {
      el("sendHint").textContent = "Eroare: biblioteca MQTT (mqtt.min.js) nu s-a încărcat.";
      return;
    }
    buildPresets();
    setMinutes(DEFAULT_MIN);

    el("sender").value = localStorage.getItem(LS.sender) || "";
    el("brokerUrl").value = localStorage.getItem(LS.broker) || DEFAULT_BROKER;
    el("token").value = localStorage.getItem(LS.token) || "";

    el("minus").addEventListener("click", function () { setMinutes(minutes - 60); });
    el("plus").addEventListener("click", function () { setMinutes(minutes + 60); });
    el("send").addEventListener("click", publishMessage);
    el("clear").addEventListener("click", clearDisplay);
    el("connect").addEventListener("click", connect);
    el("forget").addEventListener("click", function () {
      localStorage.removeItem(LS.token);
      el("token").value = "";
      if (client) { try { client.end(true); } catch (e) {} client = null; }
      setConnected(false);
      el("sendHint").textContent = "Token uitat.";
    });

    if (el("token").value) connect();
  }

  document.addEventListener("DOMContentLoaded", init);

  /* ---- dev self-test hook (used by CI/e2e): ?selftest=1&token=…&broker=… ---- */
  var q = new URLSearchParams(location.search);
  if (q.get("selftest") === "1") {
    document.addEventListener("DOMContentLoaded", function () {
      var url = q.get("broker") || DEFAULT_BROKER;
      var token = q.get("token") || localStorage.getItem(LS.token) || "";
      client = mqtt.connect(url, { username: token, clientId: "selftest-" + Math.random().toString(16).slice(2), clean: true, reconnectPeriod: 0, connectTimeout: 10000 });
      client.on("connect", function () {
        client.publish(TOPIC.message, JSON.stringify(PagerTest_()), { qos: 1, retain: true }, function () {
          document.title = "SELFTEST_OK"; el("diag").textContent = "SELFTEST_OK";
        });
      });
      client.on("error", function (e) { document.title = "SELFTEST_ERR " + e.message; el("diag").textContent = "SELFTEST_ERR " + e.message; });
    });
  }
  function PagerTest_() {
    var now = Date.now();
    return { id: uuid(), sender: q.get("sender") || "SELFTEST", text: q.get("text") || "web-sender selftest", created_at: new Date(now).toISOString(), expires_at: new Date(now + 60000).toISOString(), duration_minutes: 1 };
  }
})();
