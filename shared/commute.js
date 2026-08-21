/**
 * 通勤記録アプリの「ドメインロジック」
 *
 * このファイルはブラウザ（public/js/*）とサーバ（server.js / lib/xlsx.js）の
 * 両方から import される。所要時間の計算や判定を1か所に集約しておくことで、
 * 「画面の表示とxlsxの中身が食い違う」という事故を防いでいる。
 *
 * 依存なし・副作用なしの純粋な関数だけを置くこと（DOMやfsを触らない）。
 */

// ---------------------------------------------------------------------------
// 定数（選択肢やしきい値は「データ」として持つ。if文に埋め込まない）
// ---------------------------------------------------------------------------

/** 行き / 帰り */
export const DIRECTIONS = [
  { value: 'outbound', label: '行き', arrivalLabel: '職場ビル到着' },
  { value: 'inbound', label: '帰り', arrivalLabel: '最寄り駅ホーム到着' },
];

/** 混雑度（4段階） */
export const CROWDING_LEVELS = [
  { value: 'empty', label: '空いてる', score: 1 },
  { value: 'normal', label: '普通', score: 2 },
  { value: 'crowded', label: '混雑', score: 3 },
  { value: 'full', label: '満員', score: 4 },
];

/**
 * 到着時刻の判定ルール。
 * `before` 未満なら該当。最後の要素は before: null（それ以降すべて）。
 * しきい値を変えたくなったらこの配列だけ直せばよい（画面もxlsxも追随する）。
 */
export const JUDGEMENTS = [
  { value: 'ontime', label: 'オンタイム', before: '08:30', color: '#15803d', bg: '#dcfce7', argb: 'FFC6EFCE' },
  { value: 'warn',   label: '注意',       before: '09:00', color: '#a16207', bg: '#fef9c3', argb: 'FFFFEB9C' },
  { value: 'late',   label: '遅刻',       before: null,    color: '#b91c1c', bg: '#fee2e2', argb: 'FFFFC7CE' },
];

/** 判定を適用する区分（＝行きの「職場到着」だけ）。帰りは参考記録のみ。 */
export const JUDGED_DIRECTION = 'outbound';

const byValue = (list) => (value) => list.find((item) => item.value === value) || null;

export const directionOf = byValue(DIRECTIONS);
export const crowdingOf = byValue(CROWDING_LEVELS);
export const judgementOf = byValue(JUDGEMENTS);

export const directionLabel = (value) => directionOf(value)?.label ?? '';
export const crowdingLabel = (value) => crowdingOf(value)?.label ?? '';

// ---------------------------------------------------------------------------
// 時刻のユーティリティ
// ---------------------------------------------------------------------------

const TIME_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/** "07:45" -> 465（0時からの分数）。不正な値は null。 */
export function parseTime(value) {
  const m = TIME_RE.exec(String(value ?? '').trim());
  if (!m) return null;
  return Number(m[1]) * 60 + Number(m[2]);
}

