package org.zxor.oronbox_location

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class LocationService : Service() {
    companion object {
        internal var onStarted: ((Exception?) -> Unit)? = null
        internal var onStopped: (() -> Unit)? = null
        private const val CHANNEL = "oronbox_location"
        private const val NOTIFICATION_ID = 7301
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL,
                intent.getStringExtra("channelName"), NotificationManager.IMPORTANCE_LOW))
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            val pending = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
            val notification = builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(intent.getStringExtra("notificationTitle"))
                .setContentText(intent.getStringExtra("notificationText"))
                .setContentIntent(pending).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification)
            onStarted?.invoke(null)
        } catch (error: Exception) { onStarted?.invoke(error); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { onStopped?.invoke(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
