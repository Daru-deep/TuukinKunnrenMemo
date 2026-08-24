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

### 端末の設定

記録を最後まで続けるには、権限のほかに端末側の設定がいくつか要る。
足りないものがあると、行き／帰りの画面のいちばん上に赤いカードが出て、
その場から設定画面へ飛べる。そろっていればカードは出ない
（集計画面の下では、そろっていても一覧で確認できる）。

| 見るもの | 足りないと起きること |
| --- | --- |
| 位置情報の権限 | そもそも記録できない |
| 位置情報（GPS）がON | 権限があっても測位できない |
| 通知の権限 | 記録中の通知が出ず、止め忘れに気づけない |
| 電池の最適化から除外 | 画面を消している間にOSがサービスを止める |

判定は `ui/DeviceSetup.kt` の `SetupItem` にまとまっている。項目を足すときはここ。
画面が前に戻るたび（`ON_RESUME`）に見直すので、設定画面から帰ってくると表示が更新される。

> **電池の最適化について。** 「設定」を押すと
> `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` を投げるが、
> メーカーによっては独自の省電力画面が開く（Xiaomi/HyperOSでは
> アプリごとの「バッテリー詳細」画面。そこの **制限なし** を選ぶ）。
> なお `PowerManager.isIgnoringBatteryOptimizations()` が見ているのは
> Android標準の除外リストなので、メーカー独自の省電力設定を変えても
> カードが消えないことがある。その場合は標準の除外も行うこと。

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
├── ui/DeviceSetup.kt       端末側の設定（権限・GPS・電池）の点検と案内
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

初版はAndroid SDKの無い環境で書いたため実機確認ができていなかったが、
Xiaomi XIG07（HyperOS 3.0 / Android 16, API 36）で通しで確認した。

- ✅ ユニットテスト27件
- ✅ xlsxを実際に開いて、色分け・数値型・日またぎ計算が正しいこと
- ✅ `./gradlew installDebug` でのビルドとインストール（Composeも含めて赤なし）
- ✅ サンプル記録の生成、集計・最短ルートの地図・一覧の色分け
- ✅ 地図タイルの表示（osmdroidのユーザーエージェント設定で問題なし）
- ✅ xlsx書き出し（保存先選択→Excelで開ける、座標が入っていないこと）
- ✅ 一覧→詳細→編集→更新
- ✅ フォアグラウンドサービスが画面OFFのままGPSリクエストを保持し続けること
- ✅ 判定3色（オンタイム／注意／遅刻）の表示
- ⚠️ 実際に通勤しながらの長時間記録は、まだ通しで取っていない

実機で初めて出た不具合が2件あった。どちらも `ui/TrackMap.kt`（地図）で、
`AndroidView` と osmdroid の組み合わせに由来する。コミット
`実機で出た地図まわりの2件を直す` に経緯を書いてある。

- `AndroidView` の `update` は再コンポーズのたびに呼ばれる。地図の作り直しを
  毎回やると重い処理が積み上がってANRになる
- osmdroid は半端な拡大率をキャンバスの拡大で描き、View の外にはみ出す分を
  自分では切らない。`clipToOutline` が要る

**端末の設定に注意。** Xiaomi/HyperOS は開発者向けオプションの
「USB経由でインストール」がOFFだと `installDebug` が
`INSTALL_FAILED_USER_RESTRICTED` で失敗する。
`adb shell input` で操作を送りたい場合は「USBデバッグ（セキュリティ設定）」も要る。

### サンプル記録を作る

デバッグビルドでは、集計画面の**「記録一覧」の見出しを長押し**すると
「［開発用］サンプル記録を10日ぶん作る」が現れる。実際に20回通う前に
集計や色分けを確認できる。押すと確認ダイアログが出る。

常に出しっぱなしにしていないのは、サンプルが実際の記録と同じ一覧に並ぶため。
一度混ざると `note` を見ないと区別できず、消すのに手間がかかる。
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
