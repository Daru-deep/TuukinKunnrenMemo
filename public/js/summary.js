/**
 * 集計・出力画面
 *
 * 行き・帰りの平均／最短、判定の内訳、記録一覧（色分け）、
 * 最短記録のルート地図、xlsxダウンロードをまとめて表示する。
 *
 * 集計そのものは shared/commute.js の summarize() が行う。
 * ここは「受け取った結果をDOMに反映するだけ」に保つと見通しがよい。
 */

import {
  DIRECTIONS,
  JUDGEMENTS,
  compareRecords,
  crowdingLabel,
  directionLabel,
  formatDateShort,
  formatDuration,
  judge,
  recordDuration,
  summarize,
} from '/shared/commute.js';
import { api } from './api.js';
import { createMap, drawTrack, refreshMap } from './map.js';

const escapeHtml = (value) =>
  String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');

export class SummaryView {
  /** @param {{onEdit: Function, toast: Function}} hooks */
  constructor(hooks) {
    this.hooks = hooks;
    this.records = [];
    this.fastestMap = null;
    this.detailMap = null;
    this.statsElement = document.getElementById('stats');
    this.listElement = document.getElementById('record-list');
    this.fastestNote = document.getElementById('fastest-note');
    this.dialog = document.getElementById('detail-dialog');
    this.#bindDialog();
  }

  /** サーバから読み直して画面全体を描き直す */
  async refresh() {
    try {
      this.records = (await api.listRecords()).sort(compareRecords);
    } catch (error) {
      this.hooks.toast(error.message, 'error');
      return this.records.length;
    }
    this.#renderStats();
    this.#renderList();
    await this.#renderFastestRoute();
    return this.records.length;
  }

  /** タブが表示された直後に呼ぶ（隠れた状態で作った地図はサイズを取り直す必要がある） */
  show() {
    refreshMap(this.fastestMap);
  }

  // -- 集計カード -----------------------------------------------------------

