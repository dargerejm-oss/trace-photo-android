package com.jmdargere.tracephoto

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tuile des réglages rapides : un appui = démarrer / arrêter la trace. */
class TraceTile : TileService() {
    override fun onStartListening() { maj() }
    private fun maj() {
        val t = qsTile ?: return
        t.state = if (TraceService.actif) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (TraceService.actif) "Trace ⏹" else "Trace ▶"
        t.updateTile()
    }
    override fun onClick() {
        if (TraceService.actif) { startService(Intent(this, TraceService::class.java).setAction("STOP")); maj(); return }
        val ok = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) {
            try { startForegroundService(Intent(this, TraceService::class.java)); t(); return } catch (_: Exception) {}
        }
        val i = Intent(this, MainActivity::class.java).setAction("com.jmdargere.tracephoto.DEMARRER").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
    private fun t() { qsTile?.let { it.state = Tile.STATE_ACTIVE; it.label = "Trace ⏹"; it.updateTile() } }
}
