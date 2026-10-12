/* Offline cache: after one load with internet, walk mode works with none.
   Own code: network first (so edits show up). Model + pinned libraries: cache first (big, never change). */

const CACHE = "saathi-walk-v5";
const OWN = ["./", "./index.html", "./walk.css", "./app.js", "./core.js", "./detector.js"];
const LIB_HOSTS = ["cdn.jsdelivr.net"];

// Cross-origin isolation (needed for multi-threaded WASM) on any static host, which can't set
// headers itself: the worker adds them to our own responses. Page reloads once to pick it up.
function isolate(res) {
  if (!res || res.status === 0 || res.type === "opaque") return res;
  const headers = new Headers(res.headers);
  headers.set("Cross-Origin-Opener-Policy", "same-origin");
  // require-corp (not credentialless) so iPhone Safari isolates too; everything is same-origin.
  headers.set("Cross-Origin-Embedder-Policy", "require-corp");
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
}

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
  const own = url.origin === location.origin;
  const pinned = LIB_HOSTS.includes(url.hostname) || (own && /\/(models|vendor)\//.test(url.pathname));
  if (pinned) {
    const r = caches.match(req).then((hit) => hit ?? fetch(req).then((res) => save(req, res)));
    e.respondWith(own ? r.then(isolate) : r);
  } else if (own) {
    e.respondWith(fetch(req).then((res) => save(req, res)).catch(() => caches.match(req)).then(isolate));
  }
});
