/**
 * HTTPの組み立て（ルーティング・静的配信・エラー変換）
 *
 * server.js から分けてあるのは、テストから「保存先を差し替えたサーバ」を
 * 立ち上げられるようにするため（test/server.test.js を参照）。
 * このファイル自体はポートを開かない。
 */

import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { NotFoundError, ValidationError, isSafeId } from './store.js';
import { buildRecordsXlsx } from './xlsx.js';
import { compareRecords } from '../shared/commute.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** リクエストボディの上限。GPS軌跡が長いと数MBになるので少し余裕を持たせる。 */
const MAX_BODY_BYTES = 8 * 1024 * 1024;

/** 静的ファイルを配って良いディレクトリ（ここ以外は絶対に読ませない） */
const STATIC_ROOTS = [
  { prefix: '/shared/', dir: path.join(ROOT, 'shared') },
  { prefix: '/', dir: path.join(ROOT, 'public') },
];

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
};

/**
 * 保存層(store)を受け取ってHTTPサーバを作る。
 * storeを引数でもらう＝テストでは一時ディレクトリのstoreを渡せる。
 */
export function createApp(store) {
  return http.createServer((req, res) => {
    handle(store, req, res).catch((error) => {
      console.error('[error]', req.method, req.url, error);
      if (!res.headersSent) sendJson(res, 500, { error: 'サーバ内部エラー' });
      else res.end();
    });
  });
}

// ---------------------------------------------------------------------------
// ルーティング
// ---------------------------------------------------------------------------

async function handle(store, req, res) {
  const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);
  // パス全体をまとめてデコードしない。%2F を先に "/" に戻すと
  // /api/records/..%2F..%2Fetc%2Fpasswd のような細工でルーティングを騙せるため、
  // 判定は生のパスで行い、デコードは静的ファイル側でセグメントごとに行う。
  const pathname = url.pathname;

  if (pathname.startsWith('/api/')) return handleApi(store, req, res, pathname, url);
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return sendJson(res, 405, { error: 'Method Not Allowed' });
  }
  return serveStatic(req, res, pathname);
}

async function handleApi(store, req, res, pathname, url) {
  // 記録1件を指すパス: /api/records/<uuid>
  const recordMatch = /^\/api\/records\/([^/]+)$/.exec(pathname);

  try {
    if (pathname === '/api/health' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, records: (await store.listRecords()).length });
    }

    if (pathname === '/api/records' && req.method === 'GET') {
      const records = (await store.listRecords()).sort(compareRecords);
      const direction = url.searchParams.get('direction');
      return sendJson(res, 200, {
        records: direction ? records.filter((r) => r.direction === direction) : records,
      });
    }

    if (pathname === '/api/records' && req.method === 'POST') {
      const body = await readJsonBody(req);
      const record = await store.createRecord(body, body?.track);
      return sendJson(res, 201, { record });
    }

    if (recordMatch) {
      const id = recordMatch[1];
      if (!isSafeId(id)) return sendJson(res, 400, { error: 'idの形式が不正です' });

      if (req.method === 'GET') {
        const record = await store.getRecord(id);
        if (!record) return sendJson(res, 404, { error: '記録が見つかりません' });
        // 1件取得のときだけGPS軌跡を付ける（地図表示用）
        return sendJson(res, 200, { record, track: await store.getTrack(id) });
      }
      if (req.method === 'PUT') {
        const body = await readJsonBody(req);
        // track を送っていないときは既存の軌跡を残す
        const track = Object.hasOwn(body ?? {}, 'track') ? body.track : undefined;
        return sendJson(res, 200, { record: await store.updateRecord(id, body, track) });
      }
      if (req.method === 'DELETE') {
        await store.deleteRecord(id);
        return sendJson(res, 200, { ok: true });
      }
      return sendJson(res, 405, { error: 'Method Not Allowed' });
    }

    if (pathname === '/api/export.xlsx' && req.method === 'GET') {
      return sendXlsx(res, await store.listRecords());
    }

    return sendJson(res, 404, { error: 'そのAPIはありません' });
  } catch (error) {
    if (error instanceof ValidationError) return sendJson(res, 400, { error: error.message, errors: error.errors });
    if (error instanceof NotFoundError) return sendJson(res, 404, { error: error.message });
    if (error instanceof BadRequestError) return sendJson(res, 400, { error: error.message });
    throw error;
  }
}

