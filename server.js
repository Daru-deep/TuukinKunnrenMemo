/**
 * 通勤記録アプリの起動スクリプト
 *
 * 起動: node server.js
 * 環境変数:
 *   PORT      待ち受けポート（既定 8080）
 *   HOST      待ち受けアドレス（既定 0.0.0.0 = 同じLANのスマホから見える）
 *   DATA_DIR  データの保存先（既定 ./data）
 *   TLS_KEY / TLS_CERT  指定するとHTTPSで起動する
 *
 * なぜHTTPSが要るのか:
 *   ブラウザは Geolocation API と Service Worker を
 *   「安全なコンテキスト（https:// か localhost）」でしか許可しない。
 *   スマホから http://192.168.x.x:8080 で開くとGPSが使えないので、
 *   自己署名証明書でよいのでHTTPSで動かすのが確実（READMEに手順あり）。
 *
 * 注意: 認証機能は持っていない。インターネットに直接公開せず、
 *      自宅LANかVPN(Tailscale等)の内側だけで使うこと。
 */

import fs from 'node:fs';
import https from 'node:https';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { createApp } from './lib/app.js';
import { Store } from './lib/store.js';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT ?? 8080);
const HOST = process.env.HOST ?? '0.0.0.0';
const DATA_DIR = process.env.DATA_DIR ?? path.join(ROOT, 'data');
const TLS_KEY = process.env.TLS_KEY;
const TLS_CERT = process.env.TLS_CERT;

const store = await new Store(DATA_DIR).init();
const app = createApp(store);

// TLSの鍵と証明書が指定されていればHTTPSで、無ければHTTPで待ち受ける
const useTls = Boolean(TLS_KEY && TLS_CERT);
const server = useTls
  ? https.createServer(
      { key: fs.readFileSync(TLS_KEY), cert: fs.readFileSync(TLS_CERT) },
      // createApp が作った http.Server のリクエスト処理だけを borrow する
      (req, res) => app.emit('request', req, res),
    )
  : app;

server.listen(PORT, HOST, () => {
  console.log(`通勤記録アプリを起動しました: ${useTls ? 'https' : 'http'}://${HOST}:${PORT}`);
  console.log(`データの保存先: ${DATA_DIR}`);
  if (!useTls) {
    console.log('※ HTTPで起動中です。スマホからGPSを使うにはHTTPSが必要です（README参照）');
  }
});

// systemdやCtrl-Cで止めるときに、処理中のリクエストを終わらせてから落とす
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log(`\n${signal} を受け取りました。終了します。`);
    server.close(() => process.exit(0));
  });
}
