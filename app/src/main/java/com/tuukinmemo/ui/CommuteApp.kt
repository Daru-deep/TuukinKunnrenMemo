package com.tuukinmemo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tuukinmemo.BuildConfig
import com.tuukinmemo.MainViewModel
import com.tuukinmemo.export.XlsxWriter
import com.tuukinmemo.model.Direction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 画面（下のタブ） */
private enum class Tab(val label: String, val icon: ImageVector, val direction: Direction?) {
    OUTBOUND("行き", Icons.Filled.Work, Direction.OUTBOUND),
    INBOUND("帰り", Icons.Filled.Home, Direction.INBOUND),
    SUMMARY("集計・出力", Icons.Filled.BarChart, null),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommuteApp(viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.OUTBOUND) }

    val records by viewModel.records.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val gpsMessage by viewModel.gpsMessage.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 保存しました・削除しましたなどの通知を画面下に出す
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    /**
     * xlsxの保存。
     * アプリが勝手に場所を決めず、端末の「保存先を選ぶ」画面（SAF）を出す。
     * これならストレージの権限が要らず、Driveなどにも直接保存できる。
     */
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = viewModel.buildXlsx()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.isSuccess
            }
            viewModel.notify(if (ok) "xlsxを書き出しました" else "書き出しに失敗しました")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通勤記録") },
                actions = { Text("記録 ${records.size}件", modifier = Modifier.padding(end = 16.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)

        when (val direction = tab.direction) {
            null -> SummaryScreen(
                records = records,
                summary = summary,
                loadTrack = viewModel::loadTrack,
                onEdit = { record ->
                    viewModel.edit(record)
                    // 編集する記録の区分のタブへ移動する
                    tab = if (record.direction == Direction.OUTBOUND) Tab.OUTBOUND else Tab.INBOUND
                },
                onDelete = viewModel::delete,
                onExport = { exportLauncher.launch(XlsxWriter.suggestedFileName()) },
                // サンプル生成はデバッグビルドだけに出す（配布版には入らない）
                onCreateSampleData = if (BuildConfig.DEBUG) viewModel::createSampleData else null,
                modifier = contentModifier,
            )

            else -> EntryScreen(
                direction = direction,
                draft = drafts[direction] ?: viewModel.draftOf(direction),
                errors = errors[direction].orEmpty(),
                track = tracks[direction].orEmpty(),
                isRecording = recording == direction,
                gpsMessage = if (recording == direction) gpsMessage else "",
                onDraftChange = { transform -> viewModel.updateDraft(direction, transform) },
                onStartRecording = { viewModel.startRecording(direction) },
                onStopRecording = viewModel::stopRecording,
                onClearTrack = { viewModel.clearTrack(direction) },
                onSave = { viewModel.save(direction) },
                onReset = { viewModel.resetDraft(direction) },
                modifier = contentModifier,
            )
        }
    }
}

private const val XLSX_MIME_TYPE =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
