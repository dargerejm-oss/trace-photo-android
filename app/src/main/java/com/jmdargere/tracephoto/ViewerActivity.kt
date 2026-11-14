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
    private var toutes: List<Entree> = emptyList()
    private var adapt: BaseAdapter? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        toutes = Journal.vivantes(this).filter { it.jpeg }.reversed(); liste = toutes
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#ECEFF1")) }
        val n = liste.size; val ok = liste.count { it.lat != null }
        root.addView(TextView(this).apply {
            text = "🖼 " + T.f("v_titre", n, ok); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263238")); setPadding(dp(14), dp(34), dp(14), dp(12))
        })
        val rech = EditText(this).apply { hint = "🔍 " + T.t("j_rech"); setSingleLine(); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        root.addView(rech)
        rech.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { val q = s.toString().trim().lowercase(Locale.ROOT)
                liste = if (q.isEmpty()) toutes else toutes.filter { (it.nom + " " + it.lieu).lowercase(Locale.ROOT).contains(q) }; adapt?.notifyDataSetChanged() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        if (liste.isEmpty()) {
            root.addView(TextView(this).apply { text = T.t("v_vide"); textSize = 16f; setPadding(dp(16), dp(24), dp(16), 0) })
        } else {
            val g = GridView(this).apply { numColumns = 3; horizontalSpacing = dp(3); verticalSpacing = dp(3); setPadding(dp(3), dp(3), dp(3), dp(3)) }
            adapt = object : BaseAdapter() {
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
            g.adapter = adapt
            g.setOnItemClickListener { _, _, p, _ -> detail(p) }
            root.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    private fun lirePos(e: Entree): DoubleArray? = try {
        contentResolver.openFileDescriptor(Uri.parse(e.uri), "r")!!.use { ExifInterface(it.fileDescriptor).latLong }
    } catch (_: Exception) { null }

    private fun dms(v: Double, p: Char, n: Char): String { val a = Math.abs(v); val d = a.toInt(); val m = ((a - d) * 60).toInt(); val s = ((a - d) * 60 - m) * 60
        return String.format(Locale.US, "%d°%02d'%04.1f\"%c", d, m, s, if (v >= 0) p else n) }

    /** Fiche de lieu : lieu, pays, coordonnées, altitude, heure, appareil, autres photos du même lieu. */
    private fun fiche(e: Entree) {
        val lieu = e.lieu.removePrefix("≈ ").trim()
        val approx = e.lieu.startsWith("≈")
        var alt = ""; var cam = ""; var heure = ""
        try { contentResolver.openFileDescriptor(Uri.parse(e.uri), "r")!!.use { p -> val x = ExifInterface(p.fileDescriptor)
            val a = x.getAltitude(Double.NaN); if (!a.isNaN()) alt = String.format(Locale.US, "⛰ %.0f m\n", a)
            cam = listOfNotNull(x.getAttribute(ExifInterface.TAG_MAKE), x.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").trim()
            heure = x.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: "" } } catch (_: Exception) {}
        val memes = if (lieu.isNotEmpty()) toutes.count { it.lieu.removePrefix("≈ ").trim() == lieu } else 0
        val sb = StringBuilder()
        sb.append(if (lieu.isNotEmpty()) "🌍 $lieu\n" else "🌍 —\n")
        if (e.lat != null && e.lon != null) { sb.append("📍 ${dms(e.lat, 'N', 'S')}  ${dms(e.lon, 'E', 'W')}\n"); sb.append(String.format(Locale.US, "   (%.5f, %.5f)\n", e.lat, e.lon)) }
        if (approx) sb.append("≈ position estimée d'après une photo voisine\n")
        sb.append(alt); if (heure.isNotEmpty()) sb.append("🕒 $heure\n"); if (cam.isNotEmpty()) sb.append("📷 $cam\n")
        if (memes > 0) sb.append("🖼 $memes photo(s) dans ce lieu\n")
        val ville = lieu.substringBefore(",").trim()
        val ab = android.app.AlertDialog.Builder(this).setTitle("ℹ " + e.nom).setMessage(sb.toString()).setPositiveButton("OK", null)
        if (ville.isNotEmpty()) ab.setNeutralButton("Wikipédia") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://fr.wikipedia.org/wiki/Special:Search?search=" + Uri.encode(ville)))) } catch (_: Exception) {} }
        if (e.lat != null && e.lon != null) ab.setNegativeButton("Copier") { _, _ -> val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(android.content.ClipData.newPlainText("fiche", sb.toString())); Toast.makeText(this, "✔", Toast.LENGTH_SHORT).show() }
        ab.show()
    }

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
        fun texteP(e: Entree): String { val l = e.lieu.removePrefix("≈ ").trim(); val tag = if (l.isNotEmpty()) "#" + l.substringBefore(",").replace(Regex("[^\\p{L}\\p{N}]"), "") else ""
            return (if (l.isNotEmpty()) "📍 $l\n" else "") + (if (e.lat != null) String.format(Locale.US, "https://maps.google.com/?q=%f,%f", e.lat, e.lon) + "\n" else "") + tag }
        val bPart = btn("📤", "#1565C0") { try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, Uri.parse(liste[i].uri)).putExtra(Intent.EXTRA_TEXT, texteP(liste[i])).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), T.t("j_part"))) } catch (_: Exception) {} }
        val bTag = btn("#", "#6A1B9A") { val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(android.content.ClipData.newPlainText("lieu", texteP(liste[i]))); Toast.makeText(this, "✔", Toast.LENGTH_SHORT).show() }
        val bFiche = btn("ℹ", "#EF6C00") { fiche(liste[i]) }
        val bFerme = btn("✕", "#B71C1C") { d.dismiss() }
        val ligne = LinearLayout(d.context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(16)) }
        ligne.addView(bPrec, LinearLayout.LayoutParams(0, dp(54), 1f)); ligne.addView(bCarte, LinearLayout.LayoutParams(0, dp(54), 2.2f))
        ligne.addView(bFiche, LinearLayout.LayoutParams(0, dp(54), 1f)); ligne.addView(bPart, LinearLayout.LayoutParams(0, dp(54), 1f)); ligne.addView(bTag, LinearLayout.LayoutParams(0, dp(54), 1f)); ligne.addView(bSuiv, LinearLayout.LayoutParams(0, dp(54), 1f)); ligne.addView(bFerme, LinearLayout.LayoutParams(0, dp(54), 1f))
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
