package com.jmdargere.tracephoto

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Rappel quotidien « Pense à démarrer la trace » (9 h) ; ré-armé au redémarrage du téléphone. */
class Rappel : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val sp = c.getSharedPreferences("trace", Context.MODE_PRIVATE)
        if (i.action == Intent.ACTION_BOOT_COMPLETED) { Programme.armer(c); if (sp.getBoolean("rappel", false)) armer(c, true); return }
        if (!sp.getBoolean("rappel", false)) return
        if (TraceService.actif) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("rappel", "Rappel", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).setAction("com.jmdargere.tracephoto.DEMARRER"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(c, "rappel").setSmallIcon(R.drawable.ic_fg).setContentTitle("📍 " + T.t("rappel_txt")).setContentText(T.t("rappel_sub"))
            .setContentIntent(pi).setAutoCancel(true).build()
        nm.notify(77, n)
    }
    companion object {
        fun armer(c: Context, on: Boolean) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(c, 9, Intent(c, Rappel::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            am.cancel(pi)
            if (!on) return
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1) }
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        }
    }
}
