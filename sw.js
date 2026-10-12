/* Grandpa Pager — service worker (enables PWA install + basic offline shell). */
var CACHE = "grandpa-pager-v21";
var ASSETS = [
  "./", "./index.html", "./sender.js", "./admin.html", "./admin.js", "./common.js", "./mqtt.min.js",
  "./qrcode-generator.js",
  "./manifest.webmanifest", "./admin.webmanifest",
  "./icon-192.png", "./icon-512.png", "./icon-maskable-512.png"
];

/* Never cache under a URL that carries a query string: an invitation link
   (?token=… or #token=…) would otherwise be persisted in Cache Storage. Strip
   the search/hash so same-origin assets share one clean cache key. */
function cacheKey(url) {
  var u = new URL(url);
  if (u.origin === self.location.origin) { u.search = ""; u.hash = ""; }
  return u.href;
}

self.addEventListener("install", function (e) {
  e.waitUntil(
    caches.open(CACHE).then(function (c) {
      // Cache each asset independently; a single failure must not abort install.
      return Promise.all(ASSETS.map(function (u) { return c.add(u).catch(function () {}); }));
    }).then(function () { return self.skipWaiting(); })
  );
});

self.addEventListener("activate", function (e) {
  e.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener("fetch", function (e) {
  if (e.request.method !== "GET") return;
  e.respondWith(
    fetch(e.request).then(function (resp) {
      if (e.request.url.indexOf(self.location.origin) === 0) {
        var copy = resp.clone();
        var key = cacheKey(e.request.url);
        caches.open(CACHE).then(function (c) { c.put(key, copy); });
      }
      return resp;
    }).catch(function () {
      return caches.match(cacheKey(e.request.url)).then(function (r) {
        return r || (e.request.mode === "navigate" ? caches.match("./index.html") : undefined);
      });
    })
  );
});
