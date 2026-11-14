package com.jmdargere.tracephoto

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/** Enregistre la position toutes les 15 s tant que le bouton est sur ▶. Tourne en avant-plan (notification). */
class TraceService : Service() {
    companion object {
        @Volatile var actif = false
        @Volatile var points = 0
        @Volatile var dernierFix = 0L
        const val INTERVALLE = 15_000L
    }

    private var lm: LocationManager? = null
    private var fichier: File? = null
    private var wl: PowerManager.WakeLock? = null
    private val ecoute = LocationListener { l: Location -> noter(l) }
    private var dernier: Location? = null
    private var immobile = 0
    private var dernierTz = Int.MIN_VALUE
    private var lent = false
    private var eco = true

    private fun noter(l: Location) {
        val f = fichier ?: return
        if (l.hasAccuracy() && l.accuracy > 150f) return
        Trace.ajouter(f, l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null, l.time)
        points++; dernierFix = System.currentTimeMillis()
        try { val off = java.util.TimeZone.getDefault().getOffset(l.time) / 1000
            val tz = java.io.File(filesDir, "tz.tsv")
            if (off != dernierTz) { dernierTz = off; tz.appendText("${l.time}\t$off\n") } } catch (_: Exception) {}
        if (eco) {
            val d = dernier?.distanceTo(l) ?: 100f
            dernier = l
            if (d < 15f) immobile++ else immobile = 0
            if (!lent && immobile >= 4) { lent = true; relancer(60_000L) }
            else if (lent && d >= 30f) { lent = false; immobile = 0; relancer(INTERVALLE) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sp = getSharedPreferences("trace", MODE_PRIVATE)
        if (intent?.action == "STOP") { arreter(); return START_NOT_STICKY }
        creerNotif()
        if (actif) return START_STICKY
        // redémarrage par le système : on reprend le même fichier
        val repris: File? = if (intent == null) sp.getString("fichier", null)?.let { File(it) }?.takeIf { it.exists() } else null
        fichier = repris ?: Trace.nouveauFichier(this)
        sp.edit().putString("fichier", fichier!!.absolutePath).putBoolean("actif", true).apply()
        wl = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tracephoto:trace").apply { acquire(12 * 3600_000L) }
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        eco = sp.getBoolean("eco", true); lent = false; immobile = 0; dernier = null
        try {
            lm!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVALLE, 0f, ecoute)
            if (lm!!.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm!!.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, INTERVALLE * 2, 0f, ecoute)
        } catch (_: SecurityException) { arreter(); return START_NOT_STICKY }
        points = Trace.lireFichierCompte(fichier!!); actif = true
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun relancer(ms: Long) {
        try { lm?.removeUpdates(ecoute); lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, ms, 0f, ecoute) } catch (_: Exception) {}
    }

    private fun arreter() {
        try { lm?.removeUpdates(ecoute) } catch (_: Exception) {}
        fichier?.let { Trace.fermer(it); Trace.exporter(this, it) }
        try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        getSharedPreferences("trace", MODE_PRIVATE).edit().putBoolean("actif", false).apply()
        actif = false
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun creerNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("trace", "Trace", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "trace").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("📍 TRACE PHOTO").setContentText(T.t("notif")).setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
    }

    override fun onDestroy() { try { lm?.removeUpdates(ecoute) } catch (_: Exception) {}; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
