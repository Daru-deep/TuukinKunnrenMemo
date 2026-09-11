package com.tuukinmemo

import com.tuukinmemo.model.CommuteRecord
import com.tuukinmemo.model.Crowding
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.Judgement
import com.tuukinmemo.model.RecordDraft
import com.tuukinmemo.model.TrackPoint
import com.tuukinmemo.model.durationMinutesBetween
import com.tuukinmemo.model.shouldKeepPoint
import com.tuukinmemo.model.formatDateShort
import com.tuukinmemo.model.formatDuration
import com.tuukinmemo.model.parseTime
import com.tuukinmemo.model.summarize
import com.tuukinmemo.model.toDraft
import com.tuukinmemo.model.toRecord
import com.tuukinmemo.model.trackDistanceMeters
import com.tuukinmemo.model.validateDraft
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 計算ロジックのテスト（端末不要。Android Studioでも `./gradlew test` で走る） */
class CommuteTest {

    private fun time(text: String) = LocalTime.parse(text)

    @Test
    fun `所要時間は日をまたいでも正しい`() {
        assertEquals(55, durationMinutesBetween(time("07:45"), time("08:40")))
        assertEquals(30, durationMinutesBetween(time("23:50"), time("00:20")), "深夜の帰り")
        assertEquals(0, durationMinutesBetween(time("08:00"), time("08:00")))
    }

    @Test
    fun `所要時間の表示`() {
        assertEquals("55分", formatDuration(55))
        assertEquals("1時間15分", formatDuration(75))
        assertEquals("2時間00分", formatDuration(120))
        assertEquals("—", formatDuration(null))
    }

    @Test
    fun `日付は曜日つきで表示する`() {
        assertEquals("8/21(金)", formatDateShort(LocalDate.of(2026, 8, 21)))
    }

    @Test
    fun `時刻の解析`() {
        assertEquals(time("07:45"), parseTime("07:45"))
        assertNull(parseTime("7:45"), "ゼロ埋めなしは不正")
        assertNull(parseTime("24:00"))
        assertNull(parseTime(""))
        assertNull(parseTime(null))
    }

    @Test
    fun `判定は行きの到着時刻だけに適用される（境界値）`() {
        fun judge(text: String, direction: Direction = Direction.OUTBOUND) =
            Judgement.of(direction, time(text))

        assertEquals(Judgement.ONTIME, judge("08:29"))
        assertEquals(Judgement.WARN, judge("08:30"), "8:30ちょうどは注意")
        assertEquals(Judgement.WARN, judge("08:59"))
        assertEquals(Judgement.LATE, judge("09:00"), "9:00ちょうどは遅刻")
        assertEquals(Judgement.LATE, judge("12:00"))
        assertEquals(Judgement.ONTIME, judge("05:00"))
        assertNull(judge("19:00", Direction.INBOUND), "帰りは判定しない")
        assertNull(Judgement.of(Direction.OUTBOUND, null))
    }

