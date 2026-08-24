# 通勤記録（TuukinKunnrenMemo）

通勤訓練の記録を、その場でスマホから入力して集計・xlsx出力するAndroidアプリ。

- 行き／帰りの時刻を都度入力し、所要時間と到着判定（オンタイム／注意／遅刻）を自動計算
- GPSで移動の軌跡を記録し、OpenStreetMapの地図に表示（**画面を消していても記録が続く**）
- 全記録の平均・最短、判定の内訳を集計し、xlsxとして書き出し

Kotlin + Jetpack Compose。記録はすべて端末内に保存され、サーバも通信もいらない
（地図のタイル取得だけインターネットを使う）。

> 以前つくったブラウザ版（Node.js + Webアプリ）は、この履歴の
> `1d4030f HTTPS起動オプション・サンプルデータ生成・READMEを追加` に残っている。
> `git show 1d4030f:README.md` で当時の手順を読める。

---

## 1. ビルドして端末に入れる

必要なもの: **Android Studio**（Ladybug 2024.2.1 以降）と、開発者向けオプションを
有効にしたAndroid端末（Android 8.0 / API 26 以上）。

1. Android Studio で **Open** → このフォルダを選ぶ
2. 初回は Gradle の同期で依存ライブラリがダウンロードされる（数分）
3. 端末をUSBで繋ぎ、▶ Run を押す

コマンドラインから入れる場合:

```bash
./gradlew installDebug     # 繋がっている端末にインストール
./gradlew assembleDebug    # APKだけ作る → app/build/outputs/apk/debug/
./gradlew test             # ユニットテスト（端末不要）
```

`local.properties` に `sdk.dir` が要るが、Android Studio で一度開けば自動で作られる。

### 使うライブラリ

| ライブラリ | 用途 | 備考 |
| --- | --- | --- |
| Jetpack Compose (Material 3) | 画面 | |
| play-services-location | 位置情報 | GPSに加えWi-Fi・基地局も使うため、駅ビルの中でも点が飛びにくい |
| osmdroid | 地図 | OpenStreetMapのタイル。**APIキー不要・課金なし** |

Google Maps を使っていないのは、Maps SDK のAPIキー発行に課金アカウントの登録が
必要なため。xlsxの生成も外部ライブラリを使わず、`java.util.zip` で自作している
（Apache POI はAndroidだと重い）。

---

## 2. 使い方

1. **行き** タブで、出発前に「記録開始」を押す（初回は位置情報と通知の許可を求められる）
2. 出発したら「出発時刻」の **今** を押す。電車に乗ったら「乗車電車」の **今**
3. 混雑度・遅延を選ぶ
4. 職場に着いたら「到着時刻」の **今** → 所要時間と判定がその場で出る
5. 「保存する」を押す（GPSの記録も一緒に止まって保存される）

帰りも同じ。**集計・出力** タブで平均・最短ルート・記録一覧を見て、
xlsxを書き出せる。

記録中はスマホの通知に「◯点を記録しました」と出る。通知から直接停止もできる。

一覧の行をタップすると詳細（地図つき）が開き、そこから編集・削除ができる。

### 覚えておくと安心なこと

- 入力の途中経過は自動で端末に保存される。通勤中にアプリを閉じても消えない
- GPSの軌跡も点が増えるたびに保存している。アプリがOSに終了されても続きから記録できる
- 判定が出るのは **行き** だけ（帰りは参考記録として集計だけする）
- 記録件数に上限はない。20回で終わる前提の作りにはしていない

---

## 3. 個人情報の扱い

このアプリが持つのは、**自宅と職場の位置・毎日の行動時刻**という、かなり具体的な
個人情報になる。次の前提で作ってある。

- **記録は端末内のアプリ専用領域にだけ保存する**。サーバに送らない
  （`Context.filesDir` 配下なので、他のアプリからは読めない）
- **自動バックアップを切ってある**（`android:allowBackup="false"`）。
  既定のままだとGoogleドライブに軌跡ごとバックアップされてしまうため。
  そのぶん端末を初期化すると消えるので、下の「機種変更・バックアップ」を参照
- **xlsxにGPS座標を出力しない**。点数と距離だけなので、出力ファイルを
  他人に見せても移動経路そのものは分からない
- **地図の初期位置は東京駅**。自宅の座標をコードに埋め込んでいない
- **リポジトリに実データを入れない**。`*.xlsx` と端末バックアップは `.gitignore` 済み
- **「常に許可」の位置情報権限（ACCESS_BACKGROUND_LOCATION）は要求しない**。
  アプリを開いた状態から始めるフォアグラウンドサービスなら不要なため

---

## 4. ファイル構成

```
app/src/main/java/com/tuukinmemo/
├── model/Records.kt        区分・混雑度・判定・記録・入力途中(RecordDraft)の型
├── model/Commute.kt        所要時間・判定・検証・集計・距離。Android非依存の計算
├── data/RecordStore.kt     記録の保存（JSONファイル）
├── data/TrackRepository.kt 記録中のGPS軌跡（サービスと画面で共有）
├── data/DraftStore.kt      入力途中の保存
├── data/TrackJson.kt       軌跡とJSONの変換
├── data/SampleData.kt      動作確認用のサンプル生成（デバッグビルドのみ）
├── export/XlsxWriter.kt    xlsx生成（依存ライブラリなし）
├── location/TrackService.kt フォアグラウンドサービス（GPS記録）
├── ui/CommuteApp.kt        画面全体・下タブ・xlsx保存
├── ui/EntryScreen.kt       行き／帰りの入力
├── ui/SummaryScreen.kt     集計・一覧・詳細
├── ui/TrackMap.kt          osmdroidの地図
├── ui/Pickers.kt           日付・時刻の入力部品
├── MainViewModel.kt        画面が使う状態をまとめる
└── MainActivity.kt         入口

app/src/test/java/com/tuukinmemo/   ユニットテスト（端末不要）
```

