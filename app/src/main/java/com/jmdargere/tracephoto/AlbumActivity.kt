package com.jmdargere.tracephoto

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class AlbumActivity : Activity() {
    private lateinit var photos: List<Entree>
    private val choisies = HashSet<String>()
    private lateinit var titre: TextView
    private lateinit var adapt: BaseAdapter
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = LruCache<String, Bitmap>(120)
    private val sp by lazy { getSharedPreferences("trace", MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bt(t: String, c: String, f: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 13f; isAllCaps = false
        setBackgroundColor(Color.parseColor(c)); setOnClickListener { f() }
    }

    private val R_URI = 0x7f0a0001
    private var nomAlbum: String? = null
    private val dossier get() = java.io.File(filesDir, "albums").apply { mkdirs() }
    private fun sur(n: String) = n.replace(Regex("[^A-Za-z0-9À-ÿ _-]"), "").trim().replace(' ', '_').ifEmpty { "album" }
    private fun base() = sur(nomAlbum ?: "album").let { if (it.startsWith("album")) it else "album_$it" }

    private fun enregistrer() {
        if (choisies.isEmpty()) { Toast.makeText(this, "Aucune photo choisie", Toast.LENGTH_SHORT).show(); return }
        val s = selection()
        val f = SimpleDateFormat("dd-MM", Locale.FRENCH)
        val et = EditText(this).apply { setText(nomAlbum ?: "Album ${f.format(s.first().t)} au ${f.format(s.last().t)}") }
        AlertDialog.Builder(this).setTitle("💾 Nom de l'album").setView(et)
            .setPositiveButton("Enregistrer") { _, _ ->
                val n = et.text.toString().trim().ifEmpty { "Album" }
                nomAlbum = n
                java.io.File(dossier, sur(n) + ".txt").writeText(n + "\n" + s.joinToString("\n") { it.uri })
                Toast.makeText(this, "Album « $n » enregistré (${s.size} photos)", Toast.LENGTH_LONG).show(); maj()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun mesAlbums() {
        val fs = dossier.listFiles { x -> x.name.endsWith(".txt") }?.sortedBy { it.name } ?: emptyList()
        if (fs.isEmpty()) { Toast.makeText(this, "Aucun album enregistré", Toast.LENGTH_SHORT).show(); return }
        val noms = fs.map { it.readLines().first() }
        AlertDialog.Builder(this).setTitle("📂 Mes albums")
            .setItems(noms.toTypedArray()) { _, i ->
                val l = fs[i].readLines(); val vus = l.drop(1).toSet()
                choisies.clear(); photos.filter { it.uri in vus }.forEach { choisies.add(it.uri) }
                nomAlbum = l.first(); maj()
                Toast.makeText(this, "« ${l.first()} » : ${choisies.size} photos retrouvées", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Supprimer un album") { _, _ ->
                AlertDialog.Builder(this).setTitle("Supprimer quel album ? (les photos ne sont pas touchées)")
                    .setItems(noms.toTypedArray()) { _, i -> fs[i].delete(); Toast.makeText(this, "Album supprimé", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("Annuler", null).show()
            }.setNegativeButton("Fermer", null).show()
    }

    private fun jours() {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US); val g = SimpleDateFormat("EEE d MMM", Locale.FRENCH)
        val parJour = photos.groupBy { f.format(it.t) }.toSortedMap()
        val cles = parJour.keys.toList()
        val coche = BooleanArray(cles.size) { i -> parJour[cles[i]]!!.all { it.uri in choisies } }
        AlertDialog.Builder(this).setTitle("📅 Choisir les jours")
            .setMultiChoiceItems(cles.map { "${g.format(parJour[it]!!.first().t)} (${parJour[it]!!.size})" }.toTypedArray(), coche) { _, i, v -> coche[i] = v }
            .setPositiveButton("OK") { _, _ ->
                choisies.clear(); cles.forEachIndexed { i, k -> if (coche[i]) parJour[k]!!.forEach { choisies.add(it.uri) } }
                nomAlbum = null; maj()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun mesPdf() {
        val l = ArrayList<Pair<Long, String>>()
        contentResolver.query(android.provider.MediaStore.Files.getContentUri("external"),
            arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
            "RELATIVE_PATH = ? AND DISPLAY_NAME LIKE ?", arrayOf("Documents/TRACE_PHOTO/", "%.pdf"),
            "date_added DESC")?.use { c -> while (c.moveToNext()) l.add(c.getLong(0) to c.getString(1)) }
        if (l.isEmpty()) { Toast.makeText(this, "Aucun PDF enregistré", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("📂 Mes PDF")
            .setItems(l.map { it.second }.toTypedArray()) { _, i ->
                val u = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Files.getContentUri("external"), l[i].first)
                AlertDialog.Builder(this).setTitle(l[i].second)
                    .setPositiveButton("Ouvrir") { _, _ ->
                        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) {}
                    }
                    .setNeutralButton("Partager") { _, _ ->
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partager"))
                    }
                    .setNegativeButton("Supprimer") { _, _ ->
                        AlertDialog.Builder(this).setTitle("Supprimer ce PDF ?").setMessage(l[i].second + "\n(les photos ne sont pas touchées)")
                            .setPositiveButton("Oui, supprimer") { _, _ ->
                                val ok = try { contentResolver.delete(u, null, null) > 0 } catch (_: Exception) { false }
                                Toast.makeText(this, if (ok) "PDF supprimé" else "Suppression impossible", Toast.LENGTH_SHORT).show()
                            }.setNegativeButton("Non", null).show()
                    }.show()
            }.setNegativeButton("Fermer", null).show()
    }

    private fun selection() = photos.filter { it.uri in choisies }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        photos = Journal.vivantes(this).filter { it.jpeg && it.t > 0 }.sortedBy { it.t }
        photos.forEach { choisies.add(it.uri) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        titre = TextView(this).apply { textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263238")); setPadding(dp(14), dp(14), dp(14), dp(14)) }
        root.addView(titre)
        val l1 = LinearLayout(this).apply { setPadding(dp(6), dp(4), dp(6), 0) }
        val lp = { LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) } }
        l1.addView(bt("✔ Tout", "#546E7A") { choisies.clear(); photos.forEach { choisies.add(it.uri) }; maj() }, lp())
        l1.addView(bt("✖ Aucune", "#546E7A") { choisies.clear(); maj() }, lp())
        root.addView(l1)
        root.addView(CheckBox(this).apply { text = "🥾 Ajouter ma trace GPS réelle"; textSize = 15f
            setPadding(dp(14), dp(6), dp(14), dp(6)); isChecked = sp.getBoolean("j_trace", false)
            setOnCheckedChangeListener { _, v -> sp.edit().putBoolean("j_trace", v).apply() } })
        val l2 = LinearLayout(this).apply { setPadding(dp(6), 0, dp(6), dp(4)) }
        l2.addView(bt("🗺 Carte (GPX)", "#00838F") { exporterCarte() }, lp())
        l2.addView(bt("📄 PDF", "#C62828") { exporterPdf() }, lp())
        l2.addView(bt("📂 Mes PDF", "#37474F") { mesPdf() }, lp())
        root.addView(l2)
        val l3 = LinearLayout(this).apply { setPadding(dp(6), 0, dp(6), dp(4)) }
        l3.addView(bt("📅 Jours", "#5D4037") { jours() }, lp())
        l3.addView(bt("💾 Enregistrer", "#2E7D32") { enregistrer() }, lp())
        l3.addView(bt("📂 Mes albums", "#6A1B9A") { mesAlbums() }, lp())
        root.addView(l3)
        root.addView(bt("📍 Retrouver les positions manquantes", "#455A64") {
            val n = Journal.completer(this)
            Toast.makeText(this, if (n > 0) "$n photo(s) placée(s) d'après la trace" else "Aucune photo à compléter (pas de trace pour ces heures)", Toast.LENGTH_LONG).show()
            photos = Journal.vivantes(this).filter { it.jpeg && it.t > 0 }.sortedBy { it.t }
            maj()
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(9), 0, dp(9), dp(4)) })
        val grille = GridView(this).apply { numColumns = 3; verticalSpacing = dp(4); horizontalSpacing = dp(4); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        adapt = object : BaseAdapter() {
            override fun getCount() = photos.size
            override fun getItem(i: Int) = photos[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                val e = photos[i]
                var h = v?.tag as? Array<View>
                val cell: View
                if (v == null || h == null) {
                    val img = ImageView(this@AlbumActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    val txt = TextView(this@AlbumActivity).apply { textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#99000000")); setPadding(dp(3), 0, dp(3), 0) }
                    val chk = TextView(this@AlbumActivity).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
                    val f = FrameLayout(this@AlbumActivity).apply { layoutParams = AbsListView.LayoutParams(-1, dp(120)) }
                    f.addView(img, FrameLayout.LayoutParams(-1, -1))
                    f.addView(txt, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    f.addView(chk, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(3), dp(3), 0) })
                    h = arrayOf(img, txt, chk)
                    f.tag = h; cell = f
                } else cell = v
                val img = h!![0] as ImageView; val txt = h[1] as TextView; val chk = h[2] as TextView
                txt.text = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH).format(e.t)
                val on = e.uri in choisies
                chk.text = if (on) "✓" else ""
                chk.setBackgroundColor(Color.parseColor(if (on) "#2E7D32" else "#66000000"))
                img.alpha = if (on) 1f else 0.4f
                img.setTag(R_URI, e.uri)
                val bm = cache.get(e.uri)
                if (bm != null) img.setImageBitmap(bm) else {
                    img.setImageDrawable(null)
                    pool.execute {
                        try {
                            val t = contentResolver.loadThumbnail(Uri.parse(e.uri), Size(256, 256), null)
                            cache.put(e.uri, t)
                            runOnUiThread { if (img.getTag(R_URI) == e.uri) img.setImageBitmap(t) }
                        } catch (_: Exception) {}
                    }
                }
                return cell
            }
        }
        grille.adapter = adapt
        grille.setOnItemClickListener { _, _, i, _ ->
            val u = photos[i].uri
            if (!choisies.remove(u)) choisies.add(u)
            maj()
        }
        root.addView(grille, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        maj()
    }

    private fun maj() { titre.text = "🖼 ${nomAlbum ?: "Album"} : ${choisies.size} / ${photos.size} photos"; adapt.notifyDataSetChanged() }

    private fun exporterCarte() {
        val s = selection()
        if (s.isEmpty()) { Toast.makeText(this, "Aucune photo choisie", Toast.LENGTH_SHORT).show(); return }
        val u = Album.carteGpx(this, s, sp.getBoolean("j_trace", false), base())
        if (u == null) { Toast.makeText(this, "Export impossible", Toast.LENGTH_LONG).show(); return }
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/gpx+xml").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Ouvrir la carte"))
        } catch (_: Exception) { Toast.makeText(this, "Carte enregistrée dans Documents/TRACE_PHOTO", Toast.LENGTH_LONG).show() }
    }

    private fun exporterPdf() {
        val s = selection()
        if (s.isEmpty()) { Toast.makeText(this, "Aucune photo choisie", Toast.LENGTH_SHORT).show(); return }
        val avec = sp.getBoolean("j_trace", false)
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = s.size + 1 }
        val tv = TextView(this).apply { text = "Préparation…"; textSize = 15f }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)); addView(tv); addView(pb) }
        val dlg = AlertDialog.Builder(this).setTitle("📄 Création du PDF").setView(box).setCancelable(false).show()
        Thread {
            val u = try { Album.pdf(this, s, avec, base()) { i, n, nom -> runOnUiThread { pb.progress = i; tv.text = "Photo $i / $n\n$nom" } } } catch (e: Throwable) { null }
            runOnUiThread {
                dlg.dismiss()
                if (u == null) { AlertDialog.Builder(this).setMessage("Échec de création du PDF").setPositiveButton("OK", null).show(); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("✅ TERMINÉ")
                    .setMessage("PDF enregistré dans Documents/TRACE_PHOTO\n${s.size} photos + carte en première page.")
                    .setPositiveButton("Ouvrir") { _, _ ->
                        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) {}
                    }
                    .setNeutralButton("Partager") { _, _ ->
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partager"))
                    }
                    .setNegativeButton("Fermer", null).show()
            }
        }.start()
    }
}
