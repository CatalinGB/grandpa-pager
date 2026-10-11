/* Grandpa Pager — admin mode. Publishes a versioned config to grandpa/config
 * (retained) with the admin token, and reads the receiver's status back to
 * prefill. Token is kept in sessionStorage only. */
(function () {
  "use strict";
  var BROKER = "wss://mqtt.flespi.io:443";
  var TOPIC = { config: "grandpa/config", command: "grandpa/command", status: "grandpa/status" };
  var DEF = {
    schema: 1,
    display: {
      name_size_sp: 46, countdown: false, orientation: "auto",
      message_size_min_sp: 10, message_size_max_sp: 64,
      name_color: "#FFFAB005", per_sender_colors: true, message_color: "#FFFFFFFF",
      warn_color: "#FFFFB300", countdown_color: "#FF9E9E9E",
      idle_clock_scale: 1.3, idle_date_format: "auto", warn_text: null
    },
    cue: { glow_ms: 9000, chime: true, vibrate: true, torch: { enabled: true, on_ms: 1500, off_ms: 1500, pulses: 3 } },
    defaults: { duration_minutes: 240 },
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
  var dirty = false, client = null, lastAttempt = 0;

  /* Shared helper (common.js). */
  var staleAfterSeconds = GP.staleAfterSeconds;

  var get = function (path) { return path.split(".").reduce(function (o, k) { return o[k]; }, cfg); };
  function setPath(path, v) { var p = path.split("."); var o = cfg; for (var i = 0; i < p.length - 1; i++) o = o[p[i]]; o[p[p.length - 1]] = v; dirty = true; }
  function fmtH(m) { return m >= 60 ? ((m % 60) ? +(m / 60).toFixed(1) : (m / 60)) + " h" : m + " min"; }
  function fmtMs(ms) { return (ms / 1000) + " s"; }
  function fmtOf(fmt, v) { return fmt === "ms" ? fmtMs(v) : fmt === "h" ? fmtH(v) : "" + v; }

  /* ---- control markup (no re-render on change; handlers update in place) ----
     Controls carry `data-act`; a single delegated listener (see wireControls)
     dispatches them. No inline event handlers, so a strict CSP
     (`script-src 'self'`, no 'unsafe-inline') is possible. */
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
    el("sec-display").innerHTML = seg("display.orientation", "Orientation", ["auto", "portrait", "landscape"]) +
      slider("display.name_size_sp", "Name size", 20, 120, 2) + sw("display.countdown", "Show received/expires time") +
      slider("display.message_size_min_sp", "Message size min", 8, 200, 2) +
      slider("display.message_size_max_sp", "Message size max", 8, 200, 2) +
      color("display.name_color", "Name colour") + sw("display.per_sender_colors", "Colour names per sender", "a distinct colour per sender; off = the one colour above") +
      color("display.message_color", "Message colour") +
      color("display.warn_color", "Warning colour") + color("display.countdown_color", "Countdown colour") +
      slider("display.idle_clock_scale", "Idle clock scale", 1, 3, 0.1) +
      seg("display.idle_date_format", "Idle date format", ["auto", "dmy", "mdy", "ymd"]) +
      textField("display.warn_text", "Warning text", "default (localized)");
    el("sec-cue").innerHTML = slider("cue.glow_ms", "Glow", 0, 20000, 500, "ms") +
      sw("cue.chime", "Sound") + sw("cue.vibrate", "Vibration") + sw("cue.torch.enabled", "Torch") +
      slider("cue.torch.on_ms", "On", 200, 5000, 100, "ms") +
      slider("cue.torch.off_ms", "Off", 200, 5000, 100, "ms") +
      stepper("cue.torch.pulses", "Pulses", 1, 10, 1);
    el("sec-defaults").innerHTML = stepper("defaults.duration_minutes", "Default duration", 5, 1440, 30, "h");
    el("sec-telemetry").innerHTML = stepper("telemetry.interval_minutes", "Status interval (min)", 1, 1440, 5);
    el("sec-power").innerHTML = sw("power.screen_off_when_idle", "Screen off when idle", "sleep the display; wake on a new message");
    el("sec-kiosk").innerHTML = sw("kiosk.locked", "Locked (kiosk)", "off = open the launcher");
    el("sec-device_owner").innerHTML =
      sw("device_owner.lock_task", "Lock task", "configure full kiosk lock task") +
      sw("device_owner.status_bar_disabled", "Hide status bar") +
      sw("device_owner.restrict_accounts", "Block account changes") +
      sw("device_owner.restrict_bluetooth", "Disable Bluetooth") +
      sw("device_owner.restrict_location", "Block location") +
      sw("device_owner.restrict_printing", "Block printing") +
      sw("device_owner.restrict_sync", "Disable auto-sync") +
      sw("device_owner.restrict_mobile_networks", "Block mobile-network config") +
      sw("device_owner.restrict_tethering", "Block tethering") +
      sw("device_owner.restrict_roaming", "Block data roaming");
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
  function renderDevice(s) {
    if (!s) { el("devStatus").textContent = "—"; return; }
    var seen = s.last_seen ? new Date(s.last_seen).getTime() : 0;
    var age = Math.max(0, Math.round((Date.now() - seen) / 1000));
    var online = s.state !== "offline" && age < staleAfterSeconds(s);
    var parts = [];
    parts.push('<span class="pill ' + (online ? "on" : "off") + '">' + (online ? "connected" : "offline") + '</span>');
    parts.push("<span>battery " + s.battery + "%" + (typeof s.battery_temp_c === "number" ? " · " + s.battery_temp_c.toFixed(1) + "°C" : "") + "</span>");
    parts.push("<span>" + (s.charging ? "charging" : "on battery") + "</span>");
    parts.push("<span>wifi " + s.wifi + "</span>");
    parts.push("<span>mqtt " + s.mqtt + "</span>");
    if (typeof s.device_owner === "boolean") parts.push("<span>device owner " + s.device_owner + "</span>");
    if (s.sender) parts.push("<span>message: " + s.sender + "</span>");
    parts.push("<span>seen " + age + "s ago</span>");
    el("devStatus").innerHTML = parts.join('<span class="sep">·</span>');
  }

  function connect() {
    var token = el("adminToken").value.trim() || sessionStorage.getItem("gp_admin") || "";
    if (!token) { el("unlockHint").textContent = "Enter the token."; return; }
    sessionStorage.setItem("gp_admin", token);
    lastAttempt = Date.now();
    if (client) { try { client.end(true); } catch (e) {} }
    el("unlockHint").textContent = "Connecting…";
    client = mqtt.connect(BROKER, { username: token, clientId: "grandpa-admin-" + Math.random().toString(16).slice(2), clean: true, keepalive: 30, reconnectPeriod: 5000, connectTimeout: 12000, protocolVersion: 4 });
    client.on("connect", function () {
      el("unlock").hidden = true; el("admin").hidden = false;
      el("connPill").textContent = "connected"; el("connPill").className = "pill on";
      client.subscribe([TOPIC.status, TOPIC.config], { qos: 1 });
    });
    client.on("error", function (e) { el("unlockHint").textContent = "Error: " + e.message; });
    client.on("close", function () { el("connPill").textContent = "disconnected"; el("connPill").className = "pill off"; });
    client.on("message", function (topic, payload) {
      if (topic !== TOPIC.status) return;
      var s; try { s = JSON.parse(payload.toString()); } catch (e) { return; }
      renderDevice(s);
      if (s.connection) renderConnection(s.connection);
      if (s.command && s.command.last) el("actionHint").textContent = s.command.last + ": " + (s.command.result || "");
      if (!dirty && s.config) { cfg = JSON.parse(JSON.stringify(s.config)); cfg.schema = cfg.schema || 1; build(); el("applied").textContent = "current config loaded from the phone"; }
    });
  }

  /* Auto-connect (same rationale as the sender): heal a dropped or
     never-established link when the tab is resumed, the network returns, or the
     page is restored from the bfcache — without a manual refresh. Idempotent. */
  function ensureConnected() {
    if (!sessionStorage.getItem("gp_admin")) return;
    if (!client) { connect(); return; }
    if (client.connected || client.reconnecting) return;
    if (Date.now() - lastAttempt < 13000) return;
    try { client.reconnect(); } catch (e) { connect(); }
  }

  function apply() {
    if (!client || !client.connected) { el("applied").textContent = "You are not connected."; return; }
    client.publish(TOPIC.config, JSON.stringify(cfg), { qos: 1, retain: true });
    dirty = false;
    el("applied").textContent = "Sent — the phone will apply it and confirm in status.";
  }
  function reset() { cfg = JSON.parse(JSON.stringify(DEF)); dirty = true; build(); }

  /* ---- guarded connection change + device actions (grandpa/command) ---- */
  function renderConnection(c) {
    el("connState").textContent =
      "state: " + c.state + " · " + c.host + ":" + c.port + (c.use_tls ? " tls" : "") +
      " · password " + (c.password_set ? "set" : "not set") +
      (c.last_error ? " · " + c.last_error : "");
    if (!el("cHost").value) el("cHost").value = c.host;
    if (!el("cPort").value) el("cPort").value = c.port;
    el("cTls").checked = !!c.use_tls;
    // The username is intentionally never echoed back (for Flespi it *is* the
    // token), so a blank field always means "keep the current value".
  }
  function applyConn() {
    if (!client || !client.connected) { el("connState").textContent = "You are not connected."; return; }
    var host = el("cHost").value.trim();
    if (!host) { el("connState").textContent = "Enter a host."; return; }
    var revert = parseInt(el("cRevert").value, 10) || 90;
    if (!confirm("Change the receiver's broker?\n\nIt will test the new connection and revert automatically within " + revert + "s if it cannot connect. Continue?")) return;
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
    el("connState").textContent = "Sent — the phone will test it and commit or revert within " + revert + "s.";
  }
  function sendAction(action, label) {
    if (!client || !client.connected) return;
    if (!confirm(label + "?")) return;
    client.publish(TOPIC.command, JSON.stringify({ action: action }), { qos: 1, retain: false });
    el("actionHint").textContent = action + " requested.";
  }

  function showForm() { el("unlock").hidden = true; el("admin").hidden = false; build(); }

  /* Invitation link: admin.html#token=<admin-token> (fragment preferred — never
     sent to the server nor leaked in Referer; ?token= also works). Parsing and
     stripping are shared with the sender (common.js). */
  function readInvite() {
    var inv = GP.readInvite({ token: ["token", "t"] }, "token");
    return inv ? inv.token : "";
  }
  function clearInviteParams() { GP.clearUrlParams(["token", "t"]); }

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
    wireControls();
    if (q.get("demo") === "1") { showForm(); el("applied").textContent = "demo mode (no broker)"; }
    el("unlockBtn").addEventListener("click", connect);
    el("apply").addEventListener("click", apply);
    el("reset").addEventListener("click", reset);
    el("applyConn").addEventListener("click", applyConn);
    el("restartApp").addEventListener("click", function () { sendAction("restart_app", "Restart the receiver app"); });
    el("rebootDevice").addEventListener("click", function () { sendAction("reboot_device", "Reboot the phone (it will be offline for ~a minute)"); });
    window.addEventListener("online", ensureConnected);
    window.addEventListener("focus", ensureConnected);
    window.addEventListener("pageshow", ensureConnected);
    document.addEventListener("visibilitychange", function () { if (!document.hidden) ensureConnected(); });
    setInterval(ensureConnected, 15000);
    var saved = sessionStorage.getItem("gp_admin");
    var invite = readInvite();
    if (invite) { sessionStorage.setItem("gp_admin", invite); clearInviteParams(); saved = invite; }
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
