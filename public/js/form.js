/**
 * 行き／帰りの入力フォーム
 *
 * 行きと帰りは項目が同じなので、index.html の <template> を複製して
 * 同じクラスを2つ作る（direction が違うだけ）。
 *
 * 一番大事なのは「入力の途中経過を失わないこと」。
 * 通勤中に画面を閉じたりバッテリーが切れたりしても困らないよう、
 * 入力値とGPS軌跡は変更のたびに localStorage に保存している。
 */

import {
  CROWDING_LEVELS,
  DIRECTIONS,
  directionOf,
  durationMinutes,
  formatDuration,
  judge,
  validateRecord,
} from '/shared/commute.js';
import { api } from './api.js';
import { TrackRecorder } from './gps.js';
import { createMap, drawTrack, refreshMap } from './map.js';

const BOOLEAN_CHOICES = [
  { value: 'false', label: 'なし' },
  { value: 'true', label: 'あり' },
];

const draftKey = (direction) => `commute-draft:${direction}`;

/** 端末のローカル日付を "YYYY-MM-DD" で返す（toISOString はUTCになるので使わない） */
export function todayString(now = new Date()) {
  const offset = now.getTimezoneOffset() * 60000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}

/** 現在時刻を "HH:MM" で返す */
export function nowTimeString(now = new Date()) {
  return `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
}

export class RecordForm {
  /**
   * @param {HTMLElement} panel   差し込み先のセクション
   * @param {string} direction    'outbound' | 'inbound'
   * @param {{onSaved: Function, toast: Function}} hooks
   */
  constructor(panel, direction, hooks) {
    this.panel = panel;
    this.direction = direction;
    this.hooks = hooks;
    this.editingId = null;
    this.mapHolder = null;
    this.recorder = new TrackRecorder((state) => this.#onTrackChange(state));
  }

  mount() {
    const template = document.getElementById('form-template');
    this.form = template.content.firstElementChild.cloneNode(true);
    this.panel.append(this.form);

    this.#assignIds();
    this.#buildChoices();
    this.#bindEvents();

    // 到着時刻のラベルは行き／帰りで文言を変える
    const meta = directionOf(this.direction);
    this.form.querySelector('[data-arrival-label]').textContent =
      `到着時刻（${meta.arrivalLabel}）`;

    this.#restoreDraft();
    this.#refreshResult();
    return this;
  }

  // -- 組み立て -------------------------------------------------------------

  /** ラベルと入力欄をidで結びつける（行き／帰りで重複しないよう接頭辞を付ける） */
  #assignIds() {
    for (const field of this.form.querySelectorAll('.field')) {
      const input = field.querySelector('input[name], textarea[name]');
      const label = field.querySelector('label.field__label');
      if (!input) continue;
      input.id = `${this.direction}-${input.name}`;
      if (label) label.htmlFor = input.id;
    }
  }

  /** 混雑度・遅延・寄り道の選択肢をデータから生成する */
  #buildChoices() {
    const groups = {
      crowding: CROWDING_LEVELS.map((level) => ({ value: level.value, label: level.label })),
      delayed: BOOLEAN_CHOICES,
      detour: BOOLEAN_CHOICES,
    };

    for (const [name, options] of Object.entries(groups)) {
      const container = this.form.querySelector(`[data-choice-group="${name}"] .choices`);
      for (const option of options) {
        const label = document.createElement('label');
        label.className = 'choice';
        const id = `${this.direction}-${name}-${option.value}`;
        label.innerHTML = `
          <input type="radio" id="${id}" name="${name}" value="${option.value}" />
          <span>${option.label}</span>`;
        container.append(label);
      }
      // 真偽の項目は「なし」を初期選択にしておく（毎回選ばせない）
      if (options === BOOLEAN_CHOICES) {
        this.form.querySelector(`#${this.direction}-${name}-false`).checked = true;
      }
    }
  }

  #bindEvents() {
    this.form.addEventListener('submit', (event) => {
      event.preventDefault();
      this.#save();
    });

    // 入力のたびに計算結果を更新し、下書きを保存する
    this.form.addEventListener('input', () => this.#onInput());
    this.form.addEventListener('change', () => this.#onInput());

    // 「今」ボタン: 現在時刻を入れる
    for (const button of this.form.querySelectorAll('[data-now]')) {
      button.addEventListener('click', () => {
        const input = this.form.elements[button.dataset.now];
        input.value = nowTimeString();
        this.#onInput();
      });
    }

    // GPS
    const gpsButtons = {
      start: () => this.recorder.start(),
      stop: () => this.recorder.stop(),
      clear: () => {
        if (this.recorder.points.length === 0 || confirm('記録した軌跡を消しますか？')) {
          this.recorder.clear();
        }
      },
    };
    for (const [action, handler] of Object.entries(gpsButtons)) {
      this.form.querySelector(`[data-gps="${action}"]`).addEventListener('click', handler);
    }

    this.form.querySelector('[data-action="reset"]').addEventListener('click', () => {
      if (confirm('入力内容と軌跡をすべてクリアしますか？')) this.reset();
    });
    this.form.querySelector('[data-action="cancel-edit"]').addEventListener('click', () => {
      this.reset();
    });
  }

  // -- 値の出し入れ ---------------------------------------------------------

  /** フォームの現在値を1件ぶんのデータにまとめる */
  get values() {
    const get = (name) => this.form.elements[name]?.value ?? '';
    return {
      direction: this.direction,
      date: get('date'),
      departureTime: get('departureTime'),
      trainTime: get('trainTime'),
      crowding: this.form.querySelector('input[name="crowding"]:checked')?.value ?? '',
      delayed: this.form.querySelector('input[name="delayed"]:checked')?.value === 'true',
      arrivalTime: get('arrivalTime'),
      detour: this.form.querySelector('input[name="detour"]:checked')?.value === 'true',
      detourNote: get('detourNote'),
      note: get('note'),
    };
  }

  set values(record) {
    const set = (name, value) => {
      const input = this.form.elements[name];
      if (input && !input.length) input.value = value ?? '';
    };
    set('date', record.date || todayString());
    set('departureTime', record.departureTime);
    set('trainTime', record.trainTime);
    set('arrivalTime', record.arrivalTime);
    set('detourNote', record.detourNote);
    set('note', record.note);

    this.#checkChoice('crowding', record.crowding ?? '');
    this.#checkChoice('delayed', String(Boolean(record.delayed)));
    this.#checkChoice('detour', String(Boolean(record.detour)));
    this.#refreshResult();
  }

  #checkChoice(name, value) {
    for (const input of this.form.querySelectorAll(`input[name="${name}"]`)) {
      input.checked = input.value === value;
    }
  }

  // -- 保存 -----------------------------------------------------------------

  async #save() {
    const values = this.values;
    // サーバと同じ検証関数を使う。ここで弾けば無駄な通信をしなくて済む
    const { ok, errors } = validateRecord(values);
    if (!ok) {
      this.#showErrors(errors);
      return;
    }
    this.#showErrors([]);

    const payload = { ...values, track: this.recorder.points };
    const submit = this.form.querySelector('button[type="submit"]');
    submit.disabled = true;
    submit.textContent = '保存中…';

    try {
      if (this.editingId) {
        await api.updateRecord(this.editingId, payload);
        this.hooks.toast('記録を更新しました');
      } else {
        await api.createRecord(payload);
        this.hooks.toast('記録を保存しました');
      }
      this.reset();
      await this.hooks.onSaved();
    } catch (error) {
      // 保存に失敗しても下書きは残っているので、電波の良い場所で押し直せばよい
      this.#showErrors([error.message, ...(error.errors ?? [])]);
      this.hooks.toast('保存できませんでした（入力内容は端末に残しています）', 'error');
    } finally {
      submit.disabled = false;
      submit.textContent = '保存する';
    }
  }

  /** 既存の記録を編集用に読み込む */
  loadRecord(record, track) {
    this.editingId = record.id;
    this.values = record;
    this.recorder.stop();
    this.recorder.restore(track ?? []);
    this.form.querySelector('[data-editing-banner]').hidden = false;
    this.#saveDraft();
    this.form.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /** 入力・軌跡・編集状態をすべて初期化する */
  reset() {
    this.editingId = null;
    this.recorder.stop();
    this.recorder.clear();
    this.form.reset();
    this.values = { date: todayString(), delayed: false, detour: false };
    this.#checkChoice('crowding', '');
    this.form.querySelector('[data-editing-banner]').hidden = true;
    this.#showErrors([]);
    localStorage.removeItem(draftKey(this.direction));
    this.#refreshResult();
  }

  // -- 下書き（localStorage） ----------------------------------------------

  #saveDraft() {
    const draft = {
      values: this.values,
      editingId: this.editingId,
      points: this.recorder.points,
    };
    try {
      localStorage.setItem(draftKey(this.direction), JSON.stringify(draft));
    } catch {
      // 容量オーバーなど。保存できなくても入力自体は続けられる
    }
  }

  #restoreDraft() {
    let draft = null;
    try {
      draft = JSON.parse(localStorage.getItem(draftKey(this.direction)) ?? 'null');
    } catch {
      draft = null;
    }

    if (!draft) {
      this.values = { date: todayString() };
      return;
    }

    this.values = draft.values ?? {};
    this.editingId = draft.editingId ?? null;
    this.form.querySelector('[data-editing-banner]').hidden = !this.editingId;
    if (draft.points?.length) {
      this.recorder.restore(draft.points);
      this.#setGpsInfo(
        `前回の軌跡 ${draft.points.length}点を復元しました。続けるなら「記録開始」を押してください。`,
      );
    }
  }

  // -- 表示の更新 -----------------------------------------------------------

  #onInput() {
    this.#toggleDetourNote();
    this.#refreshResult();
    this.#saveDraft();
  }

  #toggleDetourNote() {
    const detour = this.form.querySelector('input[name="detour"]:checked')?.value === 'true';
    this.form.querySelector('[data-detour-note]').hidden = !detour;
  }

  /** 所要時間と判定バッジを更新する */
  #refreshResult() {
    const values = this.values;
    const minutes = durationMinutes(values.departureTime, values.arrivalTime);
    this.form.querySelector('[data-duration]').textContent =
      minutes === null ? '所要時間: —' : `所要時間: ${formatDuration(minutes)}`;

    const badge = this.form.querySelector('[data-judgement]');
    const result = judge(values);
    if (!result) {
      badge.hidden = true;
      return;
    }
    badge.hidden = false;
    badge.textContent = result.label;
    badge.style.color = result.color;
    badge.style.background = result.bg;
  }

  #onTrackChange({ points, active, message }) {
    const state = this.form.querySelector('[data-gps-state]');
    state.textContent = active ? `記録中（${points.length}点）` : `停止中（${points.length}点）`;
    state.dataset.active = String(active);

    this.form.querySelector('[data-gps="start"]').disabled = active;
    this.form.querySelector('[data-gps="stop"]').disabled = !active;
    if (message) this.#setGpsInfo(message);

    const mapElement = this.form.querySelector('[data-map]');
    if (points.length > 0) {
      mapElement.hidden = false;
      if (!this.mapHolder) this.mapHolder = createMap(mapElement);
      refreshMap(this.mapHolder);
      drawTrack(this.mapHolder, points);
    } else {
      mapElement.hidden = true;
    }
    this.#saveDraft();
  }

  #setGpsInfo(text) {
    this.form.querySelector('[data-gps-info]').textContent = text;
  }

  #showErrors(errors) {
    const box = this.form.querySelector('[data-errors]');
    box.hidden = errors.length === 0;
    box.textContent = errors.join('\n');
  }
}

/** 行き・帰りの2つぶんのフォームを作る */
export function mountForms(hooks) {
  const forms = new Map();
  for (const { value } of DIRECTIONS) {
    const panel = document.getElementById(`panel-${value}`);
    forms.set(value, new RecordForm(panel, value, hooks).mount());
  }
  return forms;
}
