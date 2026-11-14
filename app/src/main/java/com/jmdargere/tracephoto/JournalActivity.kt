package com.jmdargere.tracephoto

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Journal de voyage : un bloc par jour (photos, lieux, distance), recherche, partage, GPX du voyage, sauvegarde du journal. */
class JournalActivity : Activity() {
    private class Jour(val cle: String, val photos: List<Entree>, val lieux: List<String>, val km: Double, val approx: Boolean) {
        fun texte() = "📅 $cle\n📷 ${photos.size}   🥾 ${if (approx) "≈ " else ""}%.1f km\n🌍 ${if (lieux.isEmpty()) "—" else lieux.joinToString(" · ")}".format(Locale.US, km)
    }
    private val sp by lazy { getSharedPreferences("trace", MODE_PRIVATE) }
    private var reel = false
    private var nbPhotos = 0
    private lateinit var titre: TextView
    private val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Distance en ligne droite entre photos successives (carnet « mes photos »). */
    private fun kmPhotos(v: List<Entree>): Double {
        val l = v.filter { it.lat != null && it.lon != null }.sortedBy { it.t }
        var s = 0.0
        for (i in 1 until l.size) { val r = FloatArray(1); android.location.Location.distanceBetween(l[i - 1].lat!!, l[i - 1].lon!!, l[i].lat!!, l[i].lon!!, r); s += r[0] }
        return s / 1000.0
    }

    private fun calculer() {
        val ent = Journal.vivantes(this).filter { it.jpeg && it.t > 0 }
        nbPhotos = ent.size
        val parJ = ent.groupBy { df.format(Date(it.t)) }
        val ptsJ = if (reel) Trace.lireTout(this).groupBy { df.format(Date(it.t)) } else emptyMap()
        val cles = (if (reel) parJ.keys + ptsJ.keys else parJ.keys).toSortedSet(compareByDescending { it })
        jours = cles.map { k -> val v = parJ[k] ?: emptyList()
            Jour(k, v, v.map { it.lieu.removePrefix("≈ ").trim() }.filter { it.isNotEmpty() }.distinct(), if (reel) km(ptsJ[k] ?: emptyList()) else kmPhotos(v), !reel) }
        filtre = jours
    }
    private fun majEntete() {
        titre.text = "📔 " + T.f("j_titre", jours.size, nbPhotos)
    }
    private var jours: List<Jour> = emptyList()
    private var filtre: List<Jour> = emptyList()
    private lateinit var liste: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun km(p: List<Pt>): Double {
        var s = 0.0
        for (i in 1 until p.size) { if (p[i].t - p[i - 1].t > 900_000) continue
            val r = FloatArray(1); android.location.Location.distanceBetween(p[i - 1].lat, p[i - 1].lon, p[i].lat, p[i].lon, r); s += r[0] }
        return s / 1000.0
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        reel = false
        calculer()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#ECEFF1")) }
        titre = TextView(this); root.addView(titre.apply { textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263238")); setPadding(dp(14), dp(34), dp(14), dp(12)) })
        val rech = EditText(this).apply { hint = "🔍 " + T.t("j_rech"); setSingleLine(); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        root.addView(rech)
        fun bt(t: String, c: String, a: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
            background = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(14).toFloat() }; setOnClickListener { a() } }
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), 0) }
        ligne.addView(bt("📤 " + T.t("j_part"), "#1565C0") { partager() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        ligne.addView(bt("🗺 " + T.t("j_gpx"), "#00838F") { gpxVoyage() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        ligne.addView(bt("💾 " + T.t("j_sauv"), "#6A1B9A") { sauvegarder() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        root.addView(ligne)
        val sv = ScrollView(this); liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(20)) }
        sv.addView(liste); root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        rech.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { val q = s.toString().trim().lowercase(Locale.ROOT)
                filtre = if (q.isEmpty()) jours else jours.filter { it.texte().lowercase(Locale.ROOT).contains(q) }; dessiner() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        majEntete(); dessiner()
    }

    private fun dessiner() {
        liste.removeAllViews()
        if (filtre.isEmpty()) liste.addView(TextView(this).apply { text = T.t("j_vide"); textSize = 16f; setPadding(dp(8), dp(16), dp(8), 0) })
        for (j in filtre) liste.addView(TextView(this).apply { text = j.texte(); textSize = 15f; setTextColor(Color.parseColor("#263238")); setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
            setOnClickListener { partagerJour(j) } }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun envoyer(t: String) { try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), T.t("j_part"))) } catch (_: Exception) {} }
    private fun tags(j: Jour) = j.lieux.joinToString(" ") { "#" + it.substringBefore(",").replace(Regex("[^\\p{L}\\p{N}]"), "") }.trim()
    private fun partagerJour(j: Jour) = envoyer(j.texte() + "\n" + tags(j))
    private fun partager() = envoyer(filtre.joinToString("\n\n") { it.texte() } + "\n\n— TRACE PHOTO par JM.DARGERE")

    private fun gpxVoyage() {
        Thread {
            val ent = Journal.vivantes(this).filter { it.jpeg && it.lat != null && it.lon != null && it.t > 0 }
            val u = Trace.exporterCarnet(this, ent.map { Wp(it.nom, it.lat!!, it.lon!!, it.t, it.lieu) })
            runOnUiThread {
                if (u == null) { Toast.makeText(this, T.t("pas_carte"), Toast.LENGTH_LONG).show(); return@runOnUiThread }
                try { startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/gpx+xml").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), T.t("carte_info"))) }
                catch (_: Exception) { Toast.makeText(this, T.t("pas_carte"), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** Copie le journal d'import et les traces dans Documents/TRACE_PHOTO/sauvegarde. */
    private fun sauvegarder() {
        Thread {
            var n = 0
            val st = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val fichiers = ArrayList<File>(); File(filesDir, "importes.tsv").let { if (it.exists()) fichiers.add(it) }
            Trace.dir(this).listFiles { x -> x.name.endsWith(".gpx") }?.let { fichiers.addAll(it) }
            for (f in fichiers) try {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, st + "_" + f.name); put(MediaStore.MediaColumns.MIME_TYPE, if (f.name.endsWith(".gpx")) "application/gpx+xml" else "text/tab-separated-values")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/TRACE_PHOTO/sauvegarde") }
                val u = contentResolver.insert(MediaStore.Files.getContentUri("external"), v) ?: continue
                contentResolver.openOutputStream(u)?.use { it.write(f.readBytes()) }; n++
            } catch (_: Exception) {}
            runOnUiThread { Toast.makeText(this, T.f("j_sauv_ok", n), Toast.LENGTH_LONG).show() }
        }.start()
    }
}
