import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { createApp } from '../lib/app.js';
import { Store } from '../lib/store.js';

/** 一時ディレクトリを保存先にしたサーバを立ち上げる（本番データを汚さない） */
async function startServer() {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'tuukin-test-'));
  const store = await new Store(dataDir).init();
  const server = createApp(store);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;

  return {
    base,
    async call(method, url, body) {
      const res = await fetch(base + url, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      });
      const isJson = res.headers.get('content-type')?.includes('application/json');
      return { status: res.status, res, data: isJson ? await res.json() : null };
    },
    async close() {
      await new Promise((resolve) => server.close(resolve));
      await fs.rm(dataDir, { recursive: true, force: true });
    },
  };
}

const sample = {
  direction: 'outbound',
  date: '2026-08-21',
  departureTime: '07:45',
  trainTime: '07:58',
  crowding: 'crowded',
  delayed: false,
  arrivalTime: '08:25',
  detour: false,
  note: '初回',
};

test('記録のCRUDとxlsx出力が一通り動く', async (t) => {
  const app = await startServer();
  t.after(() => app.close());

  // 最初は空
  const empty = await app.call('GET', '/api/records');
  assert.equal(empty.status, 200);
  assert.deepEqual(empty.data.records, []);

  // 作成（GPS軌跡つき）
  const created = await app.call('POST', '/api/records', {
    ...sample,
    track: [
      { lat: 35.7295, lng: 139.7109, t: 1, acc: 12 },
      { lat: 35.7305, lng: 139.7109, t: 2, acc: 9 },
    ],
  });
  assert.equal(created.status, 201);
  const { id } = created.data.record;
  assert.equal(created.data.record.trackPoints, 2);
  assert.ok(created.data.record.trackDistanceKm > 0);

  // 一覧には軌跡の中身は含まれない（一覧を軽く保つ設計）
  const list = await app.call('GET', '/api/records');
  assert.equal(list.data.records.length, 1);
  assert.equal(list.data.records[0].track, undefined);

  // 1件取得では軌跡が付いてくる
  const detail = await app.call('GET', `/api/records/${id}`);
  assert.equal(detail.data.track.length, 2);

  // 更新（trackを送らなければ軌跡は残る）
  const updated = await app.call('PUT', `/api/records/${id}`, { ...sample, arrivalTime: '09:05' });
  assert.equal(updated.status, 200);
  assert.equal(updated.data.record.arrivalTime, '09:05');
  assert.equal(updated.data.record.trackPoints, 2, '軌跡が消えていない');

  // xlsxが落とせる
  const xlsx = await fetch(`${app.base}/api/export.xlsx`);
  assert.equal(xlsx.status, 200);
  assert.match(xlsx.headers.get('content-type'), /spreadsheetml\.sheet/);
  assert.match(xlsx.headers.get('content-disposition'), /filename\*=UTF-8''/);
  const buffer = Buffer.from(await xlsx.arrayBuffer());
  assert.equal(buffer.subarray(0, 2).toString(), 'PK');

  // 削除
  assert.equal((await app.call('DELETE', `/api/records/${id}`)).status, 200);
  assert.equal((await app.call('GET', '/api/records')).data.records.length, 0);
  assert.equal((await app.call('GET', `/api/records/${id}`)).status, 404);
});

test('不正な入力は400で弾き、理由を返す', async (t) => {
  const app = await startServer();
  t.after(() => app.close());

  const bad = await app.call('POST', '/api/records', { direction: 'outbound', date: '' });
  assert.equal(bad.status, 400);
  assert.ok(bad.data.errors.length >= 2);

  const badId = await app.call('GET', '/api/records/..%2F..%2Fetc%2Fpasswd');
  assert.equal(badId.status, 400, 'idの形式チェックで弾く');
});

test('静的ファイルはpublic/とshared/だけを配る', async (t) => {
  const app = await startServer();
  t.after(() => app.close());

  const index = await fetch(`${app.base}/`);
  assert.equal(index.status, 200);
  assert.match(index.headers.get('content-type'), /text\/html/);

  const shared = await fetch(`${app.base}/shared/commute.js`);
  assert.equal(shared.status, 200, 'ブラウザから共有ロジックを読める');

  // ディレクトリの外に出ようとするリクエストは404
  for (const attempt of ['/../package.json', '/..%2Fpackage.json', '/shared/../package.json']) {
    const res = await fetch(app.base + attempt);
    assert.equal(res.status, 404, `${attempt} が漏れている`);
  }
});

test('記録が増えても一覧は日付順に並ぶ', async (t) => {
  const app = await startServer();
  t.after(() => app.close());

  for (const date of ['2026-09-01', '2026-08-15', '2026-08-31']) {
    await app.call('POST', '/api/records', { ...sample, date });
  }
  const { data } = await app.call('GET', '/api/records');
  assert.deepEqual(
    data.records.map((r) => r.date),
    ['2026-08-15', '2026-08-31', '2026-09-01'],
  );

  const filtered = await app.call('GET', '/api/records?direction=inbound');
  assert.deepEqual(filtered.data.records, []);
});
