package com.renamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** v1.10: keeps the free-account creation alive while the app is
 *  minimized. The creation itself runs on plain worker threads
 *  with the application context — those threads survive minimize
 *  as long as the PROCESS lives. But with no foreground component
 *  the system (and aggressive OEM killers) may reclaim the
 *  process within seconds of backgrounding, and the worker thread
 *  dies with it — that is what stopped creation on minimize (the
 *  Activity has no onStop and never cancels anything; the kill
 *  is the process, not the lifecycle). This service holds one
 *  low-priority ongoing notification for exactly the duration of
 *  a foreground creation (started by DualFree.createSingle,
 *  stopped the moment it settles) so the process stays alive.
 *  It runs no work itself and shows nothing except the one
 *  notification, which goes away with the creation. */
class TbCreationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    nm?.createNotificationChannel(NotificationChannel(
                        "tb_creation",
                        AppLanguage.t3(this, "ساخت حساب رایگان", "Free account creation", "Создание бесплатного аккаунта"),
                        NotificationManager.IMPORTANCE_LOW))
                } catch (_: Exception) {}
            }
            val notif = Notification.Builder(this, "tb_creation")
                .setContentTitle(AppLanguage.t3(this, "در حال ساخت حساب رایگان…", "Creating a free account…", "Создание бесплатного аккаунта…"))
                .setContentText(AppLanguage.t3(this, "لطفا کمی صبر کنید", "Please wait a moment", "Пожалуйста, подождите"))
                .setSmallIcon(com.renamer.openvpn.R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
            startForeground(101, notif)
        } catch (_: Exception) {}
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { stopForeground(true) } catch (_: Exception) {}
        super.onDestroy()
    }
}
