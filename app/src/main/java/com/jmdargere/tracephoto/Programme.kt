package com.jmdargere.tracephoto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Trace programmée : démarre à l'heure de début et s'arrête à l'heure de fin, chaque jour. */
class Programme : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val sp = c.getSharedPreferences("trace", Context.MODE_PRIVATE)
        if (!sp.getBoolean("prog", false)) return
        when (i.action) {
            "START" -> if (!TraceService.actif) try { c.startForegroundService(Intent(c, TraceService::class.java).putExtra("prog", true)) } catch (_: Exception) {}
            "STOP" -> if (TraceService.actif) try { c.startService(Intent(c, TraceService::class.java).setAction("STOP")) } catch (_: Exception) {}
        }
        armer(c)
    }
    companion object {
        fun dedans(c: Context, h: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean {
            val sp = c.getSharedPreferences("trace", Context.MODE_PRIVATE)
            val d = sp.getInt("prog_d", 8); val f = sp.getInt("prog_f", 20)
            return if (d < f) h in d until f else h >= d || h < f
        }
        private fun pi(c: Context, a: String) = PendingIntent.getBroadcast(c, if (a == "START") 21 else 22, Intent(c, Programme::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        private fun prochain(h: Int): Long = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis <= System.currentTimeMillis() + 5000) add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        fun armer(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(c, "START")); am.cancel(pi(c, "STOP"))
            val sp = c.getSharedPreferences("trace", Context.MODE_PRIVATE)
            if (!sp.getBoolean("prog", false)) return
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prochain(sp.getInt("prog_d", 8)), pi(c, "START"))
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prochain(sp.getInt("prog_f", 20)), pi(c, "STOP"))
        }
    }
}
