package com.tuukinmemo.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tuukinmemo.MainActivity
import com.tuukinmemo.R
import com.tuukinmemo.data.TrackRepository
import com.tuukinmemo.model.Direction
import com.tuukinmemo.model.TrackPoint

/**
 * 移動の軌跡を記録するフォアグラウンドサービス
 *
 * ここがAndroidアプリにした一番の理由。
 * ブラウザ版では画面を消すと位置情報の取得が止まってしまい、
 * ポケットに入れて歩いている間の軌跡が残らなかった。
 * フォアグラウンドサービス（通知を出しっぱなしにする代わりに、
 * 画面が消えても動き続けられる仕組み）なら、通勤中ずっと記録できる。
 *
 * 通知は「消せない代わりに、いま何をしているか必ず見える」という約束なので、
 * 記録中は点数と誤差を出して、止め忘れにすぐ気づけるようにしている。
 */
class TrackService : Service() {

    private lateinit var client: FusedLocationProviderClient
    private var direction: Direction = Direction.OUTBOUND

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val point = TrackPoint(
                lat = location.latitude,
                lng = location.longitude,
                timeMillis = location.time,
                accuracyMeters = location.accuracy,
            )
            // 間引くかどうかの判断は TrackRepository（＝テスト済みの純粋関数）に任せる
            if (TrackRepository.add(direction, point)) updateNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        TrackRepository.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        direction = Direction.fromId(intent?.getStringExtra(EXTRA_DIRECTION)) ?: Direction.OUTBOUND
        createChannel()
        startForegroundCompat(buildNotification())
        TrackRepository.markRecording(direction)
        TrackRepository.setMessage("記録を開始しました。到着したら「記録を停止」を押してください。")
        requestUpdates()

        // 端末の都合で一度終了されても、OSに再開してもらう。
        // START_STICKY だと再開時のIntentがnullになり、記録中の区分（行き/帰り）が
        // 分からなくなるので、元のIntentごと配り直してもらう。
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        TrackRepository.markRecording(null)
        super.onDestroy()
    }

    private fun requestUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_METERS)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.requestLocationUpdates(request, callback, mainLooper)
        } catch (error: SecurityException) {
            // 権限が無いまま開始された場合（設定から取り消された直後など）
            TrackRepository.setMessage("位置情報の権限がありません。設定で許可してください。")
            stopSelf()
        }
    }

    // -- 通知 ----------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ルート記録",
            // LOW = 音を鳴らさない。通勤中に鳴られても困る
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "GPSで移動の軌跡を記録している間、表示され続けます" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val points = TrackRepository.pointsOf(direction).size
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${direction.label}のルートを記録中")
            .setContentText("${points}点を記録しました")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, "記録を停止", stop)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundCompat(notification: Notification) {
        // Android 10以降は「何のためのフォアグラウンドか」の申告が必要
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "track"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.tuukinmemo.STOP_TRACKING"
        private const val EXTRA_DIRECTION = "direction"

        /** 位置の更新間隔。徒歩の軌跡にはこれくらいで十分で、電池も持つ */
        private const val UPDATE_INTERVAL_MILLIS = 4_000L
        private const val MIN_UPDATE_INTERVAL_MILLIS = 2_000L
        private const val MIN_UPDATE_DISTANCE_METERS = 3f

        fun start(context: Context, direction: Direction) {
            val intent = Intent(context, TrackService::class.java)
                .putExtra(EXTRA_DIRECTION, direction.id)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