// ---------------------------------------------------------------------------
// 応答のヘルパ
// ---------------------------------------------------------------------------

function sendJson(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function sendXlsx(res, records) {
  const buffer = buildRecordsXlsx(records);
  const stamp = new Date().toISOString().slice(0, 10);
  const asciiName = `commute-log-${stamp}.xlsx`;
  const utf8Name = encodeURIComponent(`通勤記録_${stamp}.xlsx`);
  res.writeHead(200, {
    'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    // 日本語ファイル名は filename* (RFC 5987) で渡し、古い環境向けにASCII名も併記する
    'Content-Disposition': `attachment; filename="${asciiName}"; filename*=UTF-8''${utf8Name}`,
    'Content-Length': buffer.length,
    'Cache-Control': 'no-store',
  });
  res.end(buffer);
}

class BadRequestError extends Error {}

/** リクエストボディをJSONとして読む（サイズ上限つき） */
function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new BadRequestError('データが大きすぎます'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      if (!text.trim()) return resolve({});
      try {
        resolve(JSON.parse(text));
      } catch {
        reject(new BadRequestError('JSONの形式が不正です'));
      }
    });
    req.on('error', reject);
  });
}

// ---------------------------------------------------------------------------
// 静的ファイル
// ---------------------------------------------------------------------------

async function serveStatic(req, res, pathname) {
  const target = pathname === '/' ? '/index.html' : pathname;
  const segments = decodeSegments(target);
  if (segments === null) return sendNotFound(res);

  for (const { prefix, dir } of STATIC_ROOTS) {
    if (!target.startsWith(prefix)) continue;
    const depth = prefix.split('/').filter(Boolean).length;
    const filePath = path.join(dir, ...segments.slice(depth));

    // path.join の結果が dir の外に出ていないか必ず確認する（../ 対策）
    if (filePath !== dir && !filePath.startsWith(dir + path.sep)) continue;

    try {
      const stat = await fsp.stat(filePath);
      if (!stat.isFile()) continue;
      return sendFile(req, res, filePath, stat);
    } catch {
      continue;
    }
  }

  return sendNotFound(res);
}

/**
 * URLのパスをセグメントごとにデコードする。
 * デコード結果に "/" や "\\" が現れる、あるいは ".." が含まれる場合は
 * ディレクトリを抜け出そうとしているので null を返して拒否する。
 */
function decodeSegments(pathname) {
  const segments = [];
  for (const raw of pathname.split('/')) {
    if (raw === '') continue;
    let decoded;
    try {
      decoded = decodeURIComponent(raw);
    } catch {
      return null; // 壊れたパーセントエンコード
    }
    if (decoded.includes('/') || decoded.includes('\\') || decoded === '..' || decoded === '.') {
      return null;
    }
    segments.push(decoded);
  }
  return segments;
}

function sendNotFound(res) {
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('404 Not Found');
}

function sendFile(req, res, filePath, stat) {
  const type = MIME_TYPES[path.extname(filePath).toLowerCase()] ?? 'application/octet-stream';
  // 更新時刻とサイズから簡易ETagを作る。変わっていなければ304で済ませる。
  const etag = `W/"${stat.size}-${Number(stat.mtimeMs).toString(16)}"`;

  if (req.headers['if-none-match'] === etag) {
    res.writeHead(304, { ETag: etag });
    return res.end();
  }

  res.writeHead(200, {
    'Content-Type': type,
    'Content-Length': stat.size,
    ETag: etag,
    // 端末に古い画面が残らないよう、毎回サーバに確認させる（サイズが小さいので十分速い）
    'Cache-Control': 'no-cache',
  });
  if (req.method === 'HEAD') return res.end();
  fs.createReadStream(filePath).pipe(res);
}
