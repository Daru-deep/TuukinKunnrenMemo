package com.tuukinmemo.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * 通勤記録のデータ定義
 *
 * ここには Android の API を一切持ち込まない（java.time までにとどめる）。
 * そうしておくと、この層は端末なしでユニットテストを回せる。
 * 画面やGPSの都合はすべて外側（ui / location）に置く。
 */

/** 行き / 帰り */
enum class Direction(val id: String, val label: String, val arrivalLabel: String) {
    OUTBOUND("outbound", "行き", "職場ビル到着"),
    INBOUND("inbound", "帰り", "最寄り駅ホーム到着");

    companion object {
        fun fromId(id: String?): Direction? = entries.firstOrNull { it.id == id }
    }
}

/** 混雑度（4段階） */
enum class Crowding(val id: String, val label: String) {
    EMPTY("empty", "空いてる"),
    NORMAL("normal", "普通"),
    CROWDED("crowded", "混雑"),
    FULL("full", "満員");

    companion object {
        fun fromId(id: String?): Crowding? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 到着時刻の判定。
 *
 * `before` より前なら該当（最後の LATE は before = null ＝ それ以降すべて）。
 * 基準を変えたくなったらこの3行だけ直せば、画面の色もxlsxの塗りつぶしも追随する。
 *
 * @param colorArgb 画面で使う文字色
 * @param backgroundArgb 画面で使う背景色
 * @param fillArgb xlsx のセル塗りつぶし色（"FFC6EFCE" 形式）
 */
enum class Judgement(
    val id: String,
    val label: String,
    val before: LocalTime?,
    val colorArgb: Long,
    val backgroundArgb: Long,
    val fillArgb: String,
) {
    ONTIME("ontime", "オンタイム", LocalTime.of(8, 30), 0xFF15803D, 0xFFDCFCE7, "FFC6EFCE"),
    WARN("warn", "注意", LocalTime.of(9, 0), 0xFFA16207, 0xFFFEF9C3, "FFFFEB9C"),
    LATE("late", "遅刻", null, 0xFFB91C1C, 0xFFFEE2E2, "FFFFC7CE");

    companion object {
        /** 判定を適用する区分（＝行きの「職場到着」だけ）。帰りは参考記録なので判定しない。 */
        val JUDGED_DIRECTION = Direction.OUTBOUND

        fun of(direction: Direction, arrivalTime: LocalTime?): Judgement? {
            if (direction != JUDGED_DIRECTION || arrivalTime == null) return null
            return entries.first { it.before == null || arrivalTime < it.before }
        }
    }
}

/** GPSの1点 */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val timeMillis: Long,
    val accuracyMeters: Float,
)

/** 保存済みの1件 */
data class CommuteRecord(
    val id: String,
    val direction: Direction,
    val date: LocalDate,
    val departureTime: LocalTime,
    val trainTime: LocalTime?,
    val crowding: Crowding?,
    val delayed: Boolean,
    val arrivalTime: LocalTime,
    val detour: Boolean,
    val detourNote: String,
    val note: String,
    /** 軌跡そのものは別ファイルに置く。一覧を軽く保つため、ここには要約だけ持つ */
    val trackPoints: Int,
    val trackDistanceKm: Double,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val judgement: Judgement? get() = Judgement.of(direction, arrivalTime)
    val durationMinutes: Long get() = durationMinutesBetween(departureTime, arrivalTime)
}

/**
 * 入力途中の状態。
 *
 * 保存済みの CommuteRecord と分けているのは、入力中は
 * 「まだ時刻が入っていない」状態が正当だから（null を許す型にする）。
 * 画面はこれを編集し、保存のときだけ CommuteRecord に変換する。
 */
data class RecordDraft(
    val direction: Direction,
    /** 編集中の既存レコードid。新規なら null */
    val editingId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val departureTime: LocalTime? = null,
    val trainTime: LocalTime? = null,
    val crowding: Crowding? = null,
    val delayed: Boolean = false,
    val arrivalTime: LocalTime? = null,
    val detour: Boolean = false,
    val detourNote: String = "",
    val note: String = "",
) {
    val judgement: Judgement? get() = Judgement.of(direction, arrivalTime)

    /** 入力済みの範囲で計算できる所要時間（片方でも空なら null） */
    val durationMinutes: Long?
        get() {
            val from = departureTime ?: return null
            val to = arrivalTime ?: return null
            return durationMinutesBetween(from, to)
        }

    val isEditing: Boolean get() = editingId != null
}

/** 既存の記録を編集用の下書きに戻す */
fun CommuteRecord.toDraft(): RecordDraft = RecordDraft(
    direction = direction,
    editingId = id,
    date = date,
    departureTime = departureTime,
    trainTime = trainTime,
    crowding = crowding,
    delayed = delayed,
    arrivalTime = arrivalTime,
    detour = detour,
    detourNote = detourNote,
    note = note,
)
