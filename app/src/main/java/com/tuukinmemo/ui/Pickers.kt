package com.tuukinmemo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tuukinmemo.model.formatDateShort
import com.tuukinmemo.model.formatTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * 日付・時刻の入力部品
 *
 * 通勤中に片手で押すので、
 * - ボタンを大きくして押す場所に迷わせない
 * - 「今」ボタンで現在時刻を一発で入れられる（実際にはこれしか使わない日が多い）
 * を優先している。
 */

@Composable
fun DateField(
    date: LocalDate,
    onChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text("日付", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text("$date  ${formatDateShort(date)}")
        }
    }

    if (showDialog) {
        DatePickerModal(
            initial = date,
            onDismiss = { showDialog = false },
            onSelect = {
                onChange(it)
                showDialog = false
            },
        )
    }
}

@Composable
fun TimeField(
    label: String,
    time: LocalTime?,
    onChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { showDialog = true }, modifier = Modifier.weight(1f)) {
                Text(if (time == null) "未入力" else formatTime(time))
            }
            // 「今」＝現在時刻を入れる。歩きながらでも押せるよう独立したボタンにしている
            OutlinedButton(onClick = { onChange(LocalTime.now().withSecond(0).withNano(0)) }) {
                Text("今")
            }
        }
    }

    if (showDialog) {
        TimePickerModal(
            initial = time ?: LocalTime.now(),
            onDismiss = { showDialog = false },
            onSelect = {
                onChange(it)
                showDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerModal(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    // DatePicker は「UTCの真夜中のミリ秒」でやり取りする決まりなので、そこだけ変換する
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis == null) {
                    onDismiss()
                } else {
                    onSelect(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text("決定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerModal(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onSelect: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSelect(LocalTime.of(state.hour, state.minute)) }) { Text("決定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        text = { TimePicker(state = state) },
    )
}
