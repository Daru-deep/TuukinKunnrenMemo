package com.tuukinmemo.export

import com.tuukinmemo.model.CommuteRecord
import com.tuukinmemo.model.Judgement
import com.tuukinmemo.model.formatDateShort
import com.tuukinmemo.model.formatDuration
import com.tuukinmemo.model.formatTime
import com.tuukinmemo.model.recordOrder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * xlsx の書き出し（外部ライブラリなし）
 *
 * xlsx の正体は「XMLを何枚かZIPで固めたもの」。
 * Android には java.util.zip.ZipOutputStream があるので、XMLさえ組み立てれば
 * Apache POI のような重いライブラリを入れなくても作れる
 * （POIはAndroidだとメソッド数もサイズも大きく、扱いづらい）。
 *
 * 中身:
 *   [Content_Types].xml        各パートの種類の宣言
 *   _rels/.rels                ルート → ブック
 *   xl/workbook.xml            シート一覧
 *   xl/_rels/workbook.xml.rels ブック → シート / スタイル
 *   xl/styles.xml              判定の色分け（塗りつぶし）
 *   xl/worksheets/sheet1.xml   セルの中身
 *
 * 文字列は sharedStrings.xml を使わず inlineStr で直接書く。
 * 数百行ならサイズ差は無視できて、コードがずっと短くなる。
 */
object XlsxWriter {

    /** 出力する列。ここに1行足せば列が増える。 */
    private val columns = listOf(
        Column("日付", 12) { it.date.toString() },
        Column("曜日", 8) { formatDateShort(it.date).substringAfter('(').trimEnd(')') },
        Column("区分", 8) { it.direction.label },
        // 起床・駅着は行きだけ。帰りの行は空欄になる
        Column("起床時刻", 10) { formatTime(it.wakeTime) },
        Column("出発時刻", 10) { formatTime(it.departureTime) },
        Column("自宅最寄り駅着", 14) { formatTime(it.homeStationTime) },
        Column("乗車電車", 10) { formatTime(it.trainTime) },
        Column("職場最寄り駅着", 14) { formatTime(it.workStationTime) },
        // 行きはビル到着、帰りは最寄り駅ホーム到着
        Column("到着時刻", 10) { formatTime(it.arrivalTime) },
        Column("所要時間(分)", 12, numeric = true) { it.durationMinutes },
        Column("所要時間", 12) { formatDuration(it.durationMinutes) },
        Column("混雑度", 10) { it.crowding?.label ?: "" },
        Column("遅延", 8) { if (it.delayed) "あり" else "なし" },
        Column("寄り道", 8) { if (it.detour) "あり" else "なし" },
        Column("寄り道メモ", 24) { it.detourNote },
        Column("判定", 12) { it.judgement?.label ?: "—" },
        Column("GPS点数", 10, numeric = true) { it.trackPoints },
        Column("GPS距離(km)", 12, numeric = true) { it.trackDistanceKm },
        Column("メモ", 28) { it.note },
    )

    private class Column(
        val label: String,
        val width: Int,
        val numeric: Boolean = false,
        val value: (CommuteRecord) -> Any?,
    )

    private const val HEADER_FILL = "FFDDEBF7"

    /**
     * 記録一覧の xlsx を作る。
     *
     * GPSの座標そのものは書き出さない（自宅の位置が分かるファイルを
     * うっかり誰かに送ってしまわないため）。点数と距離だけを載せる。
     */
    fun build(records: List<CommuteRecord>): ByteArray {
        val sorted = records.sortedWith(recordOrder)

        // 使う塗りつぶし色を集めてスタイル番号を割り当てる（0=通常, 1=見出し, 2..=判定色）
        val fills = buildList {
            add(HEADER_FILL)
            Judgement.entries.forEach { add(it.fillArgb) }
        }
        val styleOf = fills.withIndex().associate { (index, argb) -> argb to index + 1 }

        val sheet = buildString {
            append(XML_HEAD)
            append("""<worksheet xmlns="$NS_MAIN" xmlns:r="$NS_REL">""")
            append(
                """<sheetViews><sheetView workbookViewId="0">""" +
                    """<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""" +
                    """</sheetView></sheetViews>""",
            )
            append("""<sheetFormatPr defaultRowHeight="18"/>""")
            append("<cols>")
            columns.forEachIndexed { index, column ->
                append("""<col min="${index + 1}" max="${index + 1}" width="${column.width}" customWidth="1"/>""")
            }
            append("</cols><sheetData>")

            // 見出し行
            append("""<row r="1">""")
            columns.forEachIndexed { index, column ->
                append(cell(1, index, column.label, numeric = false, style = styleOf.getValue(HEADER_FILL)))
            }
            append("</row>")

            // データ行（判定の色で行全体を塗る）
            sorted.forEachIndexed { rowIndex, record ->
                val rowNumber = rowIndex + 2
                val style = record.judgement?.let { styleOf.getValue(it.fillArgb) } ?: 0
                append("""<row r="$rowNumber">""")
                columns.forEachIndexed { index, column ->
                    append(cell(rowNumber, index, column.value(record), column.numeric, style))
                }
                append("</row>")
            }

            append("</sheetData>")
            append("""<autoFilter ref="A1:${columnLetter(columns.size - 1)}${sorted.size + 1}"/>""")
            append("</worksheet>")
        }

        return zip(
            "[Content_Types].xml" to CONTENT_TYPES,
            "_rels/.rels" to ROOT_RELS,
            "xl/workbook.xml" to workbookXml("通勤記録"),
            "xl/_rels/workbook.xml.rels" to WORKBOOK_RELS,
            "xl/styles.xml" to stylesXml(fills),
            "xl/worksheets/sheet1.xml" to sheet,
        )
    }

