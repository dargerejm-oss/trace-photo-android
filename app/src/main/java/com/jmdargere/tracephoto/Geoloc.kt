package com.jmdargere.tracephoto

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class Wp(val nom: String, val lat: Double, val lon: Double, val t: Long, val desc: String)

class Bilan {
    val wpts = ArrayList<Wp>()
    val lieux = java.util.TreeMap<String, Int>()
    val aSupprimer = ArrayList<Pair<Uri, Uri>>()   // (original sur la carte, copie vérifiée sur le téléphone)
    var supprimees = 0; var echecSupp = 0
    var importees = 0; var deja = 0; var avecPos = 0; var sansPos = 0; var sansDate = 0; var verifiees = 0; var erreurs = 0; var raw = 0
}

class Fichier(val parent: Uri, val docId: String, val nom: String, val taille: Long, val modif: Long)

/** Entrée du journal d'import (pour la visionneuse). */
class Entree(val uri: String, val nom: String, val lat: Double?, val lon: Double?, val lieu: String, val t: Long, val jpeg: Boolean)

object Journal {
    private fun f(c: Context) = File(c.filesDir, "importes.tsv")
    fun cles(c: Context): HashSet<String> {
        val s = HashSet<String>()
        try { f(c).forEachLine { l -> val p = l.split('\t'); if (p.isNotEmpty()) s.add(p[0]) } } catch (_: Exception) {}
        return s
    }
    fun ajouter(c: Context, cle: String, e: Entree) {
        f(c).appendText(listOf(cle, e.uri, e.nom, e.lat?.toString() ?: "", e.lon?.toString() ?: "", e.lieu, e.t, if (e.jpeg) "1" else "0").joinToString("\t") + "\n")
    }
    fun lire(c: Context): List<Entree> {
        val out = ArrayList<Entree>()
        try { f(c).forEachLine { l -> val p = l.split('\t')
            if (p.size >= 8) out.add(Entree(p[1], p[2], p[3].toDoubleOrNull(), p[4].toDoubleOrNull(), p[5], p[6].toLongOrNull() ?: 0, p[7] == "1")) } } catch (_: Exception) {}
        return out
    }
}

