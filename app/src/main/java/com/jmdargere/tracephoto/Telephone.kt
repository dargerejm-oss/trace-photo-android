package com.jmdargere.tracephoto

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

/** Photos prises avec la caméra du téléphone (dossier DCIM/Camera uniquement, avec les infos d'appareil photo dans le fichier).
 *  Ni captures d'écran, ni téléchargements, ni photos reçues. Elles sont seulement lues : jamais modifiées ni copiées. */
object Telephone {
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)

    fun ok(c: Context) = permissions().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Ajoute au carnet les nouvelles photos de la caméra. Retourne le nombre ajouté. */
    @Synchronized fun synchroniser(c: Context): Int {
        if (!ok(c)) return 0
        val cr = c.contentResolver
        val faits = Journal.cles(c)
        val pts = Trace.lireTout(c)
        val lieux = Lieux.disponible(c)
        var n = 0
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val q = try {
            cr.query(base, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0", arrayOf("DCIM/Camera/"), "${MediaStore.Images.Media.DATE_TAKEN} ASC")
        } catch (_: Exception) { null } ?: return 0
        q.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0); val nom = cur.getString(1) ?: continue
                val cle = "tel:$id|${cur.getLong(4)}"
                if (faits[cle] != null) continue
                var t = cur.getLong(2); if (t <= 0) t = cur.getLong(3) * 1000
                val uri = ContentUris.withAppendedId(base, id)
                var lat: Double? = null; var lon: Double? = null; var camera = false
                try {
                    val u2 = try { MediaStore.setRequireOriginal(uri) } catch (_: Exception) { uri }
                    cr.openInputStream(u2)?.use { s ->
                        val x = ExifInterface(s)
                        camera = !x.getAttribute(ExifInterface.TAG_MAKE).isNullOrBlank() || !x.getAttribute(ExifInterface.TAG_MODEL).isNullOrBlank()
                        x.latLong?.let { lat = it[0]; lon = it[1] }
                    }
                } catch (_: Exception) {}
                if (!camera) { Journal.ajouter(c, cle, Entree(uri.toString(), nom, null, null, "", 0, false)); continue }  // mémorisé comme « pas une photo caméra » (jpeg=false : non affiché)
                var estime = false
                if (lat == null && t > 0) Trace.position(pts, t)?.let { lat = it.lat; lon = it.lon; estime = true }
                val lieu = if (lieux && lat != null && lon != null) Lieux.chercher(c, lat!!, lon!!)?.texte() ?: "" else ""
                val desc = (if (estime) "≈ " else "") + lieu
                Journal.ajouter(c, cle, Entree(uri.toString(), nom, lat, lon, desc, t, true))
                n++
            }
        }
        return n
    }
}