    /** 提案するファイル名（保存ダイアログの初期値） */
    fun suggestedFileName(today: java.time.LocalDate = java.time.LocalDate.now()): String =
        "通勤記録_$today.xlsx"

    // -- 組み立ての部品 ------------------------------------------------------

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun cell(row: Int, columnIndex: Int, value: Any?, numeric: Boolean, style: Int): String {
        val ref = "${columnLetter(columnIndex)}$row"
        val styleAttr = if (style > 0) """ s="$style"""" else ""
        if (value == null || value == "") return """<c r="$ref"$styleAttr/>"""
        if (numeric && value is Number) return """<c r="$ref"$styleAttr><v>$value</v></c>"""
        return """<c r="$ref"$styleAttr t="inlineStr"><is><t xml:space="preserve">""" +
            escapeXml(value.toString()) + "</t></is></c>"
    }

    /** 0 -> "A", 25 -> "Z", 26 -> "AA" */
    fun columnLetter(index: Int): String {
        var n = index + 1
        val letters = StringBuilder()
        while (n > 0) {
            val rest = (n - 1) % 26
            letters.insert(0, ('A' + rest))
            n = (n - 1) / 26
        }
        return letters.toString()
    }

    private fun escapeXml(value: String): String = buildString {
        for (character in value) {
            when {
                character == '&' -> append("&amp;")
                character == '<' -> append("&lt;")
                character == '>' -> append("&gt;")
                character == '"' -> append("&quot;")
                character == '\'' -> append("&apos;")
                // XMLに入れられない制御文字は落とす（タブ・改行は残す）
                character.code < 0x20 && character != '\t' && character != '\n' && character != '\r' -> Unit
                else -> append(character)
            }
        }
    }

    // -- 固定のXMLパーツ -----------------------------------------------------

    private const val XML_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private val CONTENT_TYPES = XML_HEAD + """
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private val ROOT_RELS = XML_HEAD + """
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="$NS_REL/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private val WORKBOOK_RELS = XML_HEAD + """
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="$NS_REL/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="$NS_REL/styles" Target="styles.xml"/>
</Relationships>"""

    private fun workbookXml(sheetName: String) = XML_HEAD + """
<workbook xmlns="$NS_MAIN" xmlns:r="$NS_REL">
<sheets><sheet name="${escapeXml(sheetName.take(31))}" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    /**
     * fills の 0番と1番は仕様上「none」「gray125」で予約されているので、
     * 実際に使う色は2番以降に並べる。cellXfs の番号もそれに合わせる。
     */
    private fun stylesXml(fills: List<String>) = XML_HEAD + """
<styleSheet xmlns="$NS_MAIN">
<fonts count="2">
<font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>
<font><b/><sz val="11"/><color theme="1"/><name val="Calibri"/></font>
</fonts>
<fills count="${fills.size + 2}">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
${fills.joinToString("\n") {
        """<fill><patternFill patternType="solid"><fgColor rgb="$it"/><bgColor indexed="64"/></patternFill></fill>"""
    }}
</fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="${fills.size + 1}">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
${fills.mapIndexed { index, _ ->
        """<xf numFmtId="0" fontId="${if (index == 0) 1 else 0}" fillId="${index + 2}" borderId="0" xfId="0" applyFont="1" applyFill="1"/>"""
    }.joinToString("\n")}
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