/** Copie les NOUVELLES photos de la carte vers le téléphone (album « Fuji X4 »), avec position + lieu. La carte n'est jamais modifiée. */
class Geoloc(private val c: Context, private val racine: Uri, private val decalageUtcSec: Int, private val decalHorlogeSec: Int,
             private val ecrireLieu: Boolean, private val renommer: Boolean, private val coordNom: Boolean, private val sansPosition: Boolean,
             @Volatile var lieuxOk: Boolean) {
    @Volatile var annule = false
    private val cr = c.contentResolver
    private val fmt = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val fmtNom = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
    private val jpg = setOf("jpg", "jpeg")
    private val autres = setOf("raf", "dng", "nef", "arw", "cr2", "cr3", "orf", "rw2", "heic", "hif", "heif", "tif", "tiff")
    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun lister(): List<Fichier> {
        val out = ArrayList<Fichier>()
        fun marche(id: String) {
            if (annule) return
            val enf = DocumentsContract.buildChildDocumentsUriUsingTree(racine, id)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val dossiers = ArrayList<String>()
            try {
                cr.query(enf, cols, null, null, null)?.use { q ->
                    while (q.moveToNext()) {
                        val did = q.getString(0); val nom = q.getString(1) ?: continue; val mime = q.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (!nom.startsWith(".")) dossiers.add(did) }
                        else { val e = ext(nom); if (e in jpg) out.add(Fichier(DocumentsContract.buildDocumentUriUsingTree(racine, id), did, nom, q.getLong(3), q.getLong(4))) }
                    }
                }
            } catch (_: Exception) {}
            for (d in dossiers) marche(d)
        }
        marche(DocumentsContract.getTreeDocumentId(racine))
        return out
    }

    private fun heure(exif: ExifInterface): Long? {
        val s = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        return try {
            val dt = LocalDateTime.parse(s.trim(), fmt)
            val off = exif.getAttribute("OffsetTimeOriginal")?.let { try { ZoneOffset.of(it.trim()).totalSeconds } catch (_: Exception) { null } } ?: decalageUtcSec
            (dt.toEpochSecond(ZoneOffset.ofTotalSeconds(off)) - decalHorlogeSec) * 1000L
        } catch (_: Exception) { null }
    }

    fun traiter(fichiers: List<Fichier>, pts: List<Pt>, progres: (Int, Int) -> Unit): Bilan {
        val b = Bilan(); val faits = Journal.cles(c)
        for ((i, f) in fichiers.withIndex()) {
            if (annule) break
            progres(i + 1, fichiers.size)
            val cle = "${f.nom}|${f.taille}|${f.modif}"
            if (cle in faits) { b.deja++; continue }
            try { un(f, cle, pts, b) } catch (_: Exception) { b.erreurs++ }
        }
        return b
    }

    private fun un(f: Fichier, cle: String, pts: List<Pt>, b: Bilan) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(racine, f.docId)
        val e = ext(f.nom); val base = f.nom.substringBeforeLast('.')
        var t: Long? = null
        try { cr.openFileDescriptor(uri, "r")!!.use { p -> t = heure(ExifInterface(p.fileDescriptor)) } } catch (_: Exception) {}
        val pos = t?.let { Trace.position(pts, it) }
        if (t == null) b.sansDate++
        if (pos == null && !sansPosition) return
        val lieu = if (pos != null && lieuxOk) Lieux.chercher(c, pos.lat, pos.lon) else null
        if (pos != null) { b.avecPos++; if (lieu != null) b.lieux[lieu.texte()] = (b.lieux[lieu.texte()] ?: 0) + 1 } else b.sansPos++

        var nouvelleBase = base
        if (renommer && t != null) {
            val local = LocalDateTime.ofEpochSecond(t!! / 1000 + decalHorlogeSec + decalageUtcSec, 0, ZoneOffset.UTC)
            val coord = if (coordNom && pos != null) "_" + "%s%.4f_%s%.4f".format(Locale.US, if (pos.lat >= 0) "N" else "S", Math.abs(pos.lat), if (pos.lon >= 0) "E" else "W", Math.abs(pos.lon)) else ""
            nouvelleBase = fmtNom.format(local) + (if (lieu != null) "_" + lieu.court() else "") + coord + "_" + base
        }
        val desc = lieu?.texte() ?: ""
        if (e in jpg) {
            val tmp = File(c.cacheDir, "imp.jpg")
            cr.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (pos != null) {
                val ex = ExifInterface(tmp.absolutePath)
                ex.setLatLong(pos.lat, pos.lon)
                if (pos.ele != null) {
                    ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${Math.round(Math.abs(pos.ele) * 100)}/100")
                    ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (pos.ele < 0) "1" else "0")
                }
                if (ecrireLieu && desc.isNotEmpty() && ex.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).isNullOrBlank()) ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, desc)
                ex.saveAttributes()
            }
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$nouvelleBase.$e"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Fuji X4"); put(MediaStore.Images.Media.IS_PENDING, 1)
                if (t != null) put(MediaStore.Images.Media.DATE_TAKEN, t!!)
            }
            val dest = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: run { b.erreurs++; return }
            cr.openOutputStream(dest)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            cr.update(dest, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            tmp.delete()
            val ok = try { cr.openFileDescriptor(dest, "r")!!.use { p -> val x = ExifInterface(p.fileDescriptor); p.statSize > 1000 && (pos == null || x.latLong != null) } } catch (_: Exception) { false }
            if (pos != null) { if (ok) b.verifiees++ else b.erreurs++ }
            if (ok) b.aSupprimer.add(uri to dest)
            Journal.ajouter(c, cle, Entree(dest.toString(), "$nouvelleBase.$e", pos?.lat, pos?.lon, desc, t ?: 0, true))
        }
        b.importees++
        if (pos != null) b.wpts.add(Wp("$nouvelleBase.$e", pos.lat, pos.lon, t ?: 0, desc))
    }

    /** Supprime de la carte les JPEG dont la copie a été vérifiée (les RAW ne sont jamais touchés). */
    fun supprimerOriginaux(b: Bilan, progres: (Int, Int) -> Unit) {
        for ((i, p) in b.aSupprimer.withIndex()) {
            progres(i + 1, b.aSupprimer.size)
            try {
                val copieOk = cr.openFileDescriptor(p.second, "r")?.use { it.statSize > 1000 } == true
                if (copieOk && DocumentsContract.deleteDocument(cr, p.first)) b.supprimees++ else b.echecSupp++
            } catch (_: Exception) { b.echecSupp++ }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun dm(v: Double, pos: Char, neg: Char): String {
        val a = Math.abs(v); val d = a.toInt(); val m = (a - d) * 60
        return "%d,%.5f%c".format(Locale.US, d, m, if (v >= 0) pos else neg)
    }

    private fun xmp(p: Pt, l: Lieu?): String {
        val lx = if (l != null) " xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\" xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\" photoshop:City=\"${esc(l.ville)}\" photoshop:Country=\"${esc(l.pays)}\" Iptc4xmpCore:CountryCode=\"${l.cc}\"" else ""
        val alt = if (p.ele != null) " exif:GPSAltitude=\"${Math.round(Math.abs(p.ele) * 100)}/100\" exif:GPSAltitudeRef=\"${if (p.ele < 0) 1 else 0}\"" else ""
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" exif:GPSVersionID=\"2.3.0.0\" " +
            "exif:GPSLatitude=\"${dm(p.lat, 'N', 'S')}\" exif:GPSLongitude=\"${dm(p.lon, 'E', 'W')}\"$alt$lx/>" +
            "</rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>"
    }
}
