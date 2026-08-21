# 通勤記録（TuukinKunnrenMemo）

通勤訓練の記録を、その場でスマホから入力して集計・xlsx出力するためのWebアプリ。

- 行き／帰りの時刻を都度入力し、所要時間と到着判定（オンタイム／注意／遅刻）を自動計算
- GPSで移動の軌跡を記録し、OpenStreetMapの地図に表示
- 全記録の平均・最短、判定の内訳を集計し、xlsxとしてダウンロード

**依存パッケージはゼロ**（Node.js標準モジュールだけで動く）。`npm install` は不要。
地図はLeaflet + OpenStreetMapタイルで、課金の発生する地図APIは使っていない。

---

## 1. 動かす

必要なもの: Node.js 18以上（`node --version` で確認）

```bash
git clone <このリポジトリ>
cd TuukinKunnrenMemo
node server.js
# → http://0.0.0.0:8080 で起動
```

同じWi-Fiにいるスマホからは `http://<UbuntuマシンのIP>:8080` で開ける。
IPは `ip -4 addr show | grep inet` で確認する。

環境変数で変えられる設定:

| 変数 | 既定値 | 説明 |
| --- | --- | --- |
| `PORT` | `8080` | 待ち受けポート |
| `HOST` | `0.0.0.0` | `127.0.0.1` にすると同じマシンからのみ |
| `DATA_DIR` | `./data` | 記録の保存先 |
| `TLS_KEY` / `TLS_CERT` | なし | 両方指定するとHTTPSで起動 |

### まず画面を見てみたいとき

サンプルの記録を作れる（実データと混ざらないよう保存先を分けること）:

```bash
DATA_DIR=./data-demo node scripts/seed.js 12   # 12日ぶん
DATA_DIR=./data-demo node server.js
```

---

## 2. GPSを使うにはHTTPSが要る（重要）

ブラウザは **Geolocation API と Service Worker を「安全なコンテキスト」でしか許可しない**。
安全なコンテキストとは `https://` か `http://localhost` のこと。
つまりスマホから `http://192.168.1.20:8080` で開くと、**位置情報の取得だけが動かない**
（入力・集計・xlsxは動く）。

対処はどれか1つ。

### A. 自己署名証明書でHTTPSにする（LAN内だけならこれで十分）

```bash
# UbuntuマシンのLAN IPを入れて証明書を作る（例: 192.168.1.20）
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout tls-key.pem -out tls-cert.pem \
  -subj "/CN=192.168.1.20" \
  -addext "subjectAltName=IP:192.168.1.20"

TLS_KEY=./tls-key.pem TLS_CERT=./tls-cert.pem PORT=8443 node server.js
```

スマホで `https://192.168.1.20:8443` を開くと警告が出るので、
「詳細設定 → アクセスする」で進む。iOSのSafariでGPSまで許可されない場合は、
作った `tls-cert.pem` を端末にインストールして信頼させると安定する。

> 生成した `tls-key.pem` / `tls-cert.pem` はリポジトリに入れないこと（`.gitignore` 済み）。

### B. Tailscale などのVPNを使う

Tailscale の MagicDNS + `tailscale cert` で正式なHTTPS証明書が取れる。
外出先からも使えるようになるので、通勤訓練の用途にはこれが一番快適。

### C. Androidのみ: Chromeのフラグで例外にする

`chrome://flags/#unsafely-treat-insecure-origin-as-secure` に
`http://192.168.1.20:8080` を登録する。手軽だが端末ごとの設定が必要。

---

## 3. 個人情報の扱い

このアプリが持つデータは、**自宅と職場の位置・毎日の行動時刻**という
かなり具体的な個人情報になる。次の前提で作ってある。

- **`data/` はGitに入らない**（`.gitignore` 済み）。記録本体もGPS軌跡もコミットされない
- **xlsxにGPS座標を出力しない**。点数と距離だけを載せているので、
  出力ファイルを他人に見せても移動経路そのものは分からない
- **地図の初期位置は東京駅**。自宅の座標をコードに埋め込んでいない
- **入力途中の下書きはスマホのlocalStorageに残る**。共用端末では使わないこと
  （「入力をクリア」を押すと消える）
- **認証機能は無い**。URLを知っていれば誰でも読み書きできるので、
  **インターネットに直接公開しない**こと。ルータのポート開放はしない。
  外から使いたいならVPN（上のB）を使う

もし公開サーバに置くなら、nginxのBasic認証を前に挟むなど、
必ず認証を足してから公開すること。

---

## 4. ファイル構成

