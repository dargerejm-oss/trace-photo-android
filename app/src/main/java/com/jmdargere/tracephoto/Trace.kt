package com.jmdargere.tracephoto

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Un point de la trace : temps en millisecondes UTC. */
class Pt(val t: Long, val lat: Double, val lon: Double, val ele: Double?)

object Trace {
    private val iso = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    private val rx = Regex("""<trkpt lat="([-0-9.]+)" lon="([-0-9.]+)">(?:<ele>([-0-9.]+)</ele>)?<time>([^<]+)</time>""")

    fun dir(c: Context): File = File(c.filesDir, "traces").also { it.mkdirs() }

    fun nouveauFichier(c: Context): File {
        val n = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir(c), "trace_$n.gpx")
        f.writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"TRACE PHOTO par JM.DARGERE\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>$n</name><trkseg>\n")
        return f
    }

    fun ajouter(f: File, lat: Double, lon: Double, ele: Double?, t: Long) {
        val e = if (ele != null) "<ele>%.1f</ele>".format(Locale.US, ele) else ""
        f.appendText("<trkpt lat=\"%.6f\" lon=\"%.6f\">%s<time>%s</time></trkpt>\n".format(Locale.US, lat, lon, e, iso.get()!!.format(Date(t))))
    }

    fun lireFichierCompte(f: File): Int = try { rx.findAll(f.readText()).count() } catch (_: Exception) { 0 }

    fun fermer(f: File) {
        if (f.exists() && !f.readText().contains("</gpx>")) f.appendText("</trkseg></trk></gpx>\n")
    }

    /** Copie la trace dans Documents/TRACE_PHOTO (visible depuis le gestionnaire de fichiers). */
    fun exporter(c: Context, f: File) {
        try {
            if (Build.VERSION.SDK_INT < 29 || !f.exists()) return
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/TRACE_PHOTO")
            }
            val u = c.contentResolver.insert(MediaStore.Files.getContentUri("external"), v) ?: return
            c.contentResolver.openOutputStream(u)?.use { it.write(f.readBytes()) }
        } catch (_: Exception) {}
    }

    fun lireTout(c: Context): List<Pt> {
        val out = ArrayList<Pt>()
        val fmt = iso.get()!!
        for (f in dir(c).listFiles { x -> x.name.endsWith(".gpx") } ?: emptyArray()) {
            try {
                for (m in rx.findAll(f.readText())) {
                    val t = fmt.parse(m.groupValues[4])?.time ?: continue
                    out.add(Pt(t, m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDoubleOrNull()))
                }
            } catch (_: Exception) {}
        }
        out.sortBy { it.t }
        return out
    }

    /** Position à l'instant t : interpolée entre deux points proches, sinon le point le plus proche (5 min max). */
    fun position(pts: List<Pt>, t: Long): Pt? {
        if (pts.isEmpty()) return null
        var lo = 0; var hi = pts.size - 1
        if (t < pts[0].t) return if (pts[0].t - t <= 300_000) pts[0] else null
        if (t > pts[hi].t) return if (t - pts[hi].t <= 300_000) pts[hi] else null
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (pts[mid].t <= t) lo = mid else hi = mid }
        val a = pts[lo]; val b = pts[hi]
        val gap = b.t - a.t
        if (gap <= 900_000) {
            if (gap == 0L) return a
            val k = (t - a.t).toDouble() / gap
            val ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * k else null
            return Pt(t, a.lat + (b.lat - a.lat) * k, a.lon + (b.lon - a.lon) * k, ele)
        }
        val da = t - a.t; val db = b.t - t
        return if (minOf(da, db) <= 300_000) (if (da <= db) a else b) else null
    }
}
