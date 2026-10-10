/* Grandpa Pager — admin mode. Publishes a versioned config to grandpa/config
 * (retained) with the admin token, and reads the receiver's status back to
 * prefill. Token is kept in sessionStorage only. */
(function () {
  "use strict";
  var BROKER = "wss://mqtt.flespi.io:443";
  var TOPIC = { config: "grandpa/config", status: "grandpa/status", message: "grandpa/message" };
  var DEF = {
    schema: 1,
    display: { name_size_sp: 46, countdown: false, orientation: "auto" },
    cue: { glow_ms: 9000, chime: true, vibrate: true, torch: { enabled: true, on_ms: 1500, off_ms: 1500, pulses: 3 } },
    defaults: { duration_minutes: 240 },
    kiosk: { locked: true }
  };
  var el = function (id) { return document.getElementById(id); };
  var cfg = JSON.parse(JSON.stringify(DEF));
  var dirty = false, client = null;

  var get = function (path) { return path.split(".").reduce(function (o, k) { return o[k]; }, cfg); };
  var set = function (path, v) { var p = path.split("."); var o = cfg; for (var i = 0; i < p.length - 1; i++) o = o[p[i]]; o[p[p.length - 1]] = v; dirty = true; preview(); };
  var fmtH = function (m) { return m >= 60 ? ((m % 60) ? (m / 60).toFixed(1) : (m / 60)) + " h" : m + " min"; };
  var fmtMs = function (ms) { return (ms / 1000) + " s"; };

  function sw(path, label, desc) {
    var v = get(path);
    return '<div class="row"><div><div>' + label + '</div>' + (desc ? '<div class="sub">' + desc + '</div>' : '') + '</div>' +
      '<button class="sw ' + (v ? "on" : "") + '" onclick="A.set(\'' + path + '\',' + !v + ')"></button></div>';
  }
  function slider(path, label, min, max, step, disp) {
    var v = get(path);
    return '<div class="row"><div>' + label + '</div>' +
      '<input type="range" min="' + min + '" max="' + max + '" step="' + step + '" value="' + v + '" oninput="A.set(\'' + path + '\',+this.value)">' +
      '<span class="val">' + (disp ? disp(v) : v) + '</span></div>';
  }
  function seg(path, label, opts) {
    var v = get(path);
    return '<div class="row"><div>' + label + '</div><div class="seg">' +
      opts.map(function (o) { return '<button class="' + (o === v ? "on" : "") + '" onclick="A.set(\'' + path + '\',\'' + o + '\')">' + o + '</button>'; }).join("") + '</div></div>';
  }
  function stepper(path, label, min, max, step, disp) {
    var v = get(path);
    return '<div class="row"><div>' + label + '</div><div class="stepper">' +
      '<button onclick="A.set(\'' + path + '\',Math.max(' + min + ',' + v + '-' + step + '))">−</button>' +
      '<b>' + (disp ? disp(v) : v) + '</b>' +
      '<button onclick="A.set(\'' + path + '\',Math.min(' + max + ',' + v + '+' + step + '))">+</button></div></div>';
  }

  function build() {
    el("sec-display").innerHTML = seg("display.orientation", "Orientare", ["auto", "portrait", "landscape"]) +
      slider("display.name_size_sp", "Mărime nume", 20, 120, 2) +
      sw("display.countdown", "Arată contorul");
    el("sec-cue").innerHTML = slider("cue.glow_ms", "Strălucire", 0, 20000, 500, fmtMs) +
      sw("cue.chime", "Sunet") + sw("cue.vibrate", "Vibrație") +
      sw("cue.torch.enabled", "Lanternă") +
      slider("cue.torch.on_ms", "Aprins", 200, 5000, 100, fmtMs) +
      slider("cue.torch.off_ms", "Stins", 200, 5000, 100, fmtMs) +
      stepper("cue.torch.pulses", "Pulsuri", 1, 10, 1);
    el("sec-defaults").innerHTML = stepper("defaults.duration_minutes", "Durată implicită", 5, 1440, 30, fmtH);
    el("sec-kiosk").innerHTML = sw("kiosk.locked", "Blocat (kiosk)", "oprit = deschide launcherul");
    preview();
  }
  function preview() { el("jsonPreview").textContent = JSON.stringify(cfg, null, 1); }

  function renderDevice(s) {
    if (!s) { el("devStatus").textContent = "—"; return; }
    var seen = s.last_seen ? new Date(s.last_seen).getTime() : 0;
    var age = Math.max(0, Math.round((Date.now() - seen) / 1000));
    var online = s.state !== "offline" && age < 90;
    el("devStatus").innerHTML = '<span class="pill ' + (online ? "on" : "off") + '">' + (online ? "conectat" : "offline") + '</span>' +
      "<span>baterie " + s.battery + "%</span><span>wifi " + s.wifi + "</span><span>mqtt " + s.mqtt + "</span>" +
      (s.sender ? "<span>mesaj: " + s.sender + "</span>" : "") + "<span>văzut " + age + "s</span>";
  }

  function connect() {
    var token = el("adminToken").value.trim();
    if (!token) { el("unlockHint").textContent = "Introdu tokenul."; return; }
    sessionStorage.setItem("gp_admin", token);
    if (client) { try { client.end(true); } catch (e) {} }
    el("unlockHint").textContent = "Se conectează…";
    client = mqtt.connect(BROKER, { username: token, clientId: "grandpa-admin-" + Math.random().toString(16).slice(2), clean: true, reconnectPeriod: 5000, connectTimeout: 12000, protocolVersion: 4 });
    client.on("connect", function () {
      el("unlock").hidden = true; el("admin").hidden = false;
      el("connPill").textContent = "conectat"; el("connPill").className = "pill on";
      client.subscribe([TOPIC.status, TOPIC.config], { qos: 1 });
    });
    client.on("error", function (e) { el("unlockHint").textContent = "Eroare: " + e.message; });
    client.on("close", function () { el("connPill").textContent = "neconectat"; el("connPill").className = "pill off"; });
    client.on("message", function (topic, payload) {
      if (topic !== TOPIC.status) return;
      var s; try { s = JSON.parse(payload.toString()); } catch (e) { return; }
      renderDevice(s);
      if (!dirty && s.config) { cfg = Object.assign({}, DEF, s.config); build(); el("applied").textContent = "config curent preluat de pe telefon"; }
    });
  }

  function apply() {
    if (!client || !client.connected) { el("applied").textContent = "Nu ești conectat."; return; }
    client.publish(TOPIC.config, JSON.stringify(cfg), { qos: 1, retain: true });
    dirty = false;
    el("applied").textContent = "Trimis — telefonul va aplica și va confirma în status.";
  }
  function reset() { cfg = JSON.parse(JSON.stringify(DEF)); dirty = true; build(); }

  window.A = { set: set };
  document.addEventListener("DOMContentLoaded", function () {
    var saved = sessionStorage.getItem("gp_admin");
    if (saved) el("adminToken").value = saved;
    el("unlockBtn").addEventListener("click", connect);
    el("apply").addEventListener("click", apply);
    el("reset").addEventListener("click", reset);
    if (saved) connect();
  });
})();
