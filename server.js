/**
 * 通勤記録アプリの起動スクリプト
 *
 * 起動: node server.js
 * 環境変数:
 *   PORT      待ち受けポート（既定 8080）
 *   HOST      待ち受けアドレス（既定 0.0.0.0 = 同じLANのスマホから見える）
 *   DATA_DIR  データの保存先（既定 ./data）
 *
 * 注意: 認証機能は持っていない。インターネットに直接公開せず、
 *      自宅LANかVPN(Tailscale等)の内側だけで使うこと。README参照。
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { createApp } from './lib/app.js';
import { Store } from './lib/store.js';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT ?? 8080);
const HOST = process.env.HOST ?? '0.0.0.0';
const DATA_DIR = process.env.DATA_DIR ?? path.join(ROOT, 'data');

const store = await new Store(DATA_DIR).init();
const server = createApp(store);

server.listen(PORT, HOST, () => {
  console.log(`通勤記録アプリを起動しました: http://${HOST}:${PORT}`);
  console.log(`データの保存先: ${DATA_DIR}`);
});

// systemdやCtrl-Cで止めるときに、処理中のリクエストを終わらせてから落とす
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log(`\n${signal} を受け取りました。終了します。`);
    server.close(() => process.exit(0));
  });
}
