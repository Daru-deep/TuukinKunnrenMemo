package com.tuukinmemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tuukinmemo.model.CommuteRecord
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.Judgement
import com.tuukinmemo.model.Summary
import com.tuukinmemo.model.TrackPoint
import com.tuukinmemo.model.formatDateShort
import com.tuukinmemo.model.formatDuration
import com.tuukinmemo.model.formatTime
import java.time.LocalTime
import kotlin.math.roundToLong

/**
 * 集計・出力画面
 *
 * 画面全体を1つの LazyColumn にしている。
 * カードと一覧を別々のスクロールにすると、記録が増えたときに
 * 「一覧の中だけスクロールする」使いにくい画面になるため。
 */
@Composable
fun SummaryScreen(
    records: List<CommuteRecord>,
    summary: Summary,
    loadTrack: suspend (String) -> List<TrackPoint>,
    onEdit: (CommuteRecord) -> Unit,
    onDelete: (CommuteRecord) -> Unit,
    onExport: () -> Unit,
    /** デバッグビルドのときだけ渡される。サンプル記録を作るボタンを出す */
    onCreateSampleData: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var detail by remember { mutableStateOf<CommuteRecord?>(null) }

    // サンプル記録のボタンは普段は隠しておく。
    // 実際の記録に混ざると見分けがつかなくなるうえ、消すのが手間なので、
    // 「記録一覧」を長押ししたときだけ出す。
    var devVisible by remember { mutableStateOf(false) }
    var confirmSamples by remember { mutableStateOf(false) }
    val setup = rememberDeviceSetupState()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Direction.entries.forEach { direction ->
                    StatCard(
                        direction = direction,
                        summary = summary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            FastestRouteCard(summary = summary, loadTrack = loadTrack)
        }

        item {
            Text(
                "記録一覧",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.pointerInput(onCreateSampleData) {
                    // デバッグビルドでだけ反応する隠し操作
                    if (onCreateSampleData != null) {
                        detectTapGestures(onLongPress = { devVisible = true })
                    }
                },
            )
        }

        if (records.isEmpty()) {
            item {
                Text(
                    "まだ記録がありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }

        // 新しい記録を上に出す（直近の結果をすぐ確認したいため）
        items(records.reversed(), key = { it.id }) { record ->
            RecordRow(record = record, onClick = { detail = record })
        }

        item {
            ExportCard(count = records.size, onExport = onExport)
        }

        // 端末の設定は、足りていても一覧で確認できるようにしておく
        item {
            DeviceSetupCard(state = setup, alwaysShow = true, modifier = Modifier.fillMaxWidth())
        }

        if (onCreateSampleData != null && devVisible) {
            item {
                TextButton(
                    onClick = { confirmSamples = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("［開発用］サンプル記録を10日ぶん作る")
                }
            }
        }
    }

    if (confirmSamples && onCreateSampleData != null) {
        AlertDialog(
            onDismissRequest = { confirmSamples = false },
            title = { Text("サンプル記録を作りますか？") },
            text = {
                Text(
                    "動作確認用の記録を20件（10日ぶん）追加します。" +
                        "実際の記録と同じ一覧に並ぶので、あとで消すのが手間になります。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSamples = false
                    onCreateSampleData()
                }) { Text("作る") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSamples = false }) { Text("やめる") }
            },
        )
    }

    detail?.let { record ->
        RecordDetailDialog(
            record = record,
            loadTrack = loadTrack,
            onDismiss = { detail = null },
            onEdit = {
                detail = null
                onEdit(record)
            },
            onDelete = {
                detail = null
                onDelete(record)
            },
        )
    }
}

@Composable
private fun StatCard(direction: Direction, summary: Summary, modifier: Modifier = Modifier) {
    val stats = summary[direction]
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${direction.label}の平均（${stats.count}件）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDuration(stats.averageMinutes?.roundToLong()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "最短 " + stats.fastest?.let {
                    "${formatDuration(it.durationMinutes)}（${formatDateShort(it.date)}）"
                }.orEmpty().ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "最長 " + (stats.slowest?.let { formatDuration(it.durationMinutes) } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
            )

            // 判定は行きだけ（帰りは参考記録なので内訳を出さない）
            if (direction == Judgement.JUDGED_DIRECTION) {
                JudgementCounts(stats.judgementCounts)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JudgementCounts(counts: Map<Judgement, Int>) {
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Judgement.entries.forEach { judgement ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(judgement.backgroundArgb))) {
                Text(
                    "${judgement.label} ${counts[judgement] ?: 0}",
                    color = Color(judgement.colorArgb),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** 最短記録のルートを地図で見せる */
@Composable
private fun FastestRouteCard(summary: Summary, loadTrack: suspend (String) -> List<TrackPoint>) {
    val fastest = summary[Direction.OUTBOUND].fastest ?: summary[Direction.INBOUND].fastest
    var track by remember(fastest?.id) { mutableStateOf<List<TrackPoint>>(emptyList()) }

    LaunchedEffect(fastest?.id) {
        track = fastest?.let { loadTrack(it.id) }.orEmpty()
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最短記録のルート", style = MaterialTheme.typography.titleMedium)
            if (fastest == null) {
                Text("記録がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            Text(
                "${formatDateShort(fastest.date)} ${fastest.direction.label} " +
                    "${formatTime(fastest.departureTime)} → ${formatTime(fastest.arrivalTime)}" +
                    "（${formatDuration(fastest.durationMinutes)}）",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (track.isEmpty()) {
                Text(
                    "この記録にはGPS軌跡がありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TrackMap(points = track, modifier = Modifier.fillMaxWidth().height(240.dp))
            }
        }
    }
}

/** 一覧の1行。左端の色帯が判定（帰りは色を付けない） */
@Composable
private fun RecordRow(record: CommuteRecord, onClick: () -> Unit) {
    val judgement = record.judgement
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(6.dp)
                    .height(64.dp)
                    .background(
                        judgement?.let { Color(it.colorArgb) }
                            ?: MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "${formatDateShort(record.date)}　${record.direction.label}",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${formatTime(record.departureTime)} → ${formatTime(record.arrivalTime)}" +
                        (if (record.delayed) " / 遅延" else "") +
                        (if (record.detour) " / 寄り道" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.padding(end = 12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(formatDuration(record.durationMinutes), fontWeight = FontWeight.Bold)
                judgement?.let { JudgementBadge(it) }
            }
        }
    }
}

@Composable
private fun RecordDetailDialog(
    record: CommuteRecord,
    loadTrack: suspend (String) -> List<TrackPoint>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var track by remember(record.id) { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(record.id) { track = loadTrack(record.id) }

    val stages = record.direction.stageTimes
    // 空欄は「未入力」と書く。「—」1文字だけにすると、Xiaomi(HyperOS)の標準フォントでは
    // 幅がほぼ0で描かれ、欄ごと消えたように見えた（実機で確認）
    fun optional(time: LocalTime?) = formatTime(time).ifEmpty { "未入力" }
    val rows = buildList {
        add("日付" to formatDateShort(record.date))
        add("区分" to record.direction.label)
        if (stages) add("起床" to optional(record.wakeTime))
        add("出発" to formatTime(record.departureTime))
        if (stages) add("自宅最寄り駅着" to optional(record.homeStationTime))
        add("乗車電車" to optional(record.trainTime))
        if (stages) add("職場最寄り駅着" to optional(record.workStationTime))
        add((if (stages) "ビル到着" else "到着") to formatTime(record.arrivalTime))
    } + listOf(
        "所要時間" to formatDuration(record.durationMinutes),
        "混雑度" to (record.crowding?.label ?: "未入力"),
        "遅延" to if (record.delayed) "あり" else "なし",
        "寄り道" to if (record.detour) "あり（${record.detourNote.ifEmpty { "メモなし" }}）" else "なし",
        "判定" to (record.judgement?.label ?: "—（帰りは判定なし）"),
        "GPS" to if (record.trackPoints > 0) {
            "${record.trackPoints}点 / 約%.2fkm".format(record.trackDistanceKm)
        } else {
            "記録なし"
        },
        "メモ" to record.note.ifEmpty { "なし" },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("記録の詳細") },
        text = {
            // 行きは項目が多く、地図まで入れると画面に収まらない端末があるのでスクロールさせる
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEach { (label, value) ->
                    Row {
                        Text(
                            label,
                            // 「職場最寄り駅着」が1行に収まる幅
                            modifier = Modifier.width(104.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (track.isNotEmpty()) {
                    TrackMap(
                        points = track,
                        modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("編集する") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("削除しますか？") },
            text = { Text("${formatDateShort(record.date)}の${record.direction.label}の記録を削除します。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("削除する", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun ExportCard(count: Int, onExport: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("出力", style = MaterialTheme.typography.titleMedium)
            Text(
                "入力項目に加えて、所要時間と判定を含んだxlsxを書き出します。\n" +
                    "GPSの座標そのものは含みません（位置が分かるファイルを配らないため）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onExport,
                enabled = count > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("xlsxを書き出す（${count}件）") }
        }
    }
}
