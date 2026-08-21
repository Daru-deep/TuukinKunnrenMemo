/**
 * サーバとのやり取りをまとめる層。
 * 画面側（form.js / summary.js）が fetch を直接呼ばないようにしておくと、
 * 「エラーメッセージの出し方」を1か所で直せる。
 */

export class ApiError extends Error {
  constructor(message, errors = []) {
    super(message);
    this.name = 'ApiError';
    this.errors = errors;
  }
}

async function request(method, url, body) {
  let res;
  try {
    res = await fetch(url, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    // 圏外や、サーバが落ちているとき
    throw new ApiError('サーバに接続できませんでした。電波とサーバの状態を確認してください。');
  }

  const isJson = res.headers.get('content-type')?.includes('application/json');
  const data = isJson ? await res.json().catch(() => null) : null;

  if (!res.ok) {
    throw new ApiError(data?.error ?? `通信に失敗しました (${res.status})`, data?.errors ?? []);
  }
  return data;
}

export const api = {
  /** 記録の一覧（GPS軌跡は含まない） */
  async listRecords() {
    return (await request('GET', '/api/records')).records;
  },
  /** 1件（GPS軌跡つき） */
  async getRecord(id) {
    return request('GET', `/api/records/${id}`);
  },
  async createRecord(record) {
    return (await request('POST', '/api/records', record)).record;
  },
  async updateRecord(id, record) {
    return (await request('PUT', `/api/records/${id}`, record)).record;
  },
  async deleteRecord(id) {
    return request('DELETE', `/api/records/${id}`);
  },
};
