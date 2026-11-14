package com.jmdargere.tracephoto

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.exifinterface.media.ExifInterface
import java.util.Locale

/** Visionneuse : toutes les photos importées (album « Fuji X4 »), avec contrôle de la position lue DANS le fichier. */
class ViewerActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var liste: List<Entree>
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        liste = Journal.lire(this).filter { it.jpeg }.reversed()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#ECEFF1")) }
        val n = liste.size; val ok = liste.count { it.lat != null }
        root.addView(TextView(this).apply {
            text = "🖼 " + T.f("v_titre", n, ok); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263238")); setPadding(dp(14), dp(34), dp(14), dp(12))
        })
        if (liste.isEmpty()) {
            root.addView(TextView(this).apply { text = T.t("v_vide"); textSize = 16f; setPadding(dp(16), dp(24), dp(16), 0) })
        } else {
            val g = GridView(this).apply { numColumns = 3; horizontalSpacing = dp(3); verticalSpacing = dp(3); setPadding(dp(3), dp(3), dp(3), dp(3)) }
            g.adapter = object : BaseAdapter() {
                override fun getCount() = liste.size
                override fun getItem(p: Int) = liste[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, v: View?, par: ViewGroup): View {
                    val w = (resources.displayMetrics.widthPixels - dp(15)) / 3
                    val f = (v as? FrameLayout) ?: FrameLayout(this@ViewerActivity).apply {
                        layoutParams = AbsListView.LayoutParams(w, w)
                        addView(ImageView(this@ViewerActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.LTGRAY) }, FrameLayout.LayoutParams(-1, -1))
                        addView(TextView(this@ViewerActivity).apply { textSize = 15f; setPadding(dp(4), 0, dp(4), 0) }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
                    }
                    val e = liste[p]
                    val iv = f.getChildAt(0) as ImageView; iv.setImageBitmap(null); iv.tag = e.uri
                    val tv = f.getChildAt(1) as TextView
                    tv.text = if (e.lat != null) "📍" else "❔"
                    Thread { try { val bm = contentResolver.loadThumbnail(Uri.parse(e.uri), Size(w, w), null); h.post { if (iv.tag == e.uri) iv.setImageBitmap(bm) } } catch (_: Exception) {} }.start()
                    return f
                }
            }
            g.setOnItemClickListener { _, _, p, _ -> detail(p) }
            root.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    private fun lirePos(e: Entree): DoubleArray? = try {
        contentResolver.openFileDescriptor(Uri.parse(e.uri), "r")!!.use { ExifInterface(it.fileDescriptor).latLong }
    } catch (_: Exception) { null }

    private fun detail(start: Int) {
        var i = start
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val col = LinearLayout(d.context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val img = ImageView(d.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val txt = TextView(d.context).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(10), dp(14), dp(6)) }
        fun btn(t: String, c: String, act: () -> Unit) = Button(d.context).apply { text = t; setTextColor(Color.WHITE); isAllCaps = false; textSize = 16f
            background = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(14).toFloat() }; setOnClickListener { act() } }
        lateinit var maj: () -> Unit
        val bPrec = btn("◀", "#455A64") { if (i > 0) { i--; maj() } }
        val bSuiv = btn("▶", "#455A64") { if (i < liste.size - 1) { i++; maj() } }
        val bCarte = btn("🗺 " + T.t("v_carte"), "#00838F") {
            val e = liste[i]; if (e.lat != null && e.lon != null) {
                val u = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)", e.lat, e.lon, e.lat, e.lon, Uri.encode(e.nom)))
                try { startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, u), T.t("carte_info"))) } catch (_: Exception) { Toast.makeText(this, T.t("pas_carte"), Toast.LENGTH_LONG).show() } } }
        val bFerme = btn("✕", "#B71C1C") { d.dismiss() }
        val ligne = LinearLayout(d.context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(16)) }
        ligne.addView(bPrec, LinearLayout.LayoutParams(0, dp(54), 1f)); ligne.addView(bCarte, LinearLayout.LayoutParams(0, dp(54), 3f))
        ligne.addView(bSuiv, LinearLayout.LayoutParams(0, dp(54), 1f)); ligne.addView(bFerme, LinearLayout.LayoutParams(0, dp(54), 1f))
        col.addView(img, LinearLayout.LayoutParams(-1, 0, 1f)); col.addView(txt); col.addView(ligne)
        maj = {
            val e = liste[i]
            val p = lirePos(e)
            val etat = if (p != null) String.format(Locale.US, "✅ %s\nN/S %.5f  E/W %.5f", T.t("v_ok"), p[0], p[1]) else "❌ " + T.t("v_non")
            txt.text = e.nom + "\n" + (if (e.lieu.isNotEmpty()) "🌍 " + e.lieu + "\n" else "") + etat
            bCarte.isEnabled = e.lat != null
            Thread { try { val bm: Bitmap = contentResolver.loadThumbnail(Uri.parse(e.uri), Size(1600, 1600), null); h.post { img.setImageBitmap(bm) } } catch (_: Exception) {} }.start()
        }
        d.setContentView(col); d.show(); maj()
    }
}
