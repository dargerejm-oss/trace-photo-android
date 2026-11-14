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
    var supprimees = 0; var echecSupp = 0; var errSupp = ""; var copieAbsente = 0
    var estimees = 0; var importees = 0; var deja = 0; var avecPos = 0; var sansPos = 0; var sansDate = 0; var verifiees = 0; var erreurs = 0; var raw = 0
}

class Fichier(val parent: Uri, val docId: String, val nom: String, val taille: Long, val modif: Long)

/** Entrée du journal d'import (pour la visionneuse). */
class Entree(val uri: String, val nom: String, val lat: Double?, val lon: Double?, val lieu: String, val t: Long, val jpeg: Boolean)

object Journal {
    private fun f(c: Context) = File(c.filesDir, "importes.tsv")
    fun cles(c: Context): HashMap<String, String> {
        val s = HashMap<String, String>()
        try { f(c).forEachLine { l -> val p = l.split('\t'); if (p.size > 1) s[p[0]] = p[1] } } catch (_: Exception) {}
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
    /** Entrées dont la photo existe encore dans la galerie (les photos supprimées par l'utilisateur n'apparaissent plus). Le fichier du journal reste intact, donc rien n'est réimporté. */
    fun vivantes(c: Context): List<Entree> = lire(c).filter { e ->
        try { c.contentResolver.query(Uri.parse(e.uri), arrayOf("_id"), null, null, null)?.use { it.count > 0 } ?: false } catch (_: Exception) { false }
    }
}

/** Copie les NOUVELLES photos de la carte vers le téléphone (album « Fuji X4 »), avec position + lieu. La carte n'est jamais modifiée. */
class Geoloc(private val c: Context, private val racine: Uri, private val decalageUtcSec: Int, private val decalHorlogeSec: Int,
             private val ecrireLieu: Boolean, private val renommer: Boolean, private val coordNom: Boolean, private val sansPosition: Boolean,
             private val tzAuto: Boolean, private val voisine: Boolean, private val albumFixe: String, private val albumParAppareil: Boolean,
             @Volatile var lieuxOk: Boolean) {
    private class Info(val t: Long?, val make: String?, val model: String?)
    private val infos = HashMap<String, Info>()
    private class Ref(val t: Long, val lat: Double, val lon: Double)
    private val refs = ArrayList<Ref>()
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

    private val tzs: List<Pair<Long, Int>> by lazy {
        val o = ArrayList<Pair<Long, Int>>()
        try { File(c.filesDir, "tz.tsv").forEachLine { l -> val p = l.split('\t'); if (p.size == 2) o.add(p[0].toLong() to p[1].toInt()) } } catch (_: Exception) {}
        o.sortBy { it.first }; o
    }
    /** Fuseau du téléphone au moment de la prise de vue (si le champ « fuseau » est vide) ; sinon la valeur réglée. */
    private fun tzPour(dt: LocalDateTime): Int {
        if (!tzAuto || tzs.isEmpty()) return decalageUtcSec
        val approx = dt.toEpochSecond(ZoneOffset.ofTotalSeconds(decalageUtcSec)) * 1000L
        var r = tzs[0].second
        for (e in tzs) { if (e.first <= approx + 86_400_000L) r = e.second else break }
        return r
    }

    private fun heure(exif: ExifInterface): Long? {
        val s = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        return try {
            val dt = LocalDateTime.parse(s.trim(), fmt)
            val off = exif.getAttribute("OffsetTimeOriginal")?.let { try { ZoneOffset.of(it.trim()).totalSeconds } catch (_: Exception) { null } } ?: tzPour(dt)
            (dt.toEpochSecond(ZoneOffset.ofTotalSeconds(off)) - decalHorlogeSec) * 1000L
        } catch (_: Exception) { null }
    }

    private fun lireInfo(f: Fichier): Info {
        val uri = DocumentsContract.buildDocumentUriUsingTree(racine, f.docId)
        return try {
            cr.openFileDescriptor(uri, "r")!!.use { p ->
                val x = ExifInterface(p.fileDescriptor)
                Info(heure(x), x.getAttribute(ExifInterface.TAG_MAKE), x.getAttribute(ExifInterface.TAG_MODEL))
            }
        } catch (_: Exception) { Info(null, null, null) }
    }

    fun traiter(fichiers: List<Fichier>, pts: List<Pt>, progres: (Int, Int) -> Unit): Bilan {
        val b = Bilan(); val faits = Journal.cles(c)
        // passe 1 : heures de prise de vue + positions connues (pour estimer les photos hors trace)
        for (e in Journal.lire(c)) if (e.lat != null && e.lon != null && e.t > 0 && !e.lieu.startsWith("≈")) refs.add(Ref(e.t, e.lat, e.lon))
        for ((i, f) in fichiers.withIndex()) {
            if (annule) break
            progres(i + 1, fichiers.size)
            val cle = "${f.nom}|${f.taille}|${f.modif}"
            if (faits[cle] != null) continue
            val inf = lireInfo(f); infos[cle] = inf
            val tt = inf.t
            if (tt != null) Trace.position(pts, tt)?.let { refs.add(Ref(tt, it.lat, it.lon)) }
        }
        // passe 2 : import
        for ((i, f) in fichiers.withIndex()) {
            if (annule) break
            progres(i + 1, fichiers.size)
            val cle = "${f.nom}|${f.taille}|${f.modif}"
            val dejaUri = faits[cle]
            if (dejaUri != null) {
                b.deja++
                try { val d = Uri.parse(dejaUri); if (cr.openFileDescriptor(d, "r")?.use { it.statSize > 1000 } == true) b.aSupprimer.add(DocumentsContract.buildDocumentUriUsingTree(racine, f.docId) to d) } catch (_: Exception) {}
                continue
            }
            try { un(f, cle, pts, b) } catch (_: Exception) { b.erreurs++ }
        }
        return b
    }

    private fun albumDe(i: Info): String {
        if (!albumParAppareil || (i.make.isNullOrBlank() && i.model.isNullOrBlank())) return albumFixe.ifBlank { "Fuji X4" }
        var mk = (i.make ?: "").trim(); val md = (i.model ?: "").trim()
        mk = if (mk.uppercase(Locale.ROOT).startsWith("FUJI")) "Fuji" else mk.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
        val n = if (md.lowercase(Locale.ROOT).startsWith(mk.lowercase(Locale.ROOT))) md else "$mk $md"
        return n.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").ifBlank { albumFixe.ifBlank { "Fuji X4" } }
    }

    private fun un(f: Fichier, cle: String, pts: List<Pt>, b: Bilan) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(racine, f.docId)
        val e = ext(f.nom); val base = f.nom.substringBeforeLast('.')
        val inf = infos[cle] ?: lireInfo(f)
        val t: Long? = inf.t
        var pos = t?.let { Trace.position(pts, it) }
        var approx = false
        if (pos == null && voisine && t != null) {
            var best: Ref? = null; var bd = 3 * 3600_000L + 1
            for (r in refs) { val d = Math.abs(r.t - t); if (d < bd) { bd = d; best = r } }
            if (best != null) { pos = Pt(t, best.lat, best.lon, null); approx = true }
        }
        if (t == null) b.sansDate++
        if (pos == null && !sansPosition) return
        val lieu = if (pos != null && lieuxOk) Lieux.chercher(c, pos.lat, pos.lon) else null
        if (pos != null) { b.avecPos++; if (approx) b.estimees++; if (lieu != null) b.lieux[lieu.texte()] = (b.lieux[lieu.texte()] ?: 0) + 1 } else b.sansPos++

        var nouvelleBase = base
        if (renommer && t != null) {
            val local = LocalDateTime.ofEpochSecond(t / 1000 + decalHorlogeSec + decalageUtcSec, 0, ZoneOffset.UTC)
            val coord = if (coordNom && pos != null) "_" + "%s%.4f_%s%.4f".format(Locale.US, if (pos.lat >= 0) "N" else "S", Math.abs(pos.lat), if (pos.lon >= 0) "E" else "W", Math.abs(pos.lon)) else ""
            nouvelleBase = fmtNom.format(local) + (if (lieu != null) "_" + lieu.court() else "") + coord + "_" + base
        }
        val desc = (if (approx) "≈ " else "") + (lieu?.texte() ?: "")
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
                if (ecrireLieu && desc.removePrefix("≈ ").isNotEmpty() && ex.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).isNullOrBlank()) ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, desc)
                ex.saveAttributes()
            }
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$nouvelleBase.$e"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + albumDe(inf)); put(MediaStore.Images.Media.IS_PENDING, 1)
                if (t != null) put(MediaStore.Images.Media.DATE_TAKEN, t)
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
    /** Sans rien importer : retrouve sur la carte les JPEG déjà copiés (et vérifiés) pour proposer leur suppression. */
    fun preparerSuppression(fichiers: List<Fichier>): Bilan {
        val b = Bilan(); val faits = Journal.cles(c)
        for (f in fichiers) {
            val d = faits["${f.nom}|${f.taille}|${f.modif}"] ?: continue
            try { val u = Uri.parse(d); if (cr.openFileDescriptor(u, "r")?.use { it.statSize > 1000 } == true) b.aSupprimer.add(DocumentsContract.buildDocumentUriUsingTree(racine, f.docId) to u) } catch (_: Exception) {}
        }
        return b
    }

    fun supprimerOriginaux(b: Bilan, progres: (Int, Int) -> Unit) {
        for ((i, p) in b.aSupprimer.withIndex()) {
            progres(i + 1, b.aSupprimer.size)
            try {
                val copieOk = cr.openFileDescriptor(p.second, "r")?.use { it.statSize > 1000 } == true
                if (!copieOk) { b.copieAbsente++; b.echecSupp++; continue }
                if (DocumentsContract.deleteDocument(cr, p.first)) { val encore = try { cr.query(p.first, arrayOf("document_id"), null, null, null)?.use { it.count > 0 } == true } catch (_: Exception) { false }; if (encore) { b.echecSupp++; if (b.errSupp.isEmpty()) b.errSupp = "fichier toujours présent après suppression" } else b.supprimees++ } else { b.echecSupp++; if (b.errSupp.isEmpty()) b.errSupp = "refus du système (deleteDocument=false)" }
            } catch (e: Exception) { b.echecSupp++; if (b.errSupp.isEmpty()) b.errSupp = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160) }
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
