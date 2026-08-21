/**
 * 記録の保存層（JSONファイル）
 *
 * 設計メモ:
 * - DBを使わないのは、記録が年に数百件程度で、`cat data/records.json` で
 *   中身をそのまま読めるほうが扱いやすいため。将来SQLiteに移すとしても、
 *   このファイルの関数の形（listRecords/createRecord/...）を保てば
 *   server.js 側は書き換えずに済む。
 * - GPS軌跡は1件で数千点になりうるので records.json には入れず、
 *   data/tracks/<id>.json に分けて置く。一覧APIを軽く保つための分離。
 * - 書き込みは「一時ファイル → rename」。renameは同一ファイルシステム上で
 *   原子的なので、書き込み中に電源が落ちても壊れたJSONが残らない。
 */

import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { trackDistanceMeters, validateRecord } from '../shared/commute.js';

/** 1件の軌跡に保存する最大点数（暴走した端末から巨大データが来ても壊れないように） */
const MAX_TRACK_POINTS = 50000;

export class Store {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.recordsFile = path.join(dataDir, 'records.json');
    this.tracksDir = path.join(dataDir, 'tracks');
    /** 書き込みを直列化するためのキュー（最後の書き込みPromiseを繋いでいく） */
    this.queue = Promise.resolve();
  }

  async init() {
    await fs.mkdir(this.tracksDir, { recursive: true });
    try {
      await fs.access(this.recordsFile);
    } catch {
      await this.#writeJson(this.recordsFile, []);
    }
    return this;
  }

  // -- 読み取り ------------------------------------------------------------

  /** 全記録（軌跡の点は含まない）。壊れたファイルなら空配列を返す。 */
  async listRecords() {
    const data = await this.#readJson(this.recordsFile, []);
    return Array.isArray(data) ? data : [];
  }

  async getRecord(id) {
    const records = await this.listRecords();
    return records.find((record) => record.id === id) ?? null;
  }

  /** GPS軌跡（点の配列）。無ければ空配列。 */
  async getTrack(id) {
    if (!isSafeId(id)) return [];
    return this.#readJson(this.trackFile(id), []);
  }

  trackFile(id) {
    return path.join(this.tracksDir, `${id}.json`);
  }

  // -- 書き込み ------------------------------------------------------------

  /**
   * 新規作成。入力は必ず validateRecord を通してから保存する
   * （クライアントが送ってきた余計なキーを持ち込ませない）。
   */
  async createRecord(input, track) {
    const { ok, errors, value } = validateRecord(input);
    if (!ok) throw new ValidationError(errors);

    const id = randomUUID();
    const points = normalizeTrack(track);
    const now = new Date().toISOString();
    const record = {
      id,
      ...value,
      ...trackSummary(points),
      createdAt: now,
      updatedAt: now,
    };

    return this.#mutate(async (records) => {
      if (points.length > 0) await this.#writeJson(this.trackFile(id), points);
      return { records: [...records, record], result: record };
    });
  }

  /**
   * 更新。track が undefined のときは既存の軌跡をそのまま残す
   * （時刻だけ直したいケースで軌跡を消さないため）。
   */
  async updateRecord(id, input, track) {
    const { ok, errors, value } = validateRecord(input);
    if (!ok) throw new ValidationError(errors);

    return this.#mutate(async (records) => {
      const index = records.findIndex((record) => record.id === id);
      if (index === -1) throw new NotFoundError(id);

      let summary = {
        trackPoints: records[index].trackPoints ?? 0,
        trackDistanceKm: records[index].trackDistanceKm ?? 0,
      };
      if (track !== undefined) {
        const points = normalizeTrack(track);
        summary = trackSummary(points);
        if (points.length > 0) await this.#writeJson(this.trackFile(id), points);
        else await fs.rm(this.trackFile(id), { force: true });
      }

      const updated = {
        ...records[index],
        ...value,
        ...summary,
        updatedAt: new Date().toISOString(),
      };
      const next = [...records];
      next[index] = updated;
      return { records: next, result: updated };
    });
  }

  async deleteRecord(id) {
    return this.#mutate(async (records) => {
      const target = records.find((record) => record.id === id);
      if (!target) throw new NotFoundError(id);
      if (isSafeId(id)) await fs.rm(this.trackFile(id), { force: true });
      return { records: records.filter((record) => record.id !== id), result: target };
    });
  }

  // -- 内部 ----------------------------------------------------------------

  /**
   * 読み込み→変更→書き込みを直列に実行する。
   * ブラウザから同時に2件POSTされても、片方の書き込みが消える事故を防ぐ。
   */
  #mutate(fn) {
    const run = this.queue.then(async () => {
      const records = await this.listRecords();
      const { records: next, result } = await fn(records);
      await this.#writeJson(this.recordsFile, next);
      return result;
    });
    // 失敗しても後続の書き込みが止まらないようにキューだけは繋ぎ直す
    this.queue = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  async #readJson(file, fallback) {
    try {
      return JSON.parse(await fs.readFile(file, 'utf8'));
    } catch {
      return fallback;
    }
  }

  async #writeJson(file, data) {
    const tmp = `${file}.${process.pid}.tmp`;
    await fs.writeFile(tmp, JSON.stringify(data, null, 2), 'utf8');
    await fs.rename(tmp, file);
  }
}

export class ValidationError extends Error {
  constructor(errors) {
    super(errors.join(' / '));
    this.name = 'ValidationError';
    this.errors = errors;
  }
}

export class NotFoundError extends Error {
  constructor(id) {
    super(`記録が見つかりません: ${id}`);
    this.name = 'NotFoundError';
  }
}

/** UUID以外のidでファイルパスを組み立てさせない（パストラバーサル対策） */
export function isSafeId(id) {
  return typeof id === 'string' && /^[0-9a-fA-F-]{36}$/.test(id);
}

/** 端末から来た点列を検証して保存できる形に整える */
export function normalizeTrack(track) {
  if (!Array.isArray(track)) return [];
  const points = [];
  for (const raw of track) {
    const lat = Number(raw?.lat);
    const lng = Number(raw?.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) continue;
    const point = { lat: round6(lat), lng: round6(lng) };
    const t = Number(raw?.t);
    if (Number.isFinite(t)) point.t = Math.round(t);
    const acc = Number(raw?.acc);
    if (Number.isFinite(acc)) point.acc = Math.round(acc);
    points.push(point);
    if (points.length >= MAX_TRACK_POINTS) break;
  }
  return points;
}

/** 座標は小数6桁（約10cm）で十分。桁を落としてファイルサイズを抑える。 */
const round6 = (n) => Math.round(n * 1e6) / 1e6;

function trackSummary(points) {
  return {
    trackPoints: points.length,
    trackDistanceKm: Math.round((trackDistanceMeters(points) / 1000) * 1000) / 1000,
  };
}
