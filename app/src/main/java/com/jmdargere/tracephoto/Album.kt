package com.jmdargere.tracephoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Album : export de la carte (GPX) et du PDF (carte + une photo par page) pour les photos choisies. */
object Album {
    private const val W = 595
    private const val H = 842

    private fun tracePlage(c: Context, sel: List<Entree>): List<Pt> {
        if (sel.isEmpty()) return emptyList()
        val t0 = sel.minOf { it.t } - 43_200_000L; val t1 = sel.maxOf { it.t } + 43_200_000L
        return Trace.lireTout(c).filter { it.t in t0..t1 }
    }

    fun carteGpx(c: Context, sel: List<Entree>, avecTrace: Boolean): Uri? {
        val wp = sel.filter { it.lat != null && it.lon != null }.map { Wp(it.nom, it.lat!!, it.lon!!, it.t, it.lieu) }
        return Trace.exporterCarnet(c, wp, if (avecTrace) tracePlage(c, sel) else emptyList(), "album_")
    }

    // ---------- PDF ----------
    fun pdf(c: Context, sel: List<Entree>, avecTrace: Boolean, progres: (Int, Int, String) -> Unit): Uri? {
        val doc = PdfDocument()
        try {
            val trace = if (avecTrace) tracePlage(c, sel) else emptyList()
            progres(0, sel.size, "Carte…")
            var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
            dessinerCarte(page.canvas, sel, trace)
            doc.finishPage(page)
            for ((i, e) in sel.withIndex()) {
                progres(i + 1, sel.size, e.nom)
                page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, i + 2).create())
                dessinerPhoto(c, page.canvas, e, i + 1, sel.size)
                doc.finishPage(page)
            }
            val nom = "album_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".pdf"
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nom); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/TRACE_PHOTO")
            }
            val u = c.contentResolver.insert(MediaStore.Files.getContentUri("external"), v) ?: return null
            c.contentResolver.openOutputStream(u)?.use { doc.writeTo(it) }
            return u
        } finally { doc.close() }
    }

    private fun paint(couleur: Int, taille: Float = 12f, gras: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = couleur; textSize = taille; typeface = if (gras) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun lieuTexte(e: Entree): String = e.lieu.removePrefix("≈ ").trim()

    // --- page 1 : carte ---
    private const val MAP_W = 515
    private const val MAP_H = 600
    private fun mx(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1 shl z) * 256.0
    private fun my(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 shl z) * 256.0
    }

    private fun tuile(z: Int, x: Int, y: Int): Bitmap? = try {
        val n = 1 shl z
        val u = URL("https://tile.openstreetmap.org/$z/${((x % n) + n) % n}/$y.png")
        val cx = u.openConnection() as HttpURLConnection
        cx.connectTimeout = 8000; cx.readTimeout = 8000
        cx.setRequestProperty("User-Agent", "TRACE-PHOTO/3.9 (application Android personnelle)")
        cx.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    private fun dessinerCarte(cv: Canvas, sel: List<Entree>, trace: List<Pt>) {
        val pts = sel.filter { it.lat != null && it.lon != null }
        cv.drawColor(Color.WHITE)
        val fmt = SimpleDateFormat("d MMMM yyyy", Locale.FRENCH)
        cv.drawText("Carnet de voyage", 40f, 56f, paint(Color.parseColor("#263238"), 26f, true))
        if (sel.isNotEmpty()) {
            val d0 = fmt.format(Date(sel.first().t)); val d1 = fmt.format(Date(sel.last().t))
            cv.drawText((if (d0 == d1) d0 else "$d0 – $d1") + "   •   ${sel.size} photos", 40f, 82f, paint(Color.parseColor("#546E7A"), 13f))
        }
        val ox = 40f; val oy = 110f
        if (pts.isEmpty()) { cv.drawText("Aucune photo avec position.", ox, oy + 30f, paint(Color.DKGRAY, 14f)); return }
        // cadre
        val lats = pts.map { it.lat!! } + trace.map { it.lat }; val lons = pts.map { it.lon!! } + trace.map { it.lon }
        val latMin = lats.min(); val latMax = lats.max(); val lonMin = lons.min(); val lonMax = lons.max()
        var z = 16
        while (z > 2) {
            val w = mx(lonMax, z) - mx(lonMin, z); val h = my(latMin, z) - my(latMax, z)
            if (w <= MAP_W * 0.85 && h <= MAP_H * 0.85) break
            z--
        }
        val cxw = (mx(lonMin, z) + mx(lonMax, z)) / 2; val cyw = (my(latMin, z) + my(latMax, z)) / 2
        val left = cxw - MAP_W / 2; val top = cyw - MAP_H / 2
        cv.save(); cv.clipRect(ox, oy, ox + MAP_W, oy + MAP_H)
        cv.drawColor(Color.parseColor("#ECEFF1"))
        var fond = false
        val tx0 = Math.floor(left / 256).toInt(); val tx1 = Math.floor((left + MAP_W) / 256).toInt()
        val ty0 = Math.floor(top / 256).toInt(); val ty1 = Math.floor((top + MAP_H) / 256).toInt()
        val n = 1 shl z
        for (tx in tx0..tx1) for (ty in ty0..ty1) {
            if (ty < 0 || ty >= n) continue
            val b = tuile(z, tx, ty) ?: continue
            fond = true
            cv.drawBitmap(b, (ox + tx * 256 - left).toFloat(), (oy + ty * 256 - top).toFloat(), null)
        }
        if (!fond) cv.drawText("Fond de carte indisponible (pas d'internet)", ox + 12, oy + 24, paint(Color.GRAY, 12f))
        fun px(lon: Double) = (ox + mx(lon, z) - left).toFloat()
        fun py(lat: Double) = (oy + my(lat, z) - top).toFloat()
        // parcours : vraie trace, sinon ligne entre les photos
        if (trace.isNotEmpty()) {
            val p = Path(); var prec = -1L
            for (q in trace.sortedBy { it.t }) {
                if (prec < 0 || q.t - prec > 900_000) p.moveTo(px(q.lon), py(q.lat)) else p.lineTo(px(q.lon), py(q.lat))
                prec = q.t
            }
            cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#1565C0"); strokeWidth = 3f; strokeJoin = Paint.Join.ROUND })
        } else if (pts.size > 1) {
            val p = Path()
            pts.forEachIndexed { i, e -> if (i == 0) p.moveTo(px(e.lon!!), py(e.lat!!)) else p.lineTo(px(e.lon!!), py(e.lat!!)) }
            cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#78909C"); strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f) })
        }
        // points numérotés (numéro = numéro de page de la photo)
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val rond = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C62828") }
        val bord = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
        sel.forEachIndexed { i, e ->
            if (e.lat == null || e.lon == null) return@forEachIndexed
            val x = px(e.lon); val y = py(e.lat)
            cv.drawCircle(x, y, 9f, rond); cv.drawCircle(x, y, 9f, bord)
            cv.drawText((i + 1).toString(), x, y + 3.5f, num)
        }
        cv.restore()
        cv.drawRect(ox, oy, ox + MAP_W, oy + MAP_H, Paint().apply { style = Paint.Style.STROKE; color = Color.parseColor("#B0BEC5"); strokeWidth = 1f })
        cv.drawText("© contributeurs OpenStreetMap", ox, oy + MAP_H + 16f, paint(Color.GRAY, 9f))
        cv.drawText("Les numéros rouges sont ceux des photos, dans l'ordre de prise de vue." + if (trace.isNotEmpty()) " Ligne bleue : trace GPS." else "", ox, oy + MAP_H + 34f, paint(Color.parseColor("#546E7A"), 10f))
        cv.drawText("TRACE PHOTO par JM.DARGERE", ox, (H - 30).toFloat(), paint(Color.parseColor("#90A4AE"), 9f))
    }

    // --- pages suivantes : une photo par page ---
    private fun dessinerPhoto(c: Context, cv: Canvas, e: Entree, n: Int, total: Int) {
        cv.drawColor(Color.WHITE)
        cv.drawText("Photo $n / $total", 40f, 44f, paint(Color.parseColor("#90A4AE"), 11f))
        val aireX = 40f; val aireY = 60f; val aireW = (W - 80).toFloat(); val aireH = 660f
        try {
            val src = ImageDecoder.createSource(c.contentResolver, Uri.parse(e.uri))
            var echant = 1
            val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = maxOf(info.size.width, info.size.height)
                echant = Math.ceil(m / 1600.0).toInt().coerceAtLeast(1)
                dec.setTargetSampleSize(echant)
            }
            val s = minOf(aireW / bmp.width, aireH / bmp.height)
            val w = bmp.width * s; val h = bmp.height * s
            val l = aireX + (aireW - w) / 2; val t = aireY + (aireH - h) / 2
            cv.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
            bmp.recycle()
        } catch (_: Exception) {
            cv.drawText("Photo illisible", aireX + 20, aireY + 40, paint(Color.GRAY, 14f))
        }
        val jour = SimpleDateFormat("EEEE d MMMM yyyy 'à' HH:mm", Locale.FRENCH).format(Date(e.t))
        cv.drawText(jour.replaceFirstChar { it.uppercase() }, 40f, 748f, paint(Color.parseColor("#263238"), 16f, true))
        val lieu = lieuTexte(e)
        val estime = e.lieu.startsWith("≈")
        if (lieu.isNotEmpty()) cv.drawText(lieu + if (estime) "  (position estimée)" else "", 40f, 770f, paint(Color.parseColor("#37474F"), 13f))
        if (e.lat != null && e.lon != null) cv.drawText("%.5f, %.5f".format(Locale.US, e.lat, e.lon), 40f, 788f, paint(Color.parseColor("#78909C"), 10f))
        cv.drawText(e.nom, 40f, 806f, paint(Color.parseColor("#90A4AE"), 9f))
    }
}
