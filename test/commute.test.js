import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseTime,
  formatTime,
  durationMinutes,
  formatDuration,
  formatDateShort,
  judge,
  validateRecord,
  summarize,
  toExportRow,
  trackDistanceMeters,
} from '../shared/commute.js';

test('parseTime / formatTime', () => {
  assert.equal(parseTime('07:45'), 465);
  assert.equal(parseTime('00:00'), 0);
  assert.equal(parseTime('23:59'), 1439);
  assert.equal(parseTime('24:00'), null, '24時は不正');
  assert.equal(parseTime('7:45'), null, 'ゼロ埋めなしは不正');
  assert.equal(parseTime(''), null);
  assert.equal(formatTime(465), '07:45');
  assert.equal(formatTime(1440), '00:00');
});

test('所要時間は日をまたいでも正しく出る', () => {
  assert.equal(durationMinutes('07:45', '08:40'), 55);
  assert.equal(durationMinutes('23:50', '00:20'), 30, '深夜の帰り');
  assert.equal(durationMinutes('08:00', '08:00'), 0);
  assert.equal(durationMinutes('08:00', 'あ'), null);
});

test('formatDuration', () => {
  assert.equal(formatDuration(55), '55分');
  assert.equal(formatDuration(75), '1時間15分');
  assert.equal(formatDuration(120), '2時間00分');
  assert.equal(formatDuration(null), '—');
});

test('formatDateShort は曜日を付ける', () => {
  assert.equal(formatDateShort('2026-08-21'), '8/21(金)');
});

test('判定は行きの到着時刻だけに適用される（境界値）', () => {
  const at = (arrivalTime, direction = 'outbound') => judge({ direction, arrivalTime })?.value;
  assert.equal(at('08:29'), 'ontime');
  assert.equal(at('08:30'), 'warn', '8:30ちょうどは注意');
  assert.equal(at('08:59'), 'warn');
  assert.equal(at('09:00'), 'late', '9:00ちょうどは遅刻');
  assert.equal(at('12:00'), 'late');
  assert.equal(at('05:00'), 'ontime');
  assert.equal(judge({ direction: 'inbound', arrivalTime: '19:00' }), null, '帰りは判定しない');
  assert.equal(judge({ direction: 'outbound', arrivalTime: '' }), null);
});

test('validateRecord: 必須項目が欠けたら弾く', () => {
  const bad = validateRecord({ direction: 'outbound', date: '', departureTime: '07:45' });
  assert.equal(bad.ok, false);
  assert.equal(bad.value, null);
  assert.ok(bad.errors.some((e) => e.includes('日付')));
  assert.ok(bad.errors.some((e) => e.includes('到着時刻')));
});

test('validateRecord: 正常系は正規化された値を返す', () => {
  const { ok, value } = validateRecord({
    direction: 'outbound',
    date: '2026-08-21',
    departureTime: '07:45',
    trainTime: '07:58',
    crowding: 'crowded',
    delayed: 'true',
    arrivalTime: '08:40',
    detour: false,
    detourNote: '本当は捨てられる',
    note: '  余裕あり  ',
    id: 'クライアントが送ってきた余計な値',
  });
  assert.equal(ok, true);
  assert.equal(value.delayed, true);
  assert.equal(value.detourNote, '', '寄り道なしならメモは残さない');
  assert.equal(value.note, '余裕あり', '前後の空白は落とす');
  assert.equal(value.id, undefined, '許可した項目だけを通す');
});

test('validateRecord: 未知の混雑度は空に落とす', () => {
  const { value } = validateRecord({
    direction: 'inbound',
    date: '2026-08-21',
    departureTime: '18:00',
    arrivalTime: '19:00',
    crowding: 'ぎゅうぎゅう',
  });
  assert.equal(value.crowding, '');
});

const records = [
  { id: '1', direction: 'outbound', date: '2026-08-03', departureTime: '07:45', arrivalTime: '08:40' }, // 55分 / 注意
  { id: '2', direction: 'outbound', date: '2026-08-04', departureTime: '07:40', arrivalTime: '08:35' }, // 55分 / 注意
  { id: '3', direction: 'outbound', date: '2026-08-05', departureTime: '07:30', arrivalTime: '08:20' }, // 50分 / オンタイム
  { id: '4', direction: 'inbound', date: '2026-08-05', departureTime: '17:30', arrivalTime: '18:40' }, // 70
];

test('summarize: 区分ごとの平均・最短・判定内訳', () => {
  const s = summarize(records);
  assert.equal(s.total, 4);
  assert.equal(s.outbound.count, 3);
  assert.equal(Math.round(s.outbound.averageMinutes * 10) / 10, 53.3);
  assert.equal(s.outbound.fastest.minutes, 50);
  assert.equal(s.outbound.fastest.record.id, '3');
  assert.equal(s.outbound.slowest.minutes, 55);
  assert.deepEqual(s.outbound.judgementCounts, { ontime: 1, warn: 2, late: 0 });
  assert.equal(s.inbound.count, 1);
  assert.equal(s.inbound.averageMinutes, 70);
});

test('summarize: 記録ゼロでも落ちない', () => {
  const s = summarize([]);
  assert.equal(s.outbound.averageMinutes, null);
  assert.equal(s.outbound.fastest, null);
});

test('toExportRow', () => {
  const row = toExportRow({
    ...records[0],
    crowding: 'full',
    delayed: true,
    detour: false,
    trackPoints: 120,
    trackDistanceKm: 3.456,
  });
  assert.equal(row.direction, '行き');
  assert.equal(row.durationMinutes, 55);
  assert.equal(row.durationText, '55分');
  assert.equal(row.crowding, '満員');
  assert.equal(row.delayed, 'あり');
  assert.equal(row.detour, 'なし');
  assert.equal(row.judgement, '注意');
  assert.equal(row.trackDistanceKm, 3.46);
});

test('trackDistanceMeters: 東京駅付近の1kmはおおよそ1000m', () => {
  const points = [
    { lat: 35.681236, lng: 139.767125 },
    { lat: 35.690236, lng: 139.767125 },
  ];
  const d = trackDistanceMeters(points);
  assert.ok(d > 990 && d < 1010, `got ${d}`);
  assert.equal(trackDistanceMeters([]), 0);
  assert.equal(trackDistanceMeters([points[0]]), 0);
});