設計上いちばん大事なのは **`model/` に Android の API を持ち込んでいない**こと。
計算と判定がここに閉じているので、端末なしでテストできるし、
画面に出る値とxlsxに書かれる値がズレようがない。

### 保存の形

```
（アプリ専用領域）/files/
├── records/records.json        記録の一覧（軌跡は含まない ＝ 一覧表示が軽い）
├── records/tracks/<id>.json    その記録のGPS軌跡
└── inprogress/track-<区分>.json 記録中の軌跡
```

Room（SQLite）ではなくJSONにしている理由は `data/RecordStore.kt` の冒頭に書いてある。
件数が数千件を超えて重くなったら、このクラスの関数の形を保ったまま
中身をRoomに差し替えればよい（呼び出し側は変えなくて済む）。

---

## 5. よく変えたくなるところ

### 判定の基準時刻

`model/Records.kt` の `Judgement` を直すだけでよい。
画面のバッジも一覧の色帯もxlsxの塗りつぶしも、すべてここを見ている。

```kotlin
ONTIME("ontime", "オンタイム", LocalTime.of(8, 30), ...),
WARN("warn", "注意", LocalTime.of(9, 0), ...),
LATE("late", "遅刻", null, ...),
```

### xlsxの列を増やす

`export/XlsxWriter.kt` の `columns` に1行足す。

```kotlin
Column("曜日", 8) { formatDateShort(it.date)... },
```

### 混雑度の選択肢

`model/Records.kt` の `Crowding` を編集する。入力画面のボタンはこの enum から作っている。

### GPSの間引き加減・電池の持ち

- 間引き: `model/Commute.kt` の `MIN_MOVE_METERS` / `MIN_INTERVAL_MILLIS`
- 測位の頻度: `location/TrackService.kt` の `UPDATE_INTERVAL_MILLIS`

---

## 6. テスト

```bash
./gradlew test
```

端末なしで走る27件のテストが入っている。

- `CommuteTest` … 日またぎの所要時間、判定の境界（8:29 / 8:30 / 8:59 / 9:00）、集計、GPSの間引き
- `XlsxWriterTest` … 自作xlsxがZIPとして開けるか、色分け、エスケープ、座標を出力していないこと
- `RecordStoreTest` … 保存・更新・削除、軌跡の分離、壊れたファイルからの復帰

### 確認できていること／できていないこと

このリポジトリを書いた環境にはAndroid SDKが無かったため、
**APKのビルドと実機での動作確認はできていない**。代わりに次を確認してある。

- ✅ `model/` `data/` `export/` のユニットテスト27件（実行して成功）
- ✅ 生成したxlsxを実際に開いて、色分け・数値型・日またぎ計算が正しいこと
- ✅ Compose以外のコード（サービス・ViewModel・保存層）がコンパイルできること
- ⚠️ Compose の画面まわりは、Android Studio で初めて本当のコンパイルが通る

最初のビルドで赤が出たら、まず `ui/` の中を疑ってほしい。

### サンプル記録を作る

デバッグビルドでは、集計画面の一番下に「［開発用］サンプル記録を10日ぶん作る」
というボタンが出る。実際に20回通う前に集計や色分けを確認できる。
このボタンは配布用（release）ビルドには入らない。

---

## 7. 機種変更・バックアップ

自動バックアップを切ってあるので、端末を初期化すると記録は消える。
移すときはどちらかで。

**xlsxで残す（手軽）**
集計画面から書き出してGoogleドライブなどに保存する。
ただしGPSの軌跡は含まれない。

**ファイルごと移す（完全）**
```bash
# 取り出す（USBデバッグを有効にして）
adb backup -f backup/tuukin.ab -noapk com.tuukinmemo

# 戻す
adb restore backup/tuukin.ab
```
※ `adb backup` は端末によっては使えない。その場合はデバッグビルドで
`adb shell run-as com.tuukinmemo tar c files` を使う手もある。

---

## 8. これから足せること

初期実装では「軌跡（座標ログ）を貯める」ところまでにしてある。
座標が残っているので、後からでも次のような分析を足せる。

1. **ルートの自動判定**（地下道 / サンシャイン通り など）
   判定したい経路の代表点をいくつか登録しておき、軌跡がその近くを通ったかで分類する。
   `model/Commute.kt` の `distanceMeters()` がそのまま使える
2. **区間ごとの所要時間**（自宅→駅、電車、駅→職場）。
   軌跡には時刻が入っているので、徒歩区間だけ切り出せる
3. **混雑度と所要時間の相関**を集計画面に出す
4. **ホーム画面ウィジェット**から「今」を押せるようにする（通勤中の操作が一番速くなる）
5. **記録が数千件を超えたら** `RecordStore` の中身をRoomに差し替える

---

## ライセンス / クレジット

- 地図: [osmdroid](https://github.com/osmdroid/osmdroid)（Apache License 2.0）
- タイル: © OpenStreetMap contributors
  （[利用条件](https://operations.osmfoundation.org/policies/tiles/)）
  個人利用の範囲を超えるアクセスをしないこと
