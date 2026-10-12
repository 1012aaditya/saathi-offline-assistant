/* Offline cache: after one load with internet, walk mode works with none.
   Own code: network first (so edits show up). Model + pinned libraries: cache first (big, never change). */

const CACHE = "saathi-walk-v2";
const OWN = ["./", "./index.html", "./walk.css", "./app.js", "./core.js", "./detector.js"];
const LIB_HOSTS = ["cdn.jsdelivr.net"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(OWN)));
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

async function save(req, res) {
  if (res.ok || res.type === "opaque") (await caches.open(CACHE)).put(req, res.clone());
  return res;
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  const pinned = LIB_HOSTS.includes(url.hostname) || (url.origin === location.origin && url.pathname.includes("/models/"));
  if (pinned) {
    e.respondWith(caches.match(req).then((hit) => hit ?? fetch(req).then((res) => save(req, res))));
  } else if (url.origin === location.origin) {
    e.respondWith(fetch(req).then((res) => save(req, res)).catch(() => caches.match(req)));
  }
});
