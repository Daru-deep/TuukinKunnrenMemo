/**
 * 動作確認用のサンプル記録を作るスクリプト
 *
 *   node scripts/seed.js 12          # 12日ぶん（行き・帰り各12件）作る
 *   DATA_DIR=./data-demo node scripts/seed.js
 *
 * 集計画面や色分けを、実際に20回通わなくても確認するためのもの。
 * 座標は東京駅周辺のダミーで、実在の自宅・職場とは無関係。
 *
 * 注意: 実データの入った data/ に対して実行すると混ざるので、
 *      DATA_DIR を分けて使うこと。
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { Store } from '../lib/store.js';
import { formatTime } from '../shared/commute.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DATA_DIR = process.env.DATA_DIR ?? path.join(ROOT, 'data');
const days = Number(process.argv[2] ?? 10);

const CROWDING = ['empty', 'normal', 'crowded', 'full'];
const pick = (list) => list[Math.floor(Math.random() * list.length)];
const jitter = (n) => Math.round((Math.random() - 0.5) * 2 * n);

/** 平日だけを新しい順にさかのぼって集める */
function weekdays(count) {
  const dates = [];
  const cursor = new Date();
  while (dates.length < count) {
    cursor.setDate(cursor.getDate() - 1);
    const day = cursor.getDay();
    if (day !== 0 && day !== 6) dates.push(cursor.toISOString().slice(0, 10));
  }
  return dates.reverse();
}

/** ダミーの軌跡（東京駅付近をまっすぐ north-east に進む） */
function fakeTrack(points = 20) {
  const start = { lat: 35.6812 + Math.random() * 0.002, lng: 139.7671 + Math.random() * 0.002 };
  const now = Date.now();
  return Array.from({ length: points }, (_, i) => ({
    lat: Number((start.lat + i * 0.0004).toFixed(6)),
    lng: Number((start.lng + i * 0.0003).toFixed(6)),
    t: now + i * 30000,
    acc: 8 + Math.floor(Math.random() * 10),
  }));
}

const store = await new Store(DATA_DIR).init();

for (const date of weekdays(days)) {
  // 行き: 出発 7:40前後、所要 45〜75分（判定が3色とも出るようにばらけさせる）
  const departure = 7 * 60 + 40 + jitter(15);
  const duration = 45 + Math.floor(Math.random() * 31);
  const delayed = Math.random() < 0.25;
  await store.createRecord(
    {
      direction: 'outbound',
      date,
      departureTime: formatTime(departure),
      trainTime: formatTime(departure + 10 + jitter(3)),
      crowding: pick(CROWDING),
      delayed,
      arrivalTime: formatTime(departure + duration + (delayed ? 12 : 0)),
      detour: false,
      note: 'サンプルデータ',
    },
    fakeTrack(),
  );

  // 帰り: 17:30前後
  const back = 17 * 60 + 30 + jitter(20);
  const detour = Math.random() < 0.3;
  await store.createRecord(
    {
      direction: 'inbound',
      date,
      departureTime: formatTime(back),
      trainTime: formatTime(back + 8),
      crowding: pick(CROWDING),
      delayed: Math.random() < 0.2,
      arrivalTime: formatTime(back + 50 + Math.floor(Math.random() * 25)),
      detour,
      detourNote: detour ? '本屋に寄った' : '',
      note: 'サンプルデータ',
    },
    fakeTrack(15),
  );
}

console.log(`${days}日ぶんのサンプル記録を作りました: ${DATA_DIR}`);
