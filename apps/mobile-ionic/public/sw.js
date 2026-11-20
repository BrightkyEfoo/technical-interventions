const prefix = 'serviceops-mobile-';
let shell;

async function currentCache() {
  if (shell) return shell;
  const known = (await caches.keys()).find(key => key.startsWith(prefix));
  return known ? caches.open(known) : null;
}

self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const response = await fetch('/offline-assets.json', { cache: 'no-store' });
    const manifest = await response.json();
    shell = await caches.open(prefix + manifest.version);
    await shell.addAll(manifest.assets);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const response = await (await currentCache()).match('/index.html');
    if (!response) throw new Error('Application shell missing');
    const keys = (await caches.keys()).filter(key => key.startsWith(prefix));
    await Promise.all(keys.slice(0, -1).map(key => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin ||
      url.pathname.startsWith('/api/') || url.pathname.startsWith('/backend/')) return;
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(async () => (await currentCache())?.match('/index.html')));
  } else {
    event.respondWith((async () => (await (await currentCache())?.match(request)) || fetch(request))());
  }
});
