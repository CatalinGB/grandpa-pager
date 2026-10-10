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
  var minutes = DEFAULT_MIN;
  var myLastId = null, myLastAt = 0, lastStatus = null;

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
    p.textContent = ok ? "connected" : "disconnected";
    p.className = "pill " + (ok ? "on" : "off");
  }
  function renderDiag(d) {
    var seen = d.last_seen ? new Date(d.last_seen).getTime() : 0;
    var age = Math.max(0, Math.round((Date.now() - seen) / 1000));
    var online = d.state !== "offline" && age < 90;
    el("diag").textContent =
      "state:    " + d.state + (online ? "" : "   (STALE " + age + "s)") + "\n" +
      "mqtt:     " + d.mqtt + "\n" +
      "battery:  " + d.battery + "%" + (d.charging ? " (charging)" : "") + "\n" +
      "wifi:     " + d.wifi + "\n" +
      "message:  " + (d.sender || "-") + "\n" +
      "seen:     " + age + "s ago";
  }

  /* Non-blocking notice that a message is already on the display (option B).
     Driven by grandpa/status (sender + expiry) so it works with the sender
     token; the message text is shown too if the token may read grandpa/message. */
  function updateBannerFromStatus(d) {
    var b = el("banner");
    if (!d || d.state !== "showing" || !d.current_message_id) { b.hidden = true; return; }
    var who = d.sender ? " from <b>" + escapeHtml(d.sender) + "</b>" : "";
    var exp = "";
    if (d.expires_at) {
      var mins = Math.max(1, Math.round((new Date(d.expires_at).getTime() - Date.now()) / 60000));
      exp = " (expires in " + mins + " min)";
    }
    var replaced = (myLastId && d.current_message_id !== myLastId && (Date.now() - myLastAt) < 6000)
      ? "<br>⚠ Your message was just replaced by someone else." : "";
    b.hidden = false;
    b.innerHTML = "On screen now: a message" + who + exp + ". Sending replaces it." + replaced;
  }

  function renderBannerFromMessage(raw) {
    var b = el("banner");
    var text = (raw == null) ? "" : String(raw);
    if (!text || text === "{}") { updateBannerFromStatus(lastStatus); return; }
    var d; try { d = JSON.parse(text); } catch (e) { return; }
    if (!d || !d.text) { updateBannerFromStatus(lastStatus); return; }
    var who = d.sender ? " from <b>" + escapeHtml(d.sender) + "</b>" : "";
    var exp = "";
    if (d.expires_at) {
      var mins = Math.max(1, Math.round((new Date(d.expires_at).getTime() - Date.now()) / 60000));
      exp = " (expires in " + mins + " min)";
    }
    var replaced = (myLastId && d.id && d.id !== myLastId && (Date.now() - myLastAt) < 6000)
      ? "<br>⚠ Your message was just replaced by someone else." : "";
    b.hidden = false;
    b.innerHTML = "On screen now: “<b>" + escapeHtml(d.text) + "</b>”" + who + exp + ". Sending replaces it." + replaced;
  }

  /* ---------- mqtt ---------- */
  function uuid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0, v = c === "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }
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
    if (!c.broker || !c.token) { el("sendHint").textContent = "Broker or token missing."; return; }
    localStorage.setItem(LS.broker, c.broker);
    localStorage.setItem(LS.token, c.token);
    localStorage.setItem(LS.sender, c.name);

    if (client) { try { client.end(true); } catch (e) {} }
    setConnected(false);
    el("sendHint").textContent = "Connecting…";

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
      el("sendHint").textContent = "Connected.";
      client.subscribe([TOPIC.status, TOPIC.message], { qos: 1 });
    });
    client.on("reconnect", function () { el("sendHint").textContent = "Reconnecting…"; });
    client.on("close", function () { setConnected(false); });
    client.on("error", function (err) { setConnected(false); el("sendHint").textContent = "Error: " + err.message; });
    client.on("message", function (topic, payload) {
      if (topic === TOPIC.status) {
        try { lastStatus = JSON.parse(payload.toString()); renderDiag(lastStatus); updateBannerFromStatus(lastStatus); }
        catch (e) { el("diag").textContent = payload.toString(); }
      } else if (topic === TOPIC.message) {
        renderBannerFromMessage(payload.toString());
      }
    });
  }

  function publishMessage() {
    var name = (localStorage.getItem(LS.sender) || el("sender").value || "").trim();
    var text = el("text").value.trim();
    if (!name) { el("sendHint").textContent = "Enter your name (in Settings)."; return; }
    if (!text) { el("sendHint").textContent = "Type a message."; return; }
    if (!client || !client.connected) { el("sendHint").textContent = "You are not connected."; return; }

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
    el("sendHint").textContent = "Sent — it is on the screen.";
  }

  function clearDisplay() {
    if (!client || !client.connected) { el("sendHint").textContent = "You are not connected."; return; }
    client.publish(TOPIC.message, "", { qos: 1, retain: true });
    el("sendHint").textContent = "Screen cleared.";
  }

  /* ---------- first run ---------- */
  function setupSave() {
    var name = el("setupName").value.trim();
    var token = el("setupToken").value.trim();
    var broker = el("setupBroker").value.trim() || DEFAULT_BROKER;
    if (!name) { el("setupHint").textContent = "Enter your name."; return; }
    if (!token) { el("setupHint").textContent = "Token missing."; return; }
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
    el("setupHint").textContent = "Token forgotten. Enter it again.";
  }

  /* ---------- service worker (offline app shell) ---------- */
  function registerSW() {
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("./sw.js").catch(function () {});
    }
  }

  /* ---------- wire up ---------- */
  function init() {
    registerSW();
    if (typeof mqtt === "undefined") { el("setupHint").textContent = "Error: mqtt.min.js failed to load."; showView("setup"); return; }

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