    @Test
    fun `未入力の下書きは保存できない`() {
        val draft = RecordDraft(direction = Direction.OUTBOUND)
        val errors = validateDraft(draft)
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.contains("出発時刻") })
        assertTrue(errors.any { it.contains("到着時刻") })
        assertFailsWith<IllegalArgumentException> {
            draft.toRecord("id", 0, 0.0, 0, 0)
        }
    }

    @Test
    fun `下書きから記録への変換で余計な値を落とす`() {
        val draft = RecordDraft(
            direction = Direction.OUTBOUND,
            date = LocalDate.of(2026, 8, 21),
            departureTime = time("07:45"),
            arrivalTime = time("08:25"),
            detour = false,
            detourNote = "寄り道なしに戻したときの消し忘れ",
            note = "  余裕あり  ",
        )
        assertTrue(validateDraft(draft).isEmpty())

        val record = draft.toRecord("id", 10, 1.5, 100, 200)
        assertEquals("", record.detourNote, "寄り道なしならメモは残さない")
        assertEquals("余裕あり", record.note, "前後の空白は落とす")
        assertEquals(40, record.durationMinutes)
        assertEquals(Judgement.ONTIME, record.judgement)
    }

    @Test
    fun `記録を編集用の下書きに戻せる`() {
        val record = sample("2026-08-21", "07:45", "08:25")
        val draft = record.toDraft()
        assertEquals(record.id, draft.editingId)
        assertTrue(draft.isEditing)
        assertEquals(record.arrivalTime, draft.arrivalTime)
        assertEquals(40, draft.durationMinutes)
    }

    @Test
    fun `起床と駅着は行きだけ記録に残り、所要時間と判定はビル到着のまま`() {
        val draft = RecordDraft(
            direction = Direction.OUTBOUND,
            date = LocalDate.of(2026, 9, 11),
            wakeTime = time("06:50"),
            departureTime = time("07:40"),
            homeStationTime = time("07:46"),
            trainTime = time("07:52"),
            workStationTime = time("08:28"),
            arrivalTime = time("08:36"),
        )

        val outbound = draft.toRecord("a", 0, 0.0, 0, 0)
        assertEquals(time("06:50"), outbound.wakeTime)
        assertEquals(time("07:46"), outbound.homeStationTime)
        assertEquals(time("08:28"), outbound.workStationTime)
        assertEquals(56, outbound.durationMinutes, "所要時間は出発→ビル到着")
        assertEquals(Judgement.WARN, outbound.judgement, "判定はビル到着の8:36")
        assertEquals(time("06:50"), outbound.toDraft().wakeTime, "編集に戻しても残る")

        val inbound = draft.copy(direction = Direction.INBOUND).toRecord("b", 0, 0.0, 0, 0)
        assertNull(inbound.wakeTime, "帰りには持たせない")
        assertNull(inbound.homeStationTime)
        assertNull(inbound.workStationTime)
    }

    @Test
    fun `到着が空のときは欄の名前で知らせる`() {
        val errors = validateDraft(RecordDraft(direction = Direction.OUTBOUND, departureTime = time("07:40")))
        assertEquals(listOf("ビル到着時刻を入力してください"), errors)
    }

    private fun sample(
        date: String,
        departure: String,
        arrival: String,
        direction: Direction = Direction.OUTBOUND,
    ) = CommuteRecord(
        id = "$date-$departure-${direction.id}",
        direction = direction,
        date = LocalDate.parse(date),
        departureTime = time(departure),
        trainTime = null,
        crowding = Crowding.NORMAL,
        delayed = false,
        arrivalTime = time(arrival),
        detour = false,
        detourNote = "",
        note = "",
        trackPoints = 0,
        trackDistanceKm = 0.0,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `集計は区分ごとに平均・最短・判定内訳を出す`() {
        val records = listOf(
            sample("2026-08-03", "07:45", "08:40"), // 55分 / 注意
            sample("2026-08-04", "07:40", "08:35"), // 55分 / 注意
            sample("2026-08-05", "07:30", "08:20"), // 50分 / オンタイム
            sample("2026-08-05", "17:30", "18:40", Direction.INBOUND), // 70分
        )
        val summary = summarize(records)

        assertEquals(4, summary.total)
        val outbound = summary[Direction.OUTBOUND]
        assertEquals(3, outbound.count)
        assertEquals(53.3, outbound.averageMinutes!!, 0.05)
        assertEquals(50, outbound.fastest!!.durationMinutes)
        assertEquals(55, outbound.slowest!!.durationMinutes)
        assertEquals(1, outbound.judgementCounts[Judgement.ONTIME])
        assertEquals(2, outbound.judgementCounts[Judgement.WARN])
        assertEquals(0, outbound.judgementCounts[Judgement.LATE])

        val inbound = summary[Direction.INBOUND]
        assertEquals(1, inbound.count)
        assertEquals(70.0, inbound.averageMinutes!!, 0.001)
    }

    @Test
    fun `記録ゼロでも集計は落ちない`() {
        val summary = summarize(emptyList())
        assertNull(summary[Direction.OUTBOUND].averageMinutes)
        assertNull(summary[Direction.OUTBOUND].fastest)
        assertEquals(0, summary.total)
    }

    @Test
    fun `軌跡の距離は緯度0点009でおよそ1km`() {
        val points = listOf(
            TrackPoint(35.681236, 139.767125, 0, 5f),
            TrackPoint(35.690236, 139.767125, 1000, 5f),
        )
        assertTrue(trackDistanceMeters(points) in 990.0..1010.0)
        assertEquals(0.0, trackDistanceMeters(emptyList()))
        assertEquals(0.0, trackDistanceMeters(points.take(1)))
    }

    @Test
    fun `GPSの点は間引く`() {
        val base = TrackPoint(35.7295, 139.7109, 1_000_000, 10f)

        assertTrue(shouldKeepPoint(null, base), "最初の点は必ず残す")

        // ほとんど動いていない＆間隔も短い → 捨てる
        val stayed = base.copy(timeMillis = base.timeMillis + 1_000)
        assertFalse(shouldKeepPoint(base, stayed))

        // 時間が経てば残す（同じ場所でも「そこに居た」記録になる）
        assertTrue(shouldKeepPoint(base, base.copy(timeMillis = base.timeMillis + 5_000)))

        // 5m以上動いていれば、すぐでも残す（緯度0.0001度 ≒ 11m）
        val moved = base.copy(lat = base.lat + 0.0001, timeMillis = base.timeMillis + 500)
        assertTrue(shouldKeepPoint(base, moved))

        // 誤差が大きすぎる測位は使わない
        assertFalse(shouldKeepPoint(base, moved.copy(accuracyMeters = 150f)))
        assertFalse(shouldKeepPoint(null, base.copy(accuracyMeters = 150f)))
    }
}
