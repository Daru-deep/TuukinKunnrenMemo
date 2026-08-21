/**
 * Service Worker（オフライン対応・任意機能）
 *
 * 方針:
 * - 画面を作るファイル（HTML/CSS/JS/Leaflet）はキャッシュから先に返す
 * - /api/ への通信は必ずネットワークへ（古い記録を表示しないため）
 * - 地図タイルはキャッシュしない（OSMの利用規約と容量の都合）
 *
 * ファイルを更新したら CACHE_NAME の版数を上げること。
 */

const CACHE_NAME = 'commute-app-v1';

const APP_SHELL = [
  '/',
  '/index.html',
  '/css/app.css',
  '/js/main.js',
  '/js/form.js',
  '/js/summary.js',
  '/js/api.js',
  '/js/gps.js',
  '/js/map.js',
  '/shared/commute.js',
  '/vendor/leaflet/leaflet.js',
  '/vendor/leaflet/leaflet.css',
  '/manifest.webmanifest',
  '/icons/icon.svg',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET') return;
  if (url.origin !== self.location.origin) return; // 地図タイルなど外部は素通し
  if (url.pathname.startsWith('/api/')) return; // APIは常に最新を取りに行く

  event.respondWith(
    caches.match(event.request).then((cached) => {
      const fromNetwork = fetch(event.request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy));
          }
          return response;
        })
        .catch(() => cached);
      // キャッシュがあれば即返しつつ、裏で新しいものを取得しておく
      return cached ?? fromNetwork;
    }),
  );
});
