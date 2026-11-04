package uk.co.netspy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Netspy monitoring", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "New network devices", NotificationManager.IMPORTANCE_DEFAULT))
        }
        startForeground(41, buildNotification("Monitoring is active · first scan starting"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch {
                val prefs = getSharedPreferences("netspy_monitor", MODE_PRIVATE)
                var previous = prefs.getStringSet("known_devices", emptySet())?.toSet() ?: emptySet()
                while (isActive) {
                    try {
                        val scanned = DeviceHistory.record(this@MonitorService, NetworkScanner.scan(this@MonitorService) { _, _ -> })
                        val current = scanned.map { it.ip }.toSet()
                        val newcomers = current - previous
                        if (previous.isNotEmpty() && newcomers.isNotEmpty()) {
                            val manager = getSystemService(NotificationManager::class.java)
                            newcomers.forEachIndexed { index, ip ->
                                val alert = if (Build.VERSION.SDK_INT >= 26) {
                                    Notification.Builder(this@MonitorService, ALERT_CHANNEL)
                                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                                        .setContentTitle("New device detected")
                                        .setContentText("$ip joined your network")
                                        .setAutoCancel(true).build()
                                } else {
                                    @Suppress("DEPRECATION")
                                    Notification.Builder(this@MonitorService).setSmallIcon(android.R.drawable.stat_sys_warning)
                                        .setContentTitle("New device detected").setContentText("$ip joined your network").build()
                                }
                                manager.notify(100 + index, alert)
                            }
                        }
                        previous = current
                        prefs.edit().putStringSet("known_devices", current).apply()
                        getSystemService(NotificationManager::class.java).notify(41, buildNotification("Monitoring · ${current.size} devices · next scan in 15 min"))
                    } catch (_: Exception) { }
                    delay(15 * 60 * 1000L)
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(message: String): Notification =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL).setContentTitle("Netspy monitoring").setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Netspy monitoring").setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_sync).build()
        }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "netspy_monitor"
        const val ALERT_CHANNEL = "netspy_alerts"
    }
}