```
shared/commute.js     ドメインロジック（ブラウザとサーバの両方が使う）
                      所要時間・判定・入力検証・集計・出力列の定義
lib/store.js          記録の保存（JSONファイル / 原子的書き込み）
lib/app.js            HTTPのルーティングと静的配信
lib/xlsx.js           xlsx生成（styles.xmlで判定の色分け）
lib/zip.js            最小限のZIP書き出し（xlsxの中身はZIP）
server.js             起動スクリプト（HTTP / HTTPS）
public/index.html     画面（行き・帰り・集計の3タブ）
public/js/form.js     入力フォーム（下書き自動保存・GPS連携）
public/js/summary.js  集計画面
public/js/gps.js      GPS記録（間引き・精度フィルタ・Wake Lock）
public/js/map.js      Leaflet + OSM
public/js/api.js      サーバとの通信
scripts/seed.js       サンプルデータ生成
test/                 テスト（node --test）
data/                 記録の保存先（Git管理外）
```

設計上いちばん大事なのは **`shared/commute.js` を画面とサーバで共用している**こと。
所要時間の計算や判定がここ1か所にしかないので、
「画面に出ている数字」と「xlsxの数字」がズレようがない。

---

## 5. よく変えたくなるところ

### 判定の基準時刻を変える

`shared/commute.js` の `JUDGEMENTS` を直すだけでよい。
画面の色・バッジ・xlsxの塗りつぶしがすべて追随する。

```js
export const JUDGEMENTS = [
  { value: 'ontime', label: 'オンタイム', before: '08:30', ... },
  { value: 'warn',   label: '注意',       before: '09:00', ... },
  { value: 'late',   label: '遅刻',       before: null,    ... },
];
```

### xlsxの列を増やす

同じく `shared/commute.js` の `EXPORT_COLUMNS` に1行足し、
`toExportRow()` に値の作り方を書く。xlsx側は触らなくてよい。

### 混雑度の選択肢を変える

`CROWDING_LEVELS` を編集する。入力画面のボタンはこの配列から生成している。

---

## 6. テスト

```bash
npm test        # = node --test
```

- `test/commute.test.js` … 日またぎの所要時間、判定の境界値（8:29 / 8:30 / 8:59 / 9:00）、集計
- `test/xlsx.test.js` … 自作ZIPが規格どおりか、色分けとエスケープ、座標を出力していないこと
- `test/server.test.js` … APIのCRUD、入力検証、パストラバーサル対策

---

## 7. Ubuntuで常時起動する（systemd）

`/etc/systemd/system/tuukin.service`:

```ini
[Unit]
Description=通勤記録アプリ
After=network.target

[Service]
Type=simple
User=<実行ユーザー>
WorkingDirectory=/home/<ユーザー>/TuukinKunnrenMemo
Environment=PORT=8443
Environment=TLS_KEY=/home/<ユーザー>/TuukinKunnrenMemo/tls-key.pem
Environment=TLS_CERT=/home/<ユーザー>/TuukinKunnrenMemo/tls-cert.pem
ExecStart=/usr/bin/node server.js
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now tuukin
journalctl -u tuukin -f      # ログを見る
```

Minecraftサーバと同居させる場合、ポートが被らないよう `PORT` を確認すること。

### バックアップ

記録は `data/` にJSONで入っているだけなので、コピーすれば済む。

```bash
tar czf ~/tuukin-backup-$(date +%Y%m%d).tar.gz data/
```

---

## 8. これから足せること（次の一歩）

初期実装では「軌跡（座標ログ）を貯める」ところまでにしてある。
座標が残っているので、後からでも次のような分析を足せる。

1. **ルートの自動判定**（地下道 / サンシャイン通り など）
   判定したい経路の代表点をいくつか登録しておき、軌跡がその近くを通ったかで分類する。
   `shared/commute.js` の `distanceMeters()` がそのまま使える。
2. **区間ごとの所要時間**（自宅→駅、電車、駅→職場）
   いまは出発と到着の2点だけ。軌跡の時刻を使えば徒歩区間だけ切り出せる。
3. **混雑度と所要時間の相関**を集計画面に出す
4. **CSV出力**（`EXPORT_COLUMNS` を使い回せばすぐ書ける）
5. **記録件数が数千件を超えたら** `data/records.json` を分割するか SQLite に移す
   （`lib/store.js` の関数の形を保てば、他のファイルは変えずに差し替えられる）

---

## ライセンス / クレジット

- 地図: [Leaflet](https://leafletjs.com/)（BSD-2-Clause, `public/vendor/leaflet/LICENSE`）
- タイル: © OpenStreetMap contributors（[利用条件](https://operations.osmfoundation.org/policies/tiles/)）
  個人利用の範囲を超えるアクセスをしないこと。
