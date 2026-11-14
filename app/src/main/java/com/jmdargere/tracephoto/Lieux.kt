package com.jmdargere.tracephoto

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

class Lieu(val ville: String, val villeAscii: String, val pays: String, val cc: String) {
    fun texte() = if (pays.isNotEmpty()) "$ville, $pays" else ville
    fun court() = (villeAscii.ifEmpty { ville }).replace(Regex("[^A-Za-z0-9]+"), "-").trim('-') + "-" + cc
}

/** Noms de lieux HORS LIGNE : base GeoNames « villes > 15 000 habitants » (CC-BY), téléchargée une seule fois (≈ 2 Mo). */
object Lieux {
    private const val URL_BASE = "https://download.geonames.org/export/dump/cities15000.zip"
    private class V(val lat: Double, val lon: Double, val nom: String, val ascii: String, val cc: String)
    private var grille: HashMap<Long, ArrayList<V>>? = null

    private fun fichier(c: Context) = File(c.filesDir, "villes.tsv")
    fun disponible(c: Context) = fichier(c).exists() && fichier(c).length() > 100_000

    /** Télécharge la base si besoin. Retourne true si la base est utilisable. */
    fun assurer(c: Context): Boolean {
        if (disponible(c)) return true
        return try {
            val cx = URL(URL_BASE).openConnection() as HttpURLConnection
            cx.connectTimeout = 15000; cx.readTimeout = 30000
            ZipInputStream(cx.inputStream).use { z ->
                var e = z.nextEntry
                while (e != null && e.name != "cities15000.txt") e = z.nextEntry
                if (e == null) return false
                val tmp = File(c.filesDir, "villes.tmp")
                tmp.outputStream().use { o -> z.copyTo(o) }
                tmp.renameTo(fichier(c))
            }
            disponible(c)
        } catch (_: Exception) { false }
    }

    private fun cle(la: Int, lo: Int) = (la + 90).toLong() * 1000 + (lo + 180)

    private fun charger(c: Context) {
        if (grille != null) return
        val g = HashMap<Long, ArrayList<V>>()
        try {
            fichier(c).forEachLine { l ->
                val f = l.split('\t')
                if (f.size > 8) {
                    val la = f[4].toDoubleOrNull(); val lo = f[5].toDoubleOrNull()
                    if (la != null && lo != null) g.getOrPut(cle(Math.floor(la).toInt(), Math.floor(lo).toInt())) { ArrayList() }.add(V(la, lo, f[1], f[2], f[8]))
                }
            }
        } catch (_: Exception) {}
        grille = g
    }

    private fun km(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val r = Math.PI / 180; val dla = (a2 - a1) * r; val dlo = (o2 - o1) * r
        val x = Math.sin(dla / 2).let { it * it } + Math.cos(a1 * r) * Math.cos(a2 * r) * Math.sin(dlo / 2).let { it * it }
        return 12742 * Math.asin(Math.sqrt(x))
    }

    /** Ville la plus proche (100 km max). */
    fun chercher(c: Context, lat: Double, lon: Double): Lieu? {
        charger(c)
        val g = grille ?: return null
        val la0 = Math.floor(lat).toInt(); val lo0 = Math.floor(lon).toInt()
        var best: V? = null; var bd = 1e9
        for (dla in -1..1) for (dlo in -1..1) {
            var lo = lo0 + dlo; if (lo < -180) lo += 360; if (lo > 179) lo -= 360
            g[cle(la0 + dla, lo)]?.forEach { v -> val d = km(lat, lon, v.lat, v.lon); if (d < bd) { bd = d; best = v } }
        }
        val b = best ?: return null
        if (bd > 100) return null
        val pays = Locale("", b.cc).getDisplayCountry(Locale.getDefault())
        return Lieu(b.nom, b.ascii, pays, b.cc)
    }
}
