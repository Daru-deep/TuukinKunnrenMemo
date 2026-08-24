package com.tuukinmemo.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * 端末側の設定の案内
 *
 * 権限は「記録開始」を押した時点でも頼んでいるが、それだけでは
 * 通勤の途中で記録が止まることがある。端末の位置情報そのものがOFFだったり、
 * 電池の最適化に任せたままだと、画面を消している間にOSがサービスを
 * 止めてしまうため。出発してから気づいても手遅れなので、
 * 足りないものを出発前に一目で分かるようにしておく。
 */

/** 記録を最後まで続けるために要る、端末側の設定 */
enum class SetupItem(
    val label: String,
    val why: String,
    /** 実行時に頼む権限。設定画面に送るしかないものは null */
    val permission: String?,
) {
    LOCATION_PERMISSION(
        label = "位置情報の権限",
        why = "ルートの記録に要ります。",
        permission = Manifest.permission.ACCESS_FINE_LOCATION,
    ),
    LOCATION_SERVICE(
        label = "位置情報（GPS）をONにする",
        why = "端末の位置情報そのものが切れています。権限があっても測位できません。",
        permission = null,
    ),
    NOTIFICATION_PERMISSION(
        label = "通知の権限",
        why = "記録中であることを通知に出します。出せないと止め忘れに気づけません。",
        permission = Manifest.permission.POST_NOTIFICATIONS,
    ),
    BATTERY(
        label = "電池の最適化から除外する",
        why = "画面を消している間に記録を止められないようにします。" +
            "通勤のように1時間ほど続ける記録では、これがないと途中で切れることがあります。",
        permission = null,
    ),
    ;

    fun isSatisfied(context: Context): Boolean = when (this) {
        LOCATION_PERMISSION -> context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

        // Android 13より前は通知に権限が要らない
        NOTIFICATION_PERMISSION ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.isGranted(Manifest.permission.POST_NOTIFICATIONS)

        LOCATION_SERVICE -> context.getSystemService(LocationManager::class.java)
            ?.let { LocationManagerCompat.isLocationEnabled(it) } == true

        BATTERY -> context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** いま足りていない設定。画面が前に戻るたびに見直す */
@Stable
class DeviceSetupState internal constructor(private val context: Context) {

    var missing: List<SetupItem> by mutableStateOf(emptyList())
        private set

    /**
     * 一度頼んでも許可されなかった権限。
     * 「今後表示しない」を選ばれるとダイアログ自体が出なくなるので、
     * そのあとは設定画面へ送るように案内を切り替える。
     */
    var mustUseSettings: Set<SetupItem> by mutableStateOf(emptySet())
        internal set

    val isReady: Boolean get() = missing.isEmpty()

    fun refresh() {
        missing = SetupItem.entries.filterNot { it.isSatisfied(context) }
    }
}

@Composable
fun rememberDeviceSetupState(): DeviceSetupState {
    val context = LocalContext.current
    val state = remember(context) { DeviceSetupState(context) }
    // 設定画面から戻ってきたときに、直っているかを見直す
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }
    return state
}

/**
 * 足りない設定を並べて、その場から設定画面へ送るカード。
 *
 * @param alwaysShow true にすると、すべて設定済みでも一覧を出す（集計画面の確認用）。
 *   false のときは足りないものがある場合だけ出るので、普段は邪魔にならない。
 */
@Composable
fun DeviceSetupCard(
    state: DeviceSetupState,
    alwaysShow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    if (state.isReady && !alwaysShow) return

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val refused = result.filterValues { !it }.keys
        state.mustUseSettings = state.mustUseSettings +
            SetupItem.entries.filter { it.permission in refused }
        state.refresh()
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (state.isReady) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.isReady) "端末の設定はそろっています" else "端末の設定が足りません",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (state.isReady) {
                Text(
                    "画面を消していてもルートの記録が続きます。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "このままだと、通勤の途中で記録が止まることがあります。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SetupItem.entries.forEach { item ->
                val satisfied = item !in state.missing
                if (satisfied && !alwaysShow) return@forEach

                SetupRow(
                    item = item,
                    satisfied = satisfied,
                    onAction = {
                        val permission = item.permission
                        if (permission != null && item !in state.mustUseSettings) {
                            permissionLauncher.launch(arrayOf(permission))
                        } else {
                            context.openSettingFor(item)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupRow(
    item: SetupItem,
    satisfied: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        // 説明が複数行になるので、印と項目名の行を上でそろえる
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (satisfied) "✓" else "!",
            modifier = Modifier.width(16.dp),
            fontWeight = FontWeight.Bold,
        )

        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium)
            if (!satisfied) {
                Text(
                    item.why,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (!satisfied) {
            TextButton(onClick = onAction) { Text("設定") }
        }
    }
}

/**
 * その設定の画面を直接開く。
 * 端末によっては目的の画面が無いことがあるので、そのときはアプリの設定画面に送る。
 */
private fun Context.openSettingFor(item: SetupItem) {
    val intents = when (item) {
        SetupItem.LOCATION_SERVICE -> listOf(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        )

        SetupItem.BATTERY -> listOf(
            // 「除外しますか？」のダイアログが直接出る
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", packageName, null),
            ),
            // 出せない端末では一覧から選んでもらう
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        else -> emptyList()
    }

    val appSettings = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )

    for (intent in intents + appSettings) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (error: ActivityNotFoundException) {
            // 次の候補を試す
        }
    }
}
