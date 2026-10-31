package app.d60link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mantiene viva la app mientras se descargan o borran fotos, con una
 * notificación de progreso. Se para sola al terminar.
 */
class DownloadService : Service() {

    private val scope = MainScope()
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            build(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "d60link:download")
                .apply { acquire(WAKE_TIMEOUT_MS) }
        }
        if (watcher == null) {
            val nm = getSystemService(NotificationManager::class.java)
            watcher = scope.launch {
                var started = false
                applicationContext.controller.state
                    .map { it.progress }
                    .distinctUntilChanged()
                    .collect { p ->
                        if (p != null) {
                            started = true
                            nm.notify(NOTIF_ID, build(p))
                        } else if (started || !applicationContext.controller.isWorking) {
                            stopSelf()
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun build(p: Progress?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            p == null -> getString(R.string.notif_preparing)
            p.deleting -> getString(R.string.deleting, p.done, p.total)
            else -> getString(R.string.downloading, p.done, p.total)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(p?.total ?: 0, p?.done ?: 0, p == null)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIF_ID = 1
        private const val WAKE_TIMEOUT_MS = 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }
        }
    }
}
