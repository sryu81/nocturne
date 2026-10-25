package com.nocturne.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nocturne.MainActivity
import com.nocturne.R
import com.nocturne.session.AppState
import com.nocturne.session.NotificationRules
import com.nocturne.session.PushLevel
import com.nocturne.session.PushNotification
import com.nocturne.session.forQuietHours
import com.nocturne.session.runningJob
import com.nocturne.session.totalDone
import com.nocturne.session.totalSubs
import com.nocturne.ui.home.Health
import com.nocturne.ui.home.RigHealthSnapshot
import com.nocturne.ui.home.rigVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Nocturne alive while the phone is locked and in a pocket, and says so when something goes
 * wrong: the persistent notification is the price of Android letting the connection run, and the
 * alerts come from [NotificationRules]. It holds a CPU and a Wi-Fi lock so the socket keeps being
 * served with the screen off, and stops itself when the connection it watches is gone.
 */
class RigMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Rig connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Nocturne keeps watching the rig"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Rig alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Failures, a lost link and a full disk"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = MonitorBridge.source
        if (source == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, STATUS_ID, statusNotification("Connecting to ${source.host}…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (job == null) {
            acquireLocks()
            job = scope.launch { watch(source) }
        }
        return START_NOT_STICKY
    }

    private suspend fun watch(source: MonitorSource) = kotlinx.coroutines.coroutineScope {
        val rules = NotificationRules()
        // Subscribing keeps the Wi-Fi/beacon/daemon polls running while the screen is off.
        source.health?.let { health -> launch { health.collect { } } }
        launch {
            source.state.collect { state ->
                post(rules.onState(state).forQuietHours(state.quietHoursEnabled))
            }
        }
        var lastStatusText = ""
        while (true) {
            delay(TICK_MS)
            if (MonitorBridge.source !== source) {
                stopSelf()
                return@coroutineScope
            }
            val now = System.currentTimeMillis()
            val state = source.state.value
            val snap = source.health?.value ?: RigHealthSnapshot()
            val verdict = rigVerdict(source.status.value, source.link, snap, now)
            post(rules.onLink(verdict.text.takeIf { verdict.health == Health.BAD }, now).forQuietHours(state.quietHoursEnabled))
            snap.daemon?.let { pi ->
                pi.sd?.let { post(rules.onDisk("SD card", it.free.toDouble() / it.total.coerceAtLeast(1), now).forQuietHours(state.quietHoursEnabled)) }
                pi.usb.forEach { post(rules.onDisk("USB ${it.path}", it.free.toDouble() / it.total.coerceAtLeast(1), now).forQuietHours(state.quietHoursEnabled)) }
            }
            val text = statusText(state, verdict.text.takeIf { verdict.health == Health.BAD })
            if (text != lastStatusText) {
                lastStatusText = text
                notify(STATUS_ID, statusNotification(text))
            }
        }
    }

    private fun statusText(state: AppState, problem: String?): String {
        if (problem != null) return problem
        if (!state.ekosRunning) return "Connected · Ekos not running"
        val job = state.runningJob
        return if (job != null) "Imaging · sub ${job.totalDone}/${job.totalSubs}" else "Ekos running · no job"
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_nocturne)
            .setContentTitle("Nocturne is watching the rig")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun post(items: List<PushNotification>) {
        items.forEach { item ->
            val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_nocturne)
                .setContentTitle(item.title)
                .setContentText(item.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.text))
                .setPriority(if (item.level == PushLevel.INFO) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
                .setCategory(if (item.level == PushLevel.CRITICAL) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
            notify(item.key.hashCode(), notification)
        }
    }

    private fun notify(id: Int, notification: Notification) {
        // Without the permission (Android 13+) notify() throws; the foreground service keeps running.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireLocks() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nocturne:monitor")
            .apply { acquire(MAX_LOCK_MS) }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "nocturne:monitor").apply { acquire() }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_STATUS = "rig_status"
        const val CHANNEL_ALERTS = "rig_alerts"
        const val STATUS_ID = 1
        const val TICK_MS = 5_000L
        /** Far longer than a night; released on destroy, this only bounds a leaked lock. */
        const val MAX_LOCK_MS = 14L * 3_600_000
    }
}