  #renderStats() {
    const stats = summarize(this.records);
    this.statsElement.innerHTML = DIRECTIONS.map(({ value, label }) => {
      const summary = stats[value];
      const average =
        summary.averageMinutes === null ? '—' : formatDuration(Math.round(summary.averageMinutes));
      const fastest = summary.fastest
        ? `最短 ${formatDuration(summary.fastest.minutes)}（${formatDateShort(summary.fastest.record.date)}）`
        : '最短 —';
      const slowest = summary.slowest ? `最長 ${formatDuration(summary.slowest.minutes)}` : '最長 —';

      // 判定は行きだけに出す（帰りは参考記録なので内訳を出さない）
      const judgements =
        value === 'outbound'
          ? `<div class="judge-summary">${this.#judgementBadges(summary.judgementCounts)}</div>`
          : '';

      return `
        <div class="stat">
          <p class="stat__title">${label}の平均所要時間（${summary.count}件）</p>
          <div class="stat__value">${average}</div>
          <div class="stat__sub">${fastest}<br />${slowest}</div>
          ${judgements}
        </div>`;
    }).join('');
  }

  /** オンタイム/注意/遅刻の件数バッジ。色はshared側の定義をそのまま使う。 */
  #judgementBadges(counts) {
    return JUDGEMENTS.map(
      (rule) =>
        `<span class="badge" style="color:${rule.color};background:${rule.bg}">` +
        `${rule.label} ${counts[rule.value] ?? 0}</span>`,
    ).join('');
  }

  // -- 記録一覧 -------------------------------------------------------------

  #renderList() {
    if (this.records.length === 0) {
      this.listElement.innerHTML = '<p class="empty">まだ記録がありません</p>';
      return;
    }

    // 新しい記録を上に出す（直近の結果をすぐ確認したいため）
    const rows = [...this.records].reverse().map((record) => {
      const result = judge(record);
      const minutes = recordDuration(record);
      const color = result ? result.color : 'var(--border)';
      const badge = result
        ? `<span class="badge" style="color:${result.color};background:${result.bg}">${result.label}</span>`
        : `<span class="badge" style="color:var(--muted)">${directionLabel(record.direction)}</span>`;

      return `
        <button type="button" class="record-row" data-id="${record.id}" style="border-left-color:${color}">
          <span class="record-row__date">${formatDateShort(record.date)}</span>
          <span>
            <span class="record-row__times">${escapeHtml(record.departureTime)} → ${escapeHtml(record.arrivalTime)}</span>
            ${record.delayed ? '<span class="record-row__times"> / 遅延</span>' : ''}
            ${record.detour ? '<span class="record-row__times"> / 寄り道</span>' : ''}
          </span>
          <span class="record-row__duration">${formatDuration(minutes)}<br />${badge}</span>
        </button>`;
    });

    this.listElement.innerHTML = rows.join('');
    for (const row of this.listElement.querySelectorAll('.record-row')) {
      row.addEventListener('click', () => this.#openDetail(row.dataset.id));
    }
  }

  // -- 最短記録のルート -----------------------------------------------------

  async #renderFastestRoute() {
    const stats = summarize(this.records);
    const fastest = stats.outbound.fastest ?? stats.inbound.fastest;
    if (!fastest) {
      this.fastestNote.textContent = '記録がありません';
      return;
    }

    const { record, minutes } = fastest;
    this.fastestNote.textContent =
      `${formatDateShort(record.date)} ${directionLabel(record.direction)} ` +
      `${record.departureTime} → ${record.arrivalTime}（${formatDuration(minutes)}）`;

    const element = document.getElementById('fastest-map');
    if (!this.fastestMap) this.fastestMap = createMap(element);
    refreshMap(this.fastestMap);

    try {
      const { track } = await api.getRecord(record.id);
      drawTrack(this.fastestMap, track);
      if (!track || track.length === 0) {
        this.fastestNote.textContent += '（この記録にはGPS軌跡がありません）';
      }
    } catch (error) {
      this.hooks.toast(error.message, 'error');
    }
  }

  // -- 詳細ダイアログ -------------------------------------------------------

  #bindDialog() {
    document.getElementById('detail-close').addEventListener('click', () => this.dialog.close());
    this.dialog.addEventListener('close', () => {
      // ダイアログを閉じるときに地図を破棄しないと、次に開いたとき二重に生成される
      this.detailMap?.map.remove();
      this.detailMap = null;
    });

    document.getElementById('detail-edit').addEventListener('click', () => {
      const { record, track } = this.current ?? {};
      if (!record) return;
      this.dialog.close();
      this.hooks.onEdit(record, track);
    });

    document.getElementById('detail-delete').addEventListener('click', async () => {
      const record = this.current?.record;
      if (!record) return;
      if (!confirm(`${formatDateShort(record.date)}の記録を削除しますか？`)) return;
      try {
        await api.deleteRecord(record.id);
        this.dialog.close();
        this.hooks.toast('削除しました');
        await this.refresh();
        this.hooks.onChanged?.();
      } catch (error) {
        this.hooks.toast(error.message, 'error');
      }
    });
  }

  async #openDetail(id) {
    let detail;
    try {
      detail = await api.getRecord(id);
    } catch (error) {
      return this.hooks.toast(error.message, 'error');
    }
    this.current = detail;

    const { record, track } = detail;
    const result = judge(record);
    const minutes = recordDuration(record);
    const rows = [
      ['日付', formatDateShort(record.date)],
      ['区分', directionLabel(record.direction)],
      ['出発', record.departureTime],
      ['乗車電車', record.trainTime || '—'],
      ['到着', record.arrivalTime],
      ['所要時間', formatDuration(minutes)],
      ['混雑度', crowdingLabel(record.crowding) || '—'],
      ['遅延', record.delayed ? 'あり' : 'なし'],
      ['寄り道', record.detour ? `あり（${record.detourNote || 'メモなし'}）` : 'なし'],
      ['判定', result ? result.label : '—（帰りは判定なし）'],
      ['GPS', track.length > 0 ? `${track.length}点 / 約${(record.trackDistanceKm ?? 0).toFixed(2)}km` : '記録なし'],
      ['メモ', record.note || '—'],
    ];

    document.getElementById('detail-body').innerHTML = `
      <h2>記録の詳細</h2>
      <dl>${rows.map(([term, value]) => `<dt>${term}</dt><dd>${escapeHtml(value)}</dd>`).join('')}</dl>
      ${track.length > 0 ? '<div class="map map--mini" id="detail-map"></div>' : ''}`;

    this.dialog.showModal();

    if (track.length > 0) {
      // showModal() の後に作らないと、幅0の要素に地図を作ってしまう
      this.detailMap = createMap(document.getElementById('detail-map'));
      refreshMap(this.detailMap);
      drawTrack(this.detailMap, track);
    }
  }
}
