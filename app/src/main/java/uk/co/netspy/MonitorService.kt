package uk.co.netspy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class MonitorService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Netspy monitoring", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL).setContentTitle("Netspy monitoring").setContentText("Network monitoring is active").setSmallIcon(android.R.drawable.stat_notify_sync).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Netspy monitoring").setContentText("Network monitoring is active").setSmallIcon(android.R.drawable.stat_notify_sync).build()
        }
        startForeground(41, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val CHANNEL = "netspy_monitor" }
}
