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

    /** Historique des événements de la trace (démarrage, arrêt, coupure Android, reprise). */
    fun evt(c: Context, msg: String) {
        try { val f = File(c.filesDir, "evenements.log"); if (f.exists() && f.length() > 60_000) f.writeText(f.readLines().takeLast(200).joinToString("\n") + "\n")
            f.appendText(SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date()) + "  " + msg + "\n") } catch (_: Exception) {}
    }
    fun lireEvt(c: Context): String = try { File(c.filesDir, "evenements.log").readLines().takeLast(60).reversed().joinToString("\n") } catch (_: Exception) { "" }

    /** Marques manuelles « 📍 ici » (position du téléphone au moment de l'appui). */
    fun marquer(c: Context, lat: Double, lon: Double, t: Long) { try { File(c.filesDir, "marques.tsv").appendText("$t\t$lat\t$lon\n") } catch (_: Exception) {} }
    fun marques(c: Context): List<Wp> = try { File(c.filesDir, "marques.tsv").readLines().mapNotNull { l -> val p = l.split('\t'); if (p.size == 3) Wp("📍 " + SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(p[0].toLong())), p[1].toDouble(), p[2].toDouble(), p[0].toLong(), "Marque manuelle") else null } } catch (_: Exception) { emptyList() }

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
    fun exporter(c: Context, f: File): android.net.Uri? {
        try {
            if (Build.VERSION.SDK_INT < 29 || !f.exists()) return null
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/TRACE_PHOTO")
            }
            val u = c.contentResolver.insert(MediaStore.Files.getContentUri("external"), v) ?: return null
            c.contentResolver.openOutputStream(u)?.use { it.write(f.readBytes()) }
            return u
        } catch (_: Exception) { return null }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

    /** GPX de visualisation : la trace + un repère par photo géolocalisée. Retourne son Uri (Documents/TRACE_PHOTO). */
    private fun ecrireGpx(c: Context, contenu: String): android.net.Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val n = "photos_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".gpx"
        return try {
            // supprime les anciennes cartes photos_*.gpx (copies jetables) pour ne garder que la nouvelle
            try { c.contentResolver.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("photos_%.gpx", "Documents/TRACE_PHOTO/")) } catch (_: Exception) {}
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, n)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/TRACE_PHOTO")
            }
            val u = c.contentResolver.insert(MediaStore.Files.getContentUri("external"), v)
            u?.let { c.contentResolver.openOutputStream(it)?.use { o -> o.write(contenu.toByteArray()) } }
            u
        } catch (_: Exception) { null }
    }

    /** Carte d'un import : trace réelle + points des photos importées. */
    fun exporterVisu(c: Context, pts: List<Pt>, wpts: List<Wp>): android.net.Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val fmt = iso.get()!!
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"TRACE PHOTO par JM.DARGERE\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (w in wpts + marques(c)) sb.append("<wpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><name>%s</name><desc>%s</desc></wpt>\n".format(Locale.US, w.lat, w.lon, fmt.format(Date(w.t)), esc(w.nom), esc(w.desc)))
        sb.append("<trk><name>Trace</name><trkseg>\n")
        for (p in pts) sb.append("<trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time></trkpt>\n".format(Locale.US, p.lat, p.lon, fmt.format(Date(p.t))))
        sb.append("</trkseg></trk></gpx>\n")
        return ecrireGpx(c, sb.toString())
    }

    /** Carnet de voyage : un point par photo restante (nom = jour + heure), et un parcours par jour reliant les photos. */
    fun exporterCarnet(c: Context, photos: List<Wp>, trace: List<Pt> = emptyList()): android.net.Uri? {
        val fmt = iso.get()!!
        val jour = SimpleDateFormat("yyyy-MM-dd", Locale.US); val heure = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        val l = photos.sortedBy { it.t }
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"TRACE PHOTO par JM.DARGERE\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (w in l) sb.append("<wpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><name>%s</name><desc>%s</desc></wpt>\n".format(Locale.US, w.lat, w.lon, fmt.format(Date(w.t)), esc(heure.format(Date(w.t))), esc((w.desc + "\n" + w.nom).trim())))
        for ((j, v) in l.groupBy { jour.format(Date(it.t)) }) {
            sb.append("<trk><name>%s</name><trkseg>\n".format(esc(j)))
            for (w in v) sb.append("<trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time></trkpt>\n".format(Locale.US, w.lat, w.lon, fmt.format(Date(w.t))))
            sb.append("</trkseg></trk>\n")
        }
        if (trace.isNotEmpty()) {
            // vraie trace GPS : un nouveau segment à chaque coupure de plus de 15 min (pas de ligne droite à travers les trous)
            sb.append("<trk><name>Trace GPS réelle</name>\n")
            var prec = -1L; var ouvert = false
            for (p in trace.sortedBy { it.t }) {
                if (!ouvert || p.t - prec > 900_000) { if (ouvert) sb.append("</trkseg>\n"); sb.append("<trkseg>\n"); ouvert = true }
                sb.append("<trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time></trkpt>\n".format(Locale.US, p.lat, p.lon, fmt.format(Date(p.t))))
                prec = p.t
            }
            if (ouvert) sb.append("</trkseg>\n")
            sb.append("</trk>\n")
        }
        sb.append("</gpx>\n")
        return ecrireGpx(c, sb.toString())
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
