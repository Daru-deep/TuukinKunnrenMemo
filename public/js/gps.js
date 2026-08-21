/**
 * GPSによる移動軌跡の記録
 *
 * 方針:
 * - Geolocation API の watchPosition で位置を受け取り続ける
 * - 届いた点をすべて溜めると数千点になるので、
 *   「前の点から5m以上動いた」または「5秒以上経った」ものだけを残す
 * - 精度が極端に悪い点（誤差100m超）は捨てる。地下や駅構内で飛びやすいため
 * - 画面が消えると記録が止まる端末があるので、可能なら Wake Lock で画面を保つ
 *
 * 「地下道かサンシャイン通りか」の自動判定は、まずこの軌跡を貯めてから
 * 後で判定ロジックを足せばよい（保存している座標があれば後から分類できる）。
 */

import { distanceMeters } from '/shared/commute.js';

const MIN_DISTANCE_METERS = 5;
const MIN_INTERVAL_MS = 5000;
const MAX_ACCURACY_METERS = 100;

export class TrackRecorder {
  /**
   * @param {(state: {points: object[], active: boolean, lastAccuracy: number|null, message: string}) => void} onChange
   */
  constructor(onChange) {
    this.points = [];
    this.watchId = null;
    this.wakeLock = null;
    this.lastAccuracy = null;
    this.message = '';
    this.onChange = onChange;
    this.handleVisibility = () => {
      // 画面を戻したときにWake Lockを取り直す（仕様上、非表示になると解放される）
      if (this.isActive && document.visibilityState === 'visible') this.#requestWakeLock();
    };
  }

  get isActive() {
    return this.watchId !== null;
  }

  start() {
    if (this.isActive) return;
    if (!navigator.geolocation) {
      this.#update('この端末では位置情報が使えません');
      return;
    }

    this.watchId = navigator.geolocation.watchPosition(
      (position) => this.#onPosition(position),
      (error) => this.#onError(error),
      // enableHighAccuracy: 電池を食うが、駅〜職場の徒歩ルートには必要
      { enableHighAccuracy: true, maximumAge: 0, timeout: 30000 },
    );

    document.addEventListener('visibilitychange', this.handleVisibility);
    this.#requestWakeLock();
    this.#update('記録中です。到着したら「停止」を押してください。');
  }

  stop() {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
    }
    document.removeEventListener('visibilitychange', this.handleVisibility);
    this.#releaseWakeLock();
    this.#update(this.points.length > 0 ? '記録を止めました。保存すると軌跡も残ります。' : '');
  }

  /** 軌跡だけ捨てる（入力内容は消さない） */
  clear() {
    this.points = [];
    this.lastAccuracy = null;
    this.#update('軌跡を破棄しました');
  }

  /** 下書きから復元する（通勤中にブラウザを閉じても続きから記録できる） */
  restore(points) {
    this.points = Array.isArray(points) ? points : [];
    this.#update('');
  }

  #onPosition(position) {
    const { latitude, longitude, accuracy } = position.coords;
    this.lastAccuracy = Math.round(accuracy);
    if (accuracy > MAX_ACCURACY_METERS) {
      this.#update(`測位の精度が粗いため無視しました（±${Math.round(accuracy)}m）`);
      return;
    }

    const point = {
      lat: Number(latitude.toFixed(6)),
      lng: Number(longitude.toFixed(6)),
      t: position.timestamp,
      acc: Math.round(accuracy),
    };
    const previous = this.points.at(-1);
    if (previous) {
      const moved = distanceMeters(previous, point);
      const elapsed = point.t - (previous.t ?? 0);
      // ほとんど動いていない＆間隔も短い点は間引く（信号待ちで点が溜まらないように）
      if (moved < MIN_DISTANCE_METERS && elapsed < MIN_INTERVAL_MS) return;
    }

    this.points.push(point);
    this.#update(`記録中（${this.points.length}点 / 誤差±${this.lastAccuracy}m）`);
  }

  #onError(error) {
    const messages = {
      1: '位置情報の利用が許可されていません（ブラウザの設定を確認してください）',
      2: '現在地を取得できませんでした（地下などで測位できていない可能性があります）',
      3: '位置情報の取得がタイムアウトしました',
    };
    this.#update(messages[error.code] ?? `位置情報のエラー: ${error.message}`);
  }

  async #requestWakeLock() {
    if (!('wakeLock' in navigator)) return;
    try {
      this.wakeLock = await navigator.wakeLock.request('screen');
    } catch {
      // 拒否されても記録自体は続けられるので握りつぶす
    }
  }

  #releaseWakeLock() {
    this.wakeLock?.release?.().catch(() => {});
    this.wakeLock = null;
  }

  #update(message) {
    this.message = message;
    this.onChange({
      points: this.points,
      active: this.isActive,
      lastAccuracy: this.lastAccuracy,
      message,
    });
  }
}
