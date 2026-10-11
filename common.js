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

  root.GP = GP;
})(window);
