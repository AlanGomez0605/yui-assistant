// ==============================================================================
// PROYECTO YUI - SERVICE WORKER PARA ACCESO OFFLINE & PWA EN ANDROID
// ==============================================================================

const CACHE_NAME = 'yui-cache-v2';
const ASSETS_TO_CACHE = [
  '/',
  '/styles/main.css',
  '/scripts/app.js',
  '/scripts/sao_audio.js',
  '/manifest.json',
  '/assets/yui_avatar.jpg'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(ASSETS_TO_CACHE);
    })
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) {
            return caches.delete(key);
          }
        })
      );
    })
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  // Las peticiones a la API siempre van a la red
  if (event.request.url.includes('/api/')) {
    return;
  }

  event.respondWith(
    caches.match(event.request).then(async (cachedResponse) => {
      if (cachedResponse) return cachedResponse;
      try {
        return await fetch(event.request);
      } catch (error) {
        if (event.request.mode === 'navigate') {
          return caches.match('/');
        }
        throw error;
      }
    })
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windowClients) => {
      const existing = windowClients.find((client) => 'focus' in client);
      return existing ? existing.focus() : clients.openWindow('/');
    })
  );
});
