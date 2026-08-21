import test from 'node:test';
import assert from 'node:assert/strict';
import zlib from 'node:zlib';

import { buildRecordsXlsx, buildXlsx, columnLetter } from '../lib/xlsx.js';
import { crc32 } from '../lib/zip.js';

/**
 * テスト用の最小ZIPリーダー。
 * 末尾のEnd of Central Directoryから目次を辿り、各ファイルを取り出す。
 * 自作ZIPが「本当に規格どおりか」をライブラリなしで確認するために使う。
 */
function readZip(buffer) {
  const eocd = buffer.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  assert.notEqual(eocd, -1, 'EOCDが見つからない');
  const count = buffer.readUInt16LE(eocd + 10);
  let pointer = buffer.readUInt32LE(eocd + 16);

  const files = new Map();
  for (let i = 0; i < count; i += 1) {
    assert.equal(buffer.readUInt32LE(pointer), 0x02014b50, 'セントラルディレクトリの署名が不正');
    const crc = buffer.readUInt32LE(pointer + 16);
    const compressedSize = buffer.readUInt32LE(pointer + 20);
    const nameLength = buffer.readUInt16LE(pointer + 28);
    const extraLength = buffer.readUInt16LE(pointer + 30);
    const commentLength = buffer.readUInt16LE(pointer + 32);
    const localOffset = buffer.readUInt32LE(pointer + 42);
    const name = buffer.toString('utf8', pointer + 46, pointer + 46 + nameLength);

    assert.equal(buffer.readUInt32LE(localOffset), 0x04034b50, 'ローカルヘッダの署名が不正');
    const localNameLength = buffer.readUInt16LE(localOffset + 26);
    const localExtraLength = buffer.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const content = zlib.inflateRawSync(buffer.subarray(dataStart, dataStart + compressedSize));
    assert.equal(crc32(content), crc, `${name} のCRCが一致しない`);

    files.set(name, content.toString('utf8'));
    pointer += 46 + nameLength + extraLength + commentLength;
  }
  return files;
}

test('columnLetter', () => {
  assert.equal(columnLetter(0), 'A');
  assert.equal(columnLetter(25), 'Z');
  assert.equal(columnLetter(26), 'AA');
  assert.equal(columnLetter(27), 'AB');
});

test('xlsxはZIPとして読み戻せて、必要なパートが揃っている', () => {
  const buffer = buildXlsx({
    sheetName: 'テスト',
    columns: [{ label: 'A', type: 'string' }, { label: 'B', type: 'number' }],
    rows: [{ values: ['あ', 1], fill: null }],
  });
  assert.equal(buffer.subarray(0, 2).toString(), 'PK', 'ZIPのマジックナンバー');

  const files = readZip(buffer);
  for (const name of [
    '[Content_Types].xml',
    '_rels/.rels',
    'xl/workbook.xml',
    'xl/_rels/workbook.xml.rels',
    'xl/styles.xml',
    'xl/worksheets/sheet1.xml',
  ]) {
    assert.ok(files.has(name), `${name} が無い`);
  }
  assert.match(files.get('xl/workbook.xml'), /name="テスト"/);
});

const records = [
  {
    id: 'a', direction: 'outbound', date: '2026-08-03', departureTime: '07:45',
    trainTime: '07:58', arrivalTime: '08:20', crowding: 'full', delayed: false,
    detour: false, note: 'a & b <c>', trackPoints: 120, trackDistanceKm: 3.456,
  },
  {
    id: 'b', direction: 'outbound', date: '2026-08-04', departureTime: '07:45',
    arrivalTime: '08:40', crowding: 'normal', delayed: true, detour: true, detourNote: 'コンビニ',
  },
  {
    id: 'c', direction: 'outbound', date: '2026-08-05', departureTime: '07:45',
    arrivalTime: '09:10', crowding: 'crowded', delayed: true, detour: false,
  },
  {
    id: 'd', direction: 'inbound', date: '2026-08-05', departureTime: '23:50',
    arrivalTime: '00:35', crowding: 'empty', delayed: false, detour: false,
  },
];

test('記録シート: 見出し・行数・所要時間・判定色', () => {
  const sheet = readZip(buildRecordsXlsx(records)).get('xl/worksheets/sheet1.xml');

  assert.match(sheet, /<t xml:space="preserve">日付<\/t>/, '見出し行がある');
  assert.equal((sheet.match(/<row /g) ?? []).length, records.length + 1, '見出し＋データ行');

  // 所要時間は数値セル（Excelで集計できるように文字列にしない）
  assert.match(sheet, /<v>35<\/v>/);
  assert.match(sheet, /<v>45<\/v>/, '日をまたぐ帰りも45分として出る');

  // XMLエスケープ
  assert.match(sheet, /a &amp; b &lt;c&gt;/);

  // 判定の3色ぶんのスタイルが行に割り当たっている（見出し=1, 以降=2..4）
  const styles = [...sheet.matchAll(/<row r="(\d+)">.*?s="(\d+)"/g)].map((m) => m[2]);
  assert.deepEqual(styles, ['1', '2', '3', '4'], '見出し/オンタイム/注意/遅刻');

  // 帰り（判定なし）の行にはスタイル指定が付かない＝色が塗られない
  const lastRow = /<row r="5">.*?<\/row>/s.exec(sheet)[0];
  assert.doesNotMatch(lastRow, /s="\d+"/, '帰りの行は色分けしない');
});

test('記録シート: GPS座標そのものは出力しない（個人情報を含むファイルを配らないため）', () => {
  const withTrack = [{ ...records[0], track: [{ lat: 35.681, lng: 139.767 }] }];
  const sheet = readZip(buildRecordsXlsx(withTrack)).get('xl/worksheets/sheet1.xml');
  assert.doesNotMatch(sheet, /35\.68/);
  assert.doesNotMatch(sheet, /139\.76/);
  assert.match(sheet, /<v>120<\/v>/, '点数は出す');
});

test('記録ゼロでもxlsxは壊れない', () => {
  const files = readZip(buildRecordsXlsx([]));
  assert.match(files.get('xl/worksheets/sheet1.xml'), /<row r="1">/);
});