/** 465 -> "07:45"（24時間を超えたら折り返す） */
export function formatTime(minutes) {
  if (!Number.isFinite(minutes)) return '';
  const m = ((Math.round(minutes) % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

/**
 * 出発〜到着の所要時間（分）。
 * 到着が出発より小さい場合は日付をまたいだ（例: 23:50 -> 00:20）とみなす。
 * 帰りが深夜になるケースを素直に扱うための仕様。
 */
export function durationMinutes(departureTime, arrivalTime) {
  const from = parseTime(departureTime);
  const to = parseTime(arrivalTime);
  if (from === null || to === null) return null;
  return (to - from + 1440) % 1440;
}

/** レコードから所要時間を出す薄いラッパ */
export const recordDuration = (record) =>
  record ? durationMinutes(record.departureTime, record.arrivalTime) : null;

/** 55 -> "55分" / 75 -> "1時間15分" */
export function formatDuration(minutes) {
  if (!Number.isFinite(minutes)) return '—';
  const total = Math.round(minutes);
  if (total < 60) return `${total}分`;
  return `${Math.floor(total / 60)}時間${String(total % 60).padStart(2, '0')}分`;
}

/** "2026-08-21" -> "8/21(金)" */
export function formatDateShort(date) {
  if (!DATE_RE.test(String(date ?? ''))) return String(date ?? '');
  const [y, m, d] = date.split('-').map(Number);
  const wd = '日月火水木金土'[new Date(y, m - 1, d).getDay()];
  return `${m}/${d}(${wd})`;
}

// ---------------------------------------------------------------------------
// 判定
// ---------------------------------------------------------------------------

/**
 * 到着時刻から判定を返す。判定対象外（＝帰り）の場合は null。
 * @returns {{value:string,label:string,color:string,bg:string,argb:string}|null}
 */
export function judge(record) {
  if (!record || record.direction !== JUDGED_DIRECTION) return null;
  const arrival = parseTime(record.arrivalTime);
  if (arrival === null) return null;
  return (
    JUDGEMENTS.find((rule) => rule.before === null || arrival < parseTime(rule.before)) ??
    JUDGEMENTS[JUDGEMENTS.length - 1]
  );
}

// ---------------------------------------------------------------------------
// 入力の検証と正規化
// ---------------------------------------------------------------------------

/**
 * フォーム／APIから来た値を検証し、保存できる形に整える。
 * サーバ側の入口でもブラウザ側の保存前でも同じ関数を通すので、
 * 「画面では通ったのにサーバで落ちる」というズレが起きない。
 *
 * @returns {{ok:boolean, errors:string[], value:object|null}}
 */
export function validateRecord(input) {
  const errors = [];
  const src = input ?? {};

  const direction = directionOf(src.direction) ? src.direction : null;
  if (!direction) errors.push('区分（行き／帰り）が不正です');

  const date = String(src.date ?? '').trim();
  if (!DATE_RE.test(date) || Number.isNaN(Date.parse(date))) errors.push('日付を入力してください');

  const departureTime = String(src.departureTime ?? '').trim();
  if (parseTime(departureTime) === null) errors.push('出発時刻を入力してください');

  const arrivalTime = String(src.arrivalTime ?? '').trim();
  if (parseTime(arrivalTime) === null) errors.push('到着時刻を入力してください');

  // 乗車電車の時刻は任意（電車に乗らない日もあるため）。入っているなら形式を確認。
  const trainTime = String(src.trainTime ?? '').trim();
  if (trainTime && parseTime(trainTime) === null) errors.push('乗車電車の時刻の形式が不正です');

  // 混雑度も任意（未選択のまま保存できる）
  const crowding = crowdingOf(src.crowding) ? src.crowding : '';

  const detour = Boolean(src.detour);
  const value = {
    direction,
    date,
    departureTime,
    trainTime,
    crowding,
    delayed: Boolean(src.delayed),
    arrivalTime,
    detour,
    detourNote: detour ? String(src.detourNote ?? '').trim() : '',
    note: String(src.note ?? '').trim(),
  };

  return { ok: errors.length === 0, errors, value: errors.length === 0 ? value : null };
}

// ---------------------------------------------------------------------------
// 集計
// ---------------------------------------------------------------------------

const average = (numbers) =>
  numbers.length === 0 ? null : numbers.reduce((a, b) => a + b, 0) / numbers.length;

/**
 * 区分ごとの集計。件数が増えても O(n) のまま（20回で打ち切る前提のコードは書かない）。
 */
export function summarizeDirection(records) {
  const withDuration = records
    .map((record) => ({ record, minutes: recordDuration(record) }))
    .filter((row) => Number.isFinite(row.minutes));

  const minutes = withDuration.map((row) => row.minutes);
  const fastest = withDuration.reduce(
    (best, row) => (best === null || row.minutes < best.minutes ? row : best),
    null,
  );
  const slowest = withDuration.reduce(
    (worst, row) => (worst === null || row.minutes > worst.minutes ? row : worst),
    null,
  );

  const judgementCounts = Object.fromEntries(JUDGEMENTS.map((j) => [j.value, 0]));
  for (const record of records) {
    const result = judge(record);
    if (result) judgementCounts[result.value] += 1;
  }

  return {
    count: records.length,
    averageMinutes: average(minutes),
    fastest: fastest && { record: fastest.record, minutes: fastest.minutes },
    slowest: slowest && { record: slowest.record, minutes: slowest.minutes },
    judgementCounts,
  };
}

/** 全レコードを区分ごとに集計する */
export function summarize(records) {
  const list = Array.isArray(records) ? records : [];
  const result = { total: list.length };
  for (const { value } of DIRECTIONS) {
    result[value] = summarizeDirection(list.filter((r) => r.direction === value));
  }
  return result;
}

/** 日付→出発時刻の昇順（同着はid）。一覧の時系列表示に使う。 */
export function compareRecords(a, b) {
  return (
    String(a.date).localeCompare(String(b.date)) ||
    (parseTime(a.departureTime) ?? 0) - (parseTime(b.departureTime) ?? 0) ||
    String(a.id).localeCompare(String(b.id))
  );
}

// ---------------------------------------------------------------------------
// 出力（xlsx / CSV 共通の列定義）
// ---------------------------------------------------------------------------

/** 出力する列。ここに1行足せばxlsxの列が増える。 */
export const EXPORT_COLUMNS = [
  { key: 'date', label: '日付', width: 12, type: 'string' },
  { key: 'direction', label: '区分', width: 8, type: 'string' },
  { key: 'departureTime', label: '出発時刻', width: 10, type: 'string' },
  { key: 'trainTime', label: '乗車電車', width: 10, type: 'string' },
  { key: 'arrivalTime', label: '到着時刻', width: 10, type: 'string' },
  { key: 'durationMinutes', label: '所要時間(分)', width: 12, type: 'number' },
  { key: 'durationText', label: '所要時間', width: 12, type: 'string' },
  { key: 'crowding', label: '混雑度', width: 10, type: 'string' },
  { key: 'delayed', label: '遅延', width: 8, type: 'string' },
  { key: 'detour', label: '寄り道', width: 8, type: 'string' },
  { key: 'detourNote', label: '寄り道メモ', width: 24, type: 'string' },
  { key: 'judgement', label: '判定', width: 12, type: 'string' },
  { key: 'trackPoints', label: 'GPS点数', width: 10, type: 'number' },
  { key: 'trackDistanceKm', label: 'GPS距離(km)', width: 12, type: 'number' },
  { key: 'note', label: 'メモ', width: 28, type: 'string' },
];

/** 1レコードを出力用の1行（列key → 値）に変換する */
export function toExportRow(record) {
  const minutes = recordDuration(record);
  const result = judge(record);
  return {
    date: record.date ?? '',
    direction: directionLabel(record.direction),
    departureTime: record.departureTime ?? '',
    trainTime: record.trainTime ?? '',
    arrivalTime: record.arrivalTime ?? '',
    durationMinutes: Number.isFinite(minutes) ? minutes : '',
    durationText: Number.isFinite(minutes) ? formatDuration(minutes) : '',
    crowding: crowdingLabel(record.crowding),
    delayed: record.delayed ? 'あり' : 'なし',
    detour: record.detour ? 'あり' : 'なし',
    detourNote: record.detourNote ?? '',
    judgement: result ? result.label : '—',
    trackPoints: Number.isFinite(record.trackPoints) ? record.trackPoints : 0,
    trackDistanceKm: Number.isFinite(record.trackDistanceKm)
      ? Math.round(record.trackDistanceKm * 100) / 100
      : '',
    note: record.note ?? '',
  };
}

// ---------------------------------------------------------------------------
// GPS軌跡
// ---------------------------------------------------------------------------

/** 2点間の距離（メートル）。地球を半径6371kmの球とみなすHubeny/haversine簡易版。 */
export function distanceMeters(a, b) {
  const R = 6371000;
  const toRad = (deg) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** 軌跡の総距離（メートル） */
export function trackDistanceMeters(points) {
  if (!Array.isArray(points) || points.length < 2) return 0;
  let total = 0;
  for (let i = 1; i < points.length; i += 1) total += distanceMeters(points[i - 1], points[i]);
  return total;
}
