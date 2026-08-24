package com.tuukinmemo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tuukinmemo.model.Crowding
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.Judgement
import com.tuukinmemo.model.RecordDraft
import com.tuukinmemo.model.TrackPoint
import com.tuukinmemo.model.formatDuration

/**
 * 行き／帰りの入力画面
 *
 * 行きと帰りで項目は同じなので、direction を受け取る1つの画面を使い回す。
 * 違うのは到着地点の呼び方（職場ビル／最寄り駅）と、判定を出すかどうかだけ。
 */
@Composable
fun EntryScreen(
    direction: Direction,
    draft: RecordDraft,
    errors: List<String>,
    track: List<TrackPoint>,
    isRecording: Boolean,
    gpsMessage: String,
    onDraftChange: ((RecordDraft) -> RecordDraft) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onClearTrack: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (draft.isEditing) {
            EditingBanner(onCancel = onReset)
        }

        DateField(date = draft.date, onChange = { date -> onDraftChange { it.copy(date = date) } })

        TimeField(
            label = "出発時刻",
            time = draft.departureTime,
            onChange = { time -> onDraftChange { it.copy(departureTime = time) } },
        )

        TimeField(
            label = "乗車電車（発車時刻）",
            time = draft.trainTime,
            onChange = { time -> onDraftChange { it.copy(trainTime = time) } },
        )

        ChoiceGroup(
            label = "混雑度",
            options = Crowding.entries.map { it to it.label },
            selected = draft.crowding,
            onSelect = { crowding -> onDraftChange { it.copy(crowding = crowding) } },
        )

        ChoiceGroup(
            label = "遅延",
            options = listOf(false to "なし", true to "あり"),
            selected = draft.delayed,
            onSelect = { delayed -> onDraftChange { it.copy(delayed = delayed) } },
        )

        TimeField(
            label = "到着時刻（${direction.arrivalLabel}）",
            time = draft.arrivalTime,
            onChange = { time -> onDraftChange { it.copy(arrivalTime = time) } },
        )

        ResultRow(draft)

        ChoiceGroup(
            label = "寄り道",
            options = listOf(false to "なし", true to "あり"),
            selected = draft.detour,
            onSelect = { detour -> onDraftChange { it.copy(detour = detour) } },
        )

        if (draft.detour) {
            OutlinedTextField(
                value = draft.detourNote,
                onValueChange = { note -> onDraftChange { it.copy(detourNote = note) } },
                label = { Text("寄り道メモ") },
                placeholder = { Text("例: コンビニに寄った") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = draft.note,
            onValueChange = { note -> onDraftChange { it.copy(note = note) } },
            label = { Text("メモ") },
            placeholder = { Text("体調・混み具合の所感など") },
            modifier = Modifier.fillMaxWidth(),
        )

        GpsCard(
            track = track,
            isRecording = isRecording,
            message = gpsMessage,
            onStart = onStartRecording,
            onStop = onStopRecording,
            onClear = onClearTrack,
        )

        if (errors.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    errors.forEach { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text(if (draft.isEditing) "更新する" else "保存する")
            }
            OutlinedButton(onClick = { confirmReset = true }) { Text("クリア") }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "入力をクリアしますか？",
            message = "入力した時刻と、記録したGPSの軌跡をすべて捨てます。",
            confirmLabel = "クリアする",
            onConfirm = onReset,
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun EditingBanner(onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("既存の記録を編集しています", color = MaterialTheme.colorScheme.onPrimaryContainer)
            TextButton(onClick = onCancel) { Text("やめる") }
        }
    }
}

/** 所要時間と判定バッジ。入力に応じてその場で変わる */
@Composable
private fun ResultRow(draft: RecordDraft) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "所要時間 ${formatDuration(draft.durationMinutes)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            draft.judgement?.let { judgement ->
                JudgementBadge(judgement)
            }
        }
    }
}

/** 混雑度・遅延・寄り道のような選択式。型を問わず使い回せるようにしている */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

/**
 * GPS記録のカード
 *
 * 位置情報の権限は「記録開始」を押した時点で頼む。
 * 起動直後に何の説明もなく権限を求められると、拒否されやすいため。
 */
@Composable
private fun GpsCard(
    track: List<TrackPoint>,
    isRecording: Boolean,
    message: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current

    var confirmClear by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) onStart()
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "軌跡を破棄しますか？",
            message = "記録したGPSの軌跡を捨てます。入力した時刻は残ります。",
            confirmLabel = "破棄する",
            onConfirm = onClear,
            onDismiss = { confirmClear = false },
        )
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("ルート記録（GPS）", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (isRecording) "記録中（${track.size}点）" else "停止中（${track.size}点）",
                    color = if (isRecording) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                message.ifEmpty {
                    "出発前に「記録開始」を押すと、画面を消していても軌跡を記録します。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val missing = requiredPermissions().filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) onStart() else permissionLauncher.launch(missing.toTypedArray())
                    },
                    enabled = !isRecording,
                    modifier = Modifier.weight(1f),
                ) { Text("記録開始") }

                OutlinedButton(onClick = onStop, enabled = isRecording, modifier = Modifier.weight(1f)) {
                    Text("停止")
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = track.isNotEmpty(),
                ) { Text("破棄") }
            }

            if (track.isNotEmpty()) {
                TrackMap(points = track, modifier = Modifier.fillMaxWidth().height(200.dp))
            }
        }
    }
}

/**
 * 取り消せない操作の前に一度だけ聞く。
 * 通勤中に片手で操作するので、誤タップで入力やGPS軌跡が消えると痛い。
 */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

@Composable
fun JudgementBadge(judgement: Judgement) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(judgement.backgroundArgb)),
    ) {
        Text(
            judgement.label,
            color = Color(judgement.colorArgb),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * 記録に必要な権限。
 * Android 13以降は通知の権限も要る（フォアグラウンドサービスの通知を出すため）。
 */
private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
