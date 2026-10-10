/* Grandpa Pager — admin mode. Publishes a versioned config to grandpa/config
 * (retained) with the admin token, and reads the receiver's status back to
 * prefill. Token is kept in sessionStorage only. */
(function () {
  "use strict";
  var BROKER = "wss://mqtt.flespi.io:443";
  var TOPIC = { config: "grandpa/config", status: "grandpa/status" };
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
  function setPath(path, v) { var p = path.split("."); var o = cfg; for (var i = 0; i < p.length - 1; i++) o = o[p[i]]; o[p[p.length - 1]] = v; dirty = true; }
  function fmtH(m) { return m >= 60 ? ((m % 60) ? +(m / 60).toFixed(1) : (m / 60)) + " h" : m + " min"; }
  function fmtMs(ms) { return (ms / 1000) + " s"; }
  function fmtOf(fmt, v) { return fmt === "ms" ? fmtMs(v) : fmt === "h" ? fmtH(v) : "" + v; }

  /* ---- control markup (no re-render on change; handlers update in place) ---- */
  function sw(path, label, desc) {
    var v = get(path);
    return '<div class="row"><div><div>' + label + '</div>' + (desc ? '<div class="sub">' + desc + '</div>' : '') + '</div>' +
      '<button class="sw ' + (v ? "on" : "") + '" data-path="' + path + '" onclick="A.toggle(this)"></button></div>';
  }
  function slider(path, label, min, max, step, fmt) {
    var v = get(path);
    return '<div class="row"><div>' + label + '</div>' +
      '<input type="range" data-path="' + path + '" data-fmt="' + (fmt || "") + '" min="' + min + '" max="' + max + '" step="' + step + '" value="' + v + '" oninput="A.slide(this)">' +
      '<span class="val">' + (fmt ? fmtOf(fmt, v) : v) + '</span></div>';
  }
  function seg(path, label, opts) {
    var v = get(path);
    return '<div class="row"><div>' + label + '</div><div class="seg">' +
      opts.map(function (o) { return '<button data-path="' + path + '" data-val="' + o + '" class="' + (o === v ? "on" : "") + '" onclick="A.choose(this)">' + o + '</button>'; }).join("") + '</div></div>';
  }
  function stepper(path, label, min, max, step, fmt) {
    var v = get(path);
    return '<div class="row"><div>' + label + '</div><div class="stepper">' +
      '<button data-path="' + path + '" data-delta="-' + step + '" data-min="' + min + '" data-max="' + max + '" data-step="' + step + '" data-fmt="' + (fmt || "") + '" onclick="A.step(this)">−</button>' +
      '<b>' + (fmt ? fmtOf(fmt, v) : v) + '</b>' +
      '<button data-path="' + path + '" data-delta="' + step + '" data-min="' + min + '" data-max="' + max + '" data-step="' + step + '" data-fmt="' + (fmt || "") + '" onclick="A.step(this)">+</button></div></div>';
  }

  function build() {
    el("sec-display").innerHTML = seg("display.orientation", "Orientare", ["auto", "portrait", "landscape"]) +
      slider("display.name_size_sp", "Mărime nume", 20, 120, 2) + sw("display.countdown", "Arată contorul");
    el("sec-cue").innerHTML = slider("cue.glow_ms", "Strălucire", 0, 20000, 500, "ms") +
      sw("cue.chime", "Sunet") + sw("cue.vibrate", "Vibrație") + sw("cue.torch.enabled", "Lanternă") +
      slider("cue.torch.on_ms", "Aprins", 200, 5000, 100, "ms") +
      slider("cue.torch.off_ms", "Stins", 200, 5000, 100, "ms") +
      stepper("cue.torch.pulses", "Pulsuri", 1, 10, 1);
    el("sec-defaults").innerHTML = stepper("defaults.duration_minutes", "Durată implicită", 5, 1440, 30, "h");
    el("sec-kiosk").innerHTML = sw("kiosk.locked", "Blocat (kiosk)", "oprit = deschide launcherul");
    preview();
  }
  function preview() { el("jsonPreview").textContent = JSON.stringify(cfg, null, 1); }

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
      if (!dirty && s.config) { cfg = JSON.parse(JSON.stringify(s.config)); cfg.schema = cfg.schema || 1; build(); el("applied").textContent = "config curent preluat de pe telefon"; }
    });
  }

  function apply() {
    if (!client || !client.connected) { el("applied").textContent = "Nu ești conectat."; return; }
    client.publish(TOPIC.config, JSON.stringify(cfg), { qos: 1, retain: true });
    dirty = false;
    el("applied").textContent = "Trimis — telefonul va aplica și va confirma în status.";
  }
  function reset() { cfg = JSON.parse(JSON.stringify(DEF)); dirty = true; build(); }

  function showForm() { el("unlock").hidden = true; el("admin").hidden = false; build(); }

  window.A = { toggle: toggle, slide: slide, choose: choose, step: step };

  document.addEventListener("DOMContentLoaded", function () {
    var q = new URLSearchParams(location.search);
    if (q.get("demo") === "1") { showForm(); el("applied").textContent = "mod demo (fără broker)"; }
    el("unlockBtn").addEventListener("click", connect);
    el("apply").addEventListener("click", apply);
    el("reset").addEventListener("click", reset);
    var saved = sessionStorage.getItem("gp_admin");
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
    var fails = checks.filter(function (c) { return c.indexOf("OK ") !== 0; }).length;
    document.title = fails ? ("SELFTEST_FAIL " + fails) : "SELFTEST_OK";
    var pre = el("jsonPreview"); pre.textContent = "SELFTEST: " + (fails ? "FAIL" : "OK") + "\n" + checks.join("\n") + "\n\n" + JSON.stringify(cfg, null, 1);
  }
})();
