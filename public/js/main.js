/**
 * アプリの入口
 *
 * - タブ（行き／帰り／集計）の切り替え
 * - フォームと集計画面の組み立て、両者の橋渡し
 * - トースト表示
 * - Service Worker の登録（オフラインでも画面が開けるようにする。任意機能）
 */

import { mountForms } from './form.js';
import { SummaryView } from './summary.js';

const toastElement = document.getElementById('toast');
let toastTimer = null;

/** 画面下に短いメッセージを出す */
function toast(message, kind = 'info') {
  toastElement.textContent = message;
  toastElement.dataset.kind = kind;
  toastElement.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toastElement.hidden = true;
  }, kind === 'error' ? 6000 : 3000);
}

const summary = new SummaryView({
  toast,
  onEdit: (record, track) => {
    // 集計画面の「編集する」から、対応する入力タブへ値を送る
    forms.get(record.direction).loadRecord(record, track);
    activateTab(record.direction);
  },
  onChanged: () => updateCount(),
});

const forms = mountForms({
  toast,
  onSaved: async () => {
    const count = await summary.refresh();
    updateCount(count);
  },
});

// -- タブ ---------------------------------------------------------------------

const tabs = [...document.querySelectorAll('.tab')];

function activateTab(name) {
  for (const tab of tabs) {
    const selected = tab.dataset.tab === name;
    tab.setAttribute('aria-selected', String(selected));
    document.getElementById(`panel-${tab.dataset.tab}`).hidden = !selected;
  }
  // 隠れている間に作った地図は大きさを誤認しているので、表示直後に直す
  if (name === 'summary') summary.show();
  window.scrollTo({ top: 0 });
  // リロードしても同じタブに戻れるよう、選択中のタブを覚えておく
  localStorage.setItem('commute-active-tab', name);
}

for (const tab of tabs) {
  tab.addEventListener('click', () => activateTab(tab.dataset.tab));
}

// -- 件数表示 -----------------------------------------------------------------

const countElement = document.getElementById('record-count');

/**
 * 「〇件目」の表示。20回で終わる前提の上限は設けない
 * （入社後も使い続けられるようにするため）。
 */
async function updateCount(known) {
  const count = known ?? (await summary.refresh());
  countElement.textContent = `記録 ${count}件`;
}

// -- 起動 ---------------------------------------------------------------------

activateTab(localStorage.getItem('commute-active-tab') ?? 'outbound');
updateCount().catch(() => {
  countElement.textContent = 'サーバに接続できません';
});

if ('serviceWorker' in navigator) {
  // http://（LAN内のIP直打ち）では登録できないブラウザもあるが、失敗しても本体は動く
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}
