package com.tuukinmemo.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 通勤記録の計算ロジック（画面にもxlsxにも使う唯一の実装）
 *
 * ここを1か所に集めておくと、「画面に出ている所要時間」と
 * 「xlsxに書かれた所要時間」がズレようがない。
 * すべて純粋関数なので、端末なしでテストできる（app/src/test を参照）。
 */

private const val MINUTES_PER_DAY = 24 * 60L

/**
 * 出発から到着までの分数。
 * 到着が出発より前なら日をまたいだ（例: 23:50 → 00:20）とみなして足し戻す。
 * 帰りが深夜になるケースを素直に扱うための仕様。
 */
fun durationMinutesBetween(departure: LocalTime, arrival: LocalTime): Long {
    val minutes = Duration.between(departure, arrival).toMinutes()
    return if (minutes < 0) minutes + MINUTES_PER_DAY else minutes
}

/** 55 -> "55分" / 75 -> "1時間15分" */
fun formatDuration(minutes: Long?): String {
    if (minutes == null) return "—"
    if (minutes < 60) return "${minutes}分"
    return "${minutes / 60}時間${(minutes % 60).toString().padStart(2, '0')}分"
}

private val WEEKDAY_LABELS = listOf("月", "火", "水", "木", "金", "土", "日")

/** 2026-08-21 -> "8/21(金)" */
fun formatDateShort(date: LocalDate): String =
    "${date.monthValue}/${date.dayOfMonth}(${WEEKDAY_LABELS[date.dayOfWeek.value - 1]})"

/** LocalTime -> "07:45"（null なら空文字） */
fun formatTime(time: LocalTime?): String =
    time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: ""

/** "07:45" -> LocalTime（壊れた値なら null） */
fun parseTime(text: String?): LocalTime? {
    val match = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""").find(text?.trim().orEmpty()) ?: return null
    val (hour, minute) = match.destructured
    return LocalTime.of(hour.toInt(), minute.toInt())
}

// ---------------------------------------------------------------------------
// 入力の検証
// ---------------------------------------------------------------------------

/**
 * 保存できる状態かを調べ、足りないものを日本語で返す。
 * 空リストなら保存してよい。
 */
fun validateDraft(draft: RecordDraft): List<String> = buildList {
    if (draft.departureTime == null) add("出発時刻を入力してください")
    if (draft.arrivalTime == null) add("${draft.direction.arrivalLabel}を入力してください")
    if (draft.date.year < 2000) add("日付が不正です")
}

/**
 * 下書きを保存用の記録に変換する。
 * 検証を通っていない下書きを渡すと例外になるので、必ず validateDraft を先に通すこと。
 */
fun RecordDraft.toRecord(
    id: String,
    trackPoints: Int,
    trackDistanceKm: Double,
    createdAt: Long,
    updatedAt: Long,
): CommuteRecord {
    val errors = validateDraft(this)
    require(errors.isEmpty()) { errors.joinToString(" / ") }
    // 起床・駅着は行きだけの項目。帰りの記録には持たせない
    val stages = direction.stageTimes
    return CommuteRecord(
        id = id,
        direction = direction,
        date = date,
        wakeTime = wakeTime.takeIf { stages },
        departureTime = departureTime!!,
        homeStationTime = homeStationTime.takeIf { stages },
        trainTime = trainTime,
        workStationTime = workStationTime.takeIf { stages },
        crowding = crowding,
        delayed = delayed,
        arrivalTime = arrivalTime!!,
        detour = detour,
        // 「寄り道なし」に戻したときにメモだけ残らないようにする
        detourNote = if (detour) detourNote.trim() else "",
        note = note.trim(),
        trackPoints = trackPoints,
        trackDistanceKm = trackDistanceKm,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

// ---------------------------------------------------------------------------
// 集計
// ---------------------------------------------------------------------------

data class DirectionSummary(
    val count: Int,
    val averageMinutes: Double?,
    val fastest: CommuteRecord?,
    val slowest: CommuteRecord?,
    val judgementCounts: Map<Judgement, Int>,
)

data class Summary(
    val total: Int,
    val byDirection: Map<Direction, DirectionSummary>,
) {
    operator fun get(direction: Direction): DirectionSummary = byDirection.getValue(direction)
}

/**
 * 区分ごとの集計。件数に比例した計算しかしていないので、
 * 記録が20件でも2000件でも同じように動く。
 */
fun summarize(records: List<CommuteRecord>): Summary {
    val byDirection = Direction.entries.associateWith { direction ->
        val target = records.filter { it.direction == direction }
        DirectionSummary(
            count = target.size,
            averageMinutes = target.map { it.durationMinutes }.average().takeIf { target.isNotEmpty() },
            fastest = target.minByOrNull { it.durationMinutes },
            slowest = target.maxByOrNull { it.durationMinutes },
            judgementCounts = Judgement.entries.associateWith { judgement ->
                target.count { it.judgement == judgement }
            },
        )
    }
    return Summary(total = records.size, byDirection = byDirection)
}

/** 一覧表示・出力の並び順（日付 → 出発時刻 → id） */
val recordOrder: Comparator<CommuteRecord> =
    compareBy({ it.date }, { it.departureTime }, { it.id })

// ---------------------------------------------------------------------------
// GPS軌跡
// ---------------------------------------------------------------------------

/** 2点間の距離（メートル）。地球を半径6371kmの球とみなす簡易計算。 */
fun distanceMeters(a: TrackPoint, b: TrackPoint): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * earthRadius * asin(min(1.0, sqrt(h)))
}

/** 軌跡の総距離（メートル） */
fun trackDistanceMeters(points: List<TrackPoint>): Double =
    points.zipWithNext { a, b -> distanceMeters(a, b) }.sum()

/** 軌跡の総距離（km、小数3桁） */
fun trackDistanceKm(points: List<TrackPoint>): Double =
    (trackDistanceMeters(points) / 1000.0 * 1000).roundToInt() / 1000.0

// ---------------------------------------------------------------------------
// GPSの点をどれだけ残すか
// ---------------------------------------------------------------------------

/** これ以上動いていれば残す（メートル） */
const val MIN_MOVE_METERS = 5.0

/** 動いていなくても、この時間が経っていれば残す（ミリ秒） */
const val MIN_INTERVAL_MILLIS = 5_000L

/** これより誤差が大きい測位は使わない（メートル）。地下や駅構内で大きく飛ぶため */
const val MAX_ACCURACY_METERS = 100f

/**
 * 受け取った測位を軌跡に残すかどうか。
 *
 * 位置情報は1秒に1回以上届くので、そのまま貯めると1時間で数千点になる。
 * 「5m以上動いた」か「5秒以上経った」ものだけ残して、
 * 信号待ちの間に同じ場所の点が溜まらないようにする。
 *
 * サービスから切り離した純粋関数にしてあるので、端末なしでテストできる。
 */
fun shouldKeepPoint(previous: TrackPoint?, candidate: TrackPoint): Boolean {
    if (candidate.accuracyMeters > MAX_ACCURACY_METERS) return false
    if (previous == null) return true
    val moved = distanceMeters(previous, candidate)
    val elapsed = candidate.timeMillis - previous.timeMillis
    return moved >= MIN_MOVE_METERS || elapsed >= MIN_INTERVAL_MILLIS
}
