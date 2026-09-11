package com.tuukinmemo

import com.tuukinmemo.export.XlsxWriter
import com.tuukinmemo.model.CommuteRecord
import com.tuukinmemo.model.Crowding
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.Judgement
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 自作したxlsxが本当にxlsxの形になっているかを、ZIPを開き直して確認する */
class XlsxWriterTest {

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private fun record(
        date: String,
        departure: String,
        arrival: String,
        direction: Direction = Direction.OUTBOUND,
        note: String = "",
        trackPoints: Int = 0,
        trackDistanceKm: Double = 0.0,
    ) = CommuteRecord(
        id = "$date-$departure",
        direction = direction,
        date = LocalDate.parse(date),
        departureTime = LocalTime.parse(departure),
        trainTime = LocalTime.parse("07:58"),
        crowding = Crowding.FULL,
        delayed = true,
        arrivalTime = LocalTime.parse(arrival),
        detour = false,
        detourNote = "",
        note = note,
        trackPoints = trackPoints,
        trackDistanceKm = trackDistanceKm,
        createdAt = 0,
        updatedAt = 0,
    )

    private val records = listOf(
        record("2026-08-03", "07:45", "08:20", note = "a & b <c>", trackPoints = 120, trackDistanceKm = 3.46),
        record("2026-08-04", "07:45", "08:40"),
        record("2026-08-05", "07:45", "09:10"),
        record("2026-08-05", "23:50", "00:35", direction = Direction.INBOUND),
    )

    @Test
    fun `xlsxはZIPとして開けて必要なパートが揃っている`() {
        val bytes = XlsxWriter.build(records)
        assertEquals("PK", String(bytes.copyOfRange(0, 2)), "ZIPのマジックナンバー")

        val files = unzip(bytes)
        listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml",
        ).forEach { assertTrue(it in files, "$it が無い") }

        assertTrue(files.getValue("xl/workbook.xml").contains("""name="通勤記録""""))
    }

    @Test
    fun `見出し・行数・所要時間・エスケープ`() {
        val sheet = unzip(XlsxWriter.build(records)).getValue("xl/worksheets/sheet1.xml")

        assertTrue(sheet.contains(">日付<"), "見出し行がある")
        assertEquals(records.size + 1, Regex("<row ").findAll(sheet).count(), "見出し＋データ行")

        // 所要時間は数値セル（Excel側で平均などを取れるように文字列にしない）
        assertTrue(sheet.contains("<v>35</v>"))
        assertTrue(sheet.contains("<v>45</v>"), "日をまたぐ帰りも45分として出る")

        assertTrue(sheet.contains("a &amp; b &lt;c&gt;"), "XMLエスケープ")
    }

    @Test
    fun `判定ごとに行の色が変わり、帰りは色を塗らない`() {
        val sheet = unzip(XlsxWriter.build(records)).getValue("xl/worksheets/sheet1.xml")
        val styles = Regex("""<row r="(\d+)">(?:(?!</row>).)*?s="(\d+)"""")
            .findAll(sheet)
            .map { it.groupValues[2] }
            .toList()

        // 1=見出し, 2=オンタイム, 3=注意, 4=遅刻（Judgementの並び順どおり）
        assertEquals(listOf("1", "2", "3", "4"), styles)

        val lastRow = Regex("""<row r="5">.*?</row>""").find(sheet)!!.value
        assertFalse(lastRow.contains("s=\""), "帰りの行は色分けしない")
    }

    @Test
    fun `起床と駅着の列が出る`() {
        val outbound = record("2026-09-11", "07:40", "08:36").copy(
            wakeTime = LocalTime.parse("06:50"),
            homeStationTime = LocalTime.parse("07:46"),
            workStationTime = LocalTime.parse("08:28"),
        )
        val sheet = unzip(XlsxWriter.build(listOf(outbound))).getValue("xl/worksheets/sheet1.xml")

        listOf("起床時刻", "自宅最寄り駅着", "職場最寄り駅着").forEach {
            assertTrue(sheet.contains(">$it<"), "見出し $it")
        }
        listOf("06:50", "07:46", "08:28").forEach {
            assertTrue(sheet.contains(">$it<"), "値 $it")
        }
    }

    @Test
    fun `GPS座標そのものは出力しない（位置が分かるファイルを配らないため）`() {
        val sheet = unzip(XlsxWriter.build(records)).getValue("xl/worksheets/sheet1.xml")
        assertFalse(sheet.contains("35.68"))
        assertFalse(sheet.contains("139.76"))
        assertTrue(sheet.contains("<v>120</v>"), "点数は出す")
        assertTrue(sheet.contains("<v>3.46</v>"), "距離は出す")
    }

    @Test
    fun `記録ゼロでも壊れない`() {
        val files = unzip(XlsxWriter.build(emptyList()))
        assertTrue(files.getValue("xl/worksheets/sheet1.xml").contains("""<row r="1">"""))
    }

    @Test
    fun `判定の色定義がスタイルに全部含まれる`() {
        val styles = unzip(XlsxWriter.build(records)).getValue("xl/styles.xml")
        Judgement.entries.forEach { assertTrue(styles.contains(it.fillArgb), "${it.label}の色が無い") }
    }

    @Test
    fun `列番号の変換`() {
        assertEquals("A", XlsxWriter.columnLetter(0))
        assertEquals("Z", XlsxWriter.columnLetter(25))
        assertEquals("AA", XlsxWriter.columnLetter(26))
        assertEquals("AB", XlsxWriter.columnLetter(27))
    }
}
