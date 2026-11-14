package com.jmdargere.tracephoto

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class Bilan {
    var geo = 0; var deja = 0; var hors = 0; var xmp = 0; var xmpExiste = 0; var sansDate = 0; var erreurs = 0; var renomme = 0
    val lieux = java.util.TreeMap<String, Int>()
}

class Fichier(val parent: Uri, val docId: String, val nom: String)

/** Cherche les photos du dossier choisi, lit l'heure de prise de vue et écrit la position issue de la trace. */
class Geoloc(private val c: Context, private val racine: Uri, private val decalageUtcSec: Int, private val decalHorlogeSec: Int, private val remplacer: Boolean,
             private val ecrireLieu: Boolean, private val renommer: Boolean, @Volatile var lieuxOk: Boolean) {
    private class Groupe(val parent: Uri, val parentId: String, val base: String, var t: Long, var lieu: Lieu?)
    private val groupes = LinkedHashMap<String, Groupe>()
    @Volatile var annule = false
    private val cr = c.contentResolver
    private val fmt = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val jpg = setOf("jpg", "jpeg")
    private val autres = setOf("raf", "dng", "nef", "arw", "cr2", "cr3", "orf", "rw2", "heic", "hif", "heif", "tif", "tiff")

    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun lister(): List<Fichier> {
        val out = ArrayList<Fichier>()
        val rootId = DocumentsContract.getTreeDocumentId(racine)
        fun marche(id: String) {
            if (annule) return
            val enf = DocumentsContract.buildChildDocumentsUriUsingTree(racine, id)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val dossiers = ArrayList<String>()
            try {
                cr.query(enf, cols, null, null, null)?.use { q ->
                    while (q.moveToNext()) {
                        val did = q.getString(0); val nom = q.getString(1) ?: continue; val mime = q.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (!nom.startsWith(".")) dossiers.add(did) }
                        else { val e = ext(nom); if (e in jpg || e in autres) out.add(Fichier(DocumentsContract.buildDocumentUriUsingTree(racine, id), did, nom)) }
                    }
                }
            } catch (_: Exception) {}
            for (d in dossiers) marche(d)
        }
        marche(rootId)
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
        val b = Bilan()
        for ((i, f) in fichiers.withIndex()) {
            if (annule) break
            progres(i + 1, fichiers.size)
            try { un(f, pts, b) } catch (_: Exception) { b.erreurs++ }
        }
        if (renommer && !annule) renommerGroupes(b)
        return b
    }

    private fun un(f: Fichier, pts: List<Pt>, b: Bilan) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(racine, f.docId)
        val e = ext(f.nom)
        // lecture seule d'abord
        var t: Long? = null; var aGps = false
        cr.openFileDescriptor(uri, "r")!!.use { p ->
            val ex = ExifInterface(p.fileDescriptor)
            t = heure(ex); aGps = ex.latLong != null
        }
        if (t == null) { b.sansDate++; return }
        if (aGps && !remplacer && e in jpg) { b.deja++; return }
        val pos = Trace.position(pts, t!!)
        if (pos == null) { b.hors++; return }
        val lieu = if (lieuxOk) Lieux.chercher(c, pos.lat, pos.lon) else null
        if (lieu != null) { b.lieux[lieu.texte()] = (b.lieux[lieu.texte()] ?: 0) + 1
            val base = f.nom.substringBeforeLast('.')
            groupes.getOrPut(DocumentsContract.getDocumentId(f.parent) + "/" + base) { Groupe(f.parent, DocumentsContract.getDocumentId(f.parent), base, t!!, lieu) } }
        if (e in jpg) {
            cr.openFileDescriptor(uri, "rw")!!.use { p ->
                val ex = ExifInterface(p.fileDescriptor)
                ex.setLatLong(pos.lat, pos.lon)
                if (pos.ele != null) {
                    ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${Math.round(Math.abs(pos.ele) * 100)}/100")
                    ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (pos.ele < 0) "1" else "0")
                }
                if (ecrireLieu && lieu != null && ex.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).isNullOrBlank()) ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, lieu.texte())
                ex.saveAttributes()
            }
            // contrôle : on relit
            val ok = cr.openFileDescriptor(uri, "r")!!.use { p -> ExifInterface(p.fileDescriptor).latLong != null }
            if (ok) b.geo++ else b.erreurs++
        } else {
            val nomXmp = f.nom.substringBeforeLast('.') + ".xmp"
            if (existe(f.parent, nomXmp)) { b.xmpExiste++; return }
            val u = DocumentsContract.createDocument(cr, f.parent, "application/octet-stream", nomXmp) ?: run { b.erreurs++; return }
            cr.openOutputStream(u, "w")!!.use { it.write(xmp(pos, lieu).toByteArray(Charsets.UTF_8)) }
            b.xmp++; b.geo++
        }
    }

    private fun existe(parent: Uri, nom: String): Boolean {
        val pid = DocumentsContract.getDocumentId(parent)
        val enf = DocumentsContract.buildChildDocumentsUriUsingTree(racine, pid)
        cr.query(enf, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { q ->
            while (q.moveToNext()) if (nom.equals(q.getString(0), true)) return true
        }
        return false
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

    private val dejaNomme = Regex("""^\d{4}-\d{2}-\d{2}_\d{4}_""")
    private val fmtNom = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    private fun renommerGroupes(b: Bilan) {
        for (g in groupes.values) {
            if (annule) return
            if (dejaNomme.containsMatchIn(g.base)) continue
            val l = g.lieu ?: continue
            val local = LocalDateTime.ofEpochSecond(g.t / 1000 + decalHorlogeSec + decalageUtcSec, 0, ZoneOffset.UTC)
            val nouveau = fmtNom.format(local) + "_" + l.court() + "_" + g.base
            val enf = DocumentsContract.buildChildDocumentsUriUsingTree(racine, g.parentId)
            val aRenommer = ArrayList<Pair<String, String>>()
            try {
                cr.query(enf, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { q ->
                    while (q.moveToNext()) { val n = q.getString(1) ?: continue
                        if (n.substringBeforeLast('.').equals(g.base, true) && n.contains('.')) aRenommer.add(q.getString(0) to n) }
                }
            } catch (_: Exception) {}
            for ((id, n) in aRenommer) {
                try {
                    val r = DocumentsContract.renameDocument(cr, DocumentsContract.buildDocumentUriUsingTree(racine, id), nouveau + "." + n.substringAfterLast('.'))
                    if (r != null) b.renomme++ else b.erreurs++
                } catch (_: Exception) { b.erreurs++ }
            }
        }
    }
}
