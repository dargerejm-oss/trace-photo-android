package com.jmdargere.tracephoto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.util.TimeZone

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var etat: TextView
    private lateinit var btnTrace: Button
    private lateinit var btnGeo: Button
    private lateinit var carte: TextView
    private lateinit var barre: ProgressBar
    private lateinit var banniere: TextView
    private lateinit var bilanTxt: TextView
    private lateinit var etFuseau: EditText
    private lateinit var etDecal: EditText
    private lateinit var cbSans: CheckBox
    private lateinit var cbVois: CheckBox
    private lateinit var cbRappel: CheckBox
    private lateinit var cbProg: CheckBox
    private lateinit var etPd: EditText
    private lateinit var etPf: EditText
    private lateinit var sablier: android.webkit.WebView
    private lateinit var cbEco: CheckBox
    private lateinit var cbParApp: CheckBox
    private lateinit var etAlbum: EditText
    private val RC_CALIB = 4
    private lateinit var cbSup: CheckBox
    private var dernierGeo: Geoloc? = null
    private var dernierBilan: Bilan? = null
    private val RC_AUTH = 3
    private lateinit var cbLieu: CheckBox
    private lateinit var cbRen: CheckBox
    private lateinit var cbAuto: CheckBox
    private lateinit var cbCoord: CheckBox
    private lateinit var btnDossier: Button
    private lateinit var btnCarteVue: Button
    private var dernierArbre: Uri? = null
    private var dernierVisu: Uri? = null
    private lateinit var btnStop: Button
    private var travail: Geoloc? = null
    private var enCours = false
    private val RC_LOC = 1; private val RC_DOSSIER = 2
    private val sp by lazy { getSharedPreferences("trace", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fond(c: Int, r: Int = 18) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun gros(txt: String, c: Int): Button = Button(this).apply {
        text = txt; setTextColor(Color.WHITE); textSize = 19f; typeface = Typeface.DEFAULT_BOLD; background = fond(c); isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(-1, dp(86)).apply { topMargin = dp(10) }
    }
    private fun txt(t: String, sz: Float = 15f, c: Int = Color.parseColor("#263238")) = TextView(this).apply { text = t; textSize = sz; setTextColor(c) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e -> try { java.io.File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e)) } catch (_: Exception) {}; prev?.uncaughtException(th, e) }
        val sv = ScrollView(this); sv.setBackgroundColor(Color.parseColor("#ECEFF1"))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(30), dp(16), dp(30)) }
        sv.addView(root); setContentView(sv)

        root.addView(txt("📍 TRACE PHOTO", 28f, Color.parseColor("#C62828")).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(txt(T.t("versiontxt"), 14f, Color.parseColor("#455A64")))
        root.addView(txt("Instagram @jean_michel_dargere", 13f, Color.parseColor("#455A64")))
        root.addView(txt("📞 Tél +33 6 13 37 10 42 · ✉ dargerejm@gmail.com", 12f, Color.parseColor("#455A64")))
        root.addView(txt(T.t("sub"), 15f).apply { setPadding(0, dp(8), 0, dp(4)) })

        etat = txt("", 15f).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); background = fond(Color.WHITE) }
        root.addView(etat, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        btnTrace = gros(T.t("go"), Color.parseColor("#2E7D32")).also { it.setOnClickListener { basculeTrace() } }
        root.addView(btnTrace)

        root.addView(Button(this).apply { text = T.t("b_marque"); isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE); background = fond(Color.parseColor("#F9A825")); setOnClickListener { marquerIci() } }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        root.addView(Button(this).apply { text = T.t("b_histo"); isAllCaps = false; setOnClickListener { montrerHisto() } })
        carte = txt("", 14f).apply { setPadding(dp(4), dp(18), dp(4), dp(0)) }
        root.addView(carte)
        btnGeo = gros(T.t("geo"), Color.parseColor("#1565C0")).also { it.setOnClickListener { lancer(false) } }
        root.addView(btnGeo)
        root.addView(Button(this).apply { text = T.t("autre"); isAllCaps = false; setOnClickListener { lancer(true) } })

        barre = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE; max = 100 }
        root.addView(barre, LinearLayout.LayoutParams(-1, dp(26)).apply { topMargin = dp(10) })
        sablier = android.webkit.WebView(this).apply { visibility = View.GONE; setBackgroundColor(0); settings.javaScriptEnabled = true; loadUrl("file:///android_asset/hourglass.html") }
        root.addView(sablier, LinearLayout.LayoutParams(-1, dp(250)))
        btnStop = Button(this).apply { text = T.t("stop_trt"); setTextColor(Color.WHITE); background = fond(Color.parseColor("#B71C1C")); visibility = View.GONE
            setOnClickListener { travail?.annule = true } }
        root.addView(btnStop, LinearLayout.LayoutParams(-1, dp(56)))
        banniere = txt("", 26f, Color.WHITE).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; visibility = View.GONE; setPadding(0, dp(18), 0, dp(18)) }
        root.addView(banniere, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        bilanTxt = txt("", 16f).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); background = fond(Color.WHITE); visibility = View.GONE }
        root.addView(bilanTxt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        btnDossier = gros(T.t("b_visio"), Color.parseColor("#6A1B9A")).apply { setOnClickListener { startActivity(Intent(this@MainActivity, ViewerActivity::class.java)) } }
        root.addView(btnDossier)
        btnCarteVue = gros(T.t("b_carte"), Color.parseColor("#00838F")).apply { visibility = View.GONE; setOnClickListener { ouvrirCarte() } }
        root.addView(btnCarteVue)
        root.addView(gros(T.t("b_nettoie"), Color.parseColor("#B71C1C")).apply { setOnClickListener { nettoyerCarte() } })
        root.addView(gros(T.t("b_journal"), Color.parseColor("#EF6C00")).apply { setOnClickListener { startActivity(Intent(this@MainActivity, JournalActivity::class.java)) } })
        root.addView(txt(T.t("reglages"), 18f, Color.parseColor("#1565C0")).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(26), 0, dp(4)) })
        root.addView(txt(T.t("fuseau"), 13f))
        etFuseau = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(sp.getString("fuseau", "")) }
        root.addView(etFuseau)
        root.addView(txt(T.t("decal"), 13f))
        etDecal = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setText(sp.getString("decal", "")) }
        root.addView(etDecal)
        root.addView(Button(this).apply { text = T.t("calib"); isAllCaps = false; setOnClickListener { calibrer() } })
        cbSup = CheckBox(this).apply { text = T.t("o_sup"); isChecked = sp.getBoolean("sup", true) }; root.addView(cbSup)
        cbSans = CheckBox(this).apply { text = T.t("o_sans"); isChecked = sp.getBoolean("sans", true) }; root.addView(cbSans)
        cbLieu = CheckBox(this).apply { text = T.t("o_lieu"); isChecked = sp.getBoolean("lieu", true) }; root.addView(cbLieu)
        cbRen = CheckBox(this).apply { text = T.t("o_ren"); isChecked = sp.getBoolean("ren", true) }; root.addView(cbRen)
        cbCoord = CheckBox(this).apply { text = T.t("o_coord"); isChecked = sp.getBoolean("coord", true) }; root.addView(cbCoord)
        cbVois = CheckBox(this).apply { text = T.t("o_vois"); isChecked = sp.getBoolean("vois", true) }; root.addView(cbVois)
        cbEco = CheckBox(this).apply { text = T.t("o_eco"); isChecked = sp.getBoolean("eco", true) }; root.addView(cbEco)
        cbParApp = CheckBox(this).apply { text = T.t("o_parapp"); isChecked = sp.getBoolean("parapp", false) }; root.addView(cbParApp)
        root.addView(txt(T.t("album"), 13f))
        etAlbum = EditText(this).apply { setText(sp.getString("album", "Fuji X4")); setSingleLine() }; root.addView(etAlbum)
        cbRappel = CheckBox(this).apply { text = T.t("o_rappel"); isChecked = sp.getBoolean("rappel", false)
            setOnCheckedChangeListener { _, on -> sp.edit().putBoolean("rappel", on).apply(); Rappel.armer(this@MainActivity, on) } }; root.addView(cbRappel)
        cbProg = CheckBox(this).apply { text = T.t("o_prog"); isChecked = sp.getBoolean("prog", false)
            setOnCheckedChangeListener { _, on -> sp.edit().putBoolean("prog", on).apply(); if (on) { demanderToujours(); if (Programme.dedans(this@MainActivity) && !TraceService.actif) basculeTrace() }; Programme.armer(this@MainActivity) } }; root.addView(cbProg)
        val lp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        etPd = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(sp.getInt("prog_d", 8).toString()); setSingleLine(); gravity = Gravity.CENTER }
        etPf = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(sp.getInt("prog_f", 20).toString()); setSingleLine(); gravity = Gravity.CENTER }
        lp.addView(txt(T.t("prog_de"), 14f)); lp.addView(etPd, LinearLayout.LayoutParams(dp(70), -2)); lp.addView(txt(T.t("prog_a"), 14f)); lp.addView(etPf, LinearLayout.LayoutParams(dp(70), -2)); lp.addView(txt(" h", 14f))
        root.addView(lp)
        root.addView(Button(this).apply { text = T.t("b_toujours"); isAllCaps = false; setOnClickListener { demanderToujours(true) } })
        cbAuto = CheckBox(this).apply { text = T.t("o_auto"); isChecked = sp.getBoolean("auto", false) }; root.addView(cbAuto)
        root.addView(Button(this).apply { text = T.t("batt"); isAllCaps = false
            setOnClickListener { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {} } })
        onNewIntent(intent)
        root.addView(txt(T.t("garde2") + "\n" + T.t("credit"), 12f, Color.parseColor("#546E7A")).apply { setPadding(0, dp(12), 0, 0) })
    }


    private var calibDlg: android.app.AlertDialog? = null
    private fun calibrer() {
        val tv = TextView(this).apply { textSize = 44f; gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE; setPadding(0, dp(24), 0, dp(24)) }
        val fm = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
        val run = object : Runnable { override fun run() { tv.text = fm.format(java.util.Date()); h.postDelayed(this, 100) } }
        h.post(run)
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
            addView(TextView(this@MainActivity).apply { text = T.t("calib_1") }); addView(tv) }
        calibDlg = android.app.AlertDialog.Builder(this).setTitle(T.t("calib")).setView(lay)
            .setPositiveButton(T.t("calib_suite")) { _, _ -> h.removeCallbacks(run)
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/jpeg" }
                startActivityForResult(i, RC_CALIB) }
            .setNegativeButton("✕") { _, _ -> h.removeCallbacks(run) }.setOnCancelListener { h.removeCallbacks(run) }.show()
    }

    private fun calibPhoto(u: Uri) {
        val naif: java.time.LocalDateTime? = try {
            contentResolver.openFileDescriptor(u, "r")!!.use { p ->
                val x = androidx.exifinterface.media.ExifInterface(p.fileDescriptor)
                val s = x.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: x.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                s?.let { java.time.LocalDateTime.parse(it.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")) }
            }
        } catch (_: Exception) { null }
        if (naif == null) { Toast.makeText(this, T.t("calib_err"), Toast.LENGTH_LONG).show(); return }
        val img = ImageView(this).apply { adjustViewBounds = true; setImageURI(u) }
        val et = EditText(this).apply { hint = "HH:MM:SS"; inputType = InputType.TYPE_CLASS_DATETIME; setSingleLine() }
        val hms = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        val apercu = TextView(this).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, 0) }
        // lit "14:32:05", "14 32 05", "143205" ou "14:32" (secondes = 0)
        fun lire(sx: String): java.time.LocalTime? {
            val n = Regex("\\d+").findAll(sx).map { it.value }.toList()
            val v = if (n.size == 1 && n[0].length == 6) listOf(n[0].substring(0, 2), n[0].substring(2, 4), n[0].substring(4, 6)) else n
            if (v.size < 2) return null
            return try { java.time.LocalTime.of(v[0].toInt(), v[1].toInt(), if (v.size > 2) v[2].toInt() else 0) } catch (_: Exception) { null }
        }
        fun ecart(sx: String): Long? {
            val saisi = lire(sx) ?: return null
            var d = java.time.Duration.between(saisi, naif.toLocalTime()).seconds
            if (d > 43200) d -= 86400; if (d < -43200) d += 86400
            return d
        }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(x: android.text.Editable?) {
                val d = ecart(x.toString())
                apercu.text = if (d == null) "" else "Écart calculé : %d s (%d min %02d s)".format(d, Math.abs(d) / 60, Math.abs(d) % 60)
            }
        })
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
            addView(img, LinearLayout.LayoutParams(-1, dp(220)))
            addView(TextView(this@MainActivity).apply { text = T.f("calib_2", naif.toLocalTime().format(hms)); textSize = 16f })
            addView(et); addView(apercu) }
        android.app.AlertDialog.Builder(this).setTitle(T.t("calib")).setView(lay)
            .setPositiveButton("OK") { _, _ ->
                val d = ecart(et.text.toString())
                if (d == null) { Toast.makeText(this, T.t("calib_err"), Toast.LENGTH_LONG).show() }
                else { etDecal.setText(d.toString()); sauveReglages(); Toast.makeText(this, T.f("calib_ok", d), Toast.LENGTH_LONG).show() } }
            .setNegativeButton("✕", null).show()
    }

    private fun hg(i: Int, n: Int) { val pct = if (n > 0) i * 100.0 / n else 0.0; sablier.evaluateJavascript("setP(%.1f,true)".format(java.util.Locale.US, pct), null) }

    private var autoFait = false
    private fun montrerCrash() {
        val f = java.io.File(filesDir, "crash.txt"); if (!f.exists()) return
        val s = f.readText().take(1500); f.delete()
        android.app.AlertDialog.Builder(this).setTitle("Erreur précédente").setMessage(s).setPositiveButton("OK", null).show()
    }

    override fun onResume() {
        super.onResume(); tick(); montrerCrash()
        if (!autoFait && sp.getBoolean("auto", false) && !enCours && carteDetectee() != null && sp.getString("arbre", null) != null) { autoFait = true; lancer(false) }
    }
    override fun onPause() { super.onPause(); h.removeCallbacksAndMessages(null); sauveReglages() }

    private fun sauveReglages() { sp.edit().putString("fuseau", etFuseau.text.toString()).putString("decal", etDecal.text.toString()).putBoolean("sans", cbSans.isChecked).putBoolean("sup", cbSup.isChecked).putBoolean("lieu", cbLieu.isChecked).putBoolean("ren", cbRen.isChecked).putBoolean("auto", cbAuto.isChecked).putBoolean("coord", cbCoord.isChecked).putBoolean("vois", cbVois.isChecked).putBoolean("eco", cbEco.isChecked).putBoolean("parapp", cbParApp.isChecked).putString("album", etAlbum.text.toString().trim()).putInt("prog_d", (etPd.text.toString().toIntOrNull() ?: 8).coerceIn(0, 23)).putInt("prog_f", (etPf.text.toString().toIntOrNull() ?: 20).coerceIn(0, 23)).apply(); Programme.armer(this) }

    private fun tick() {
        val on = TraceService.actif
        btnTrace.text = T.t(if (on) "stop" else "go")
        btnTrace.background = fond(Color.parseColor(if (on) "#C62828" else "#2E7D32"))
        if (on) {
            val d = if (TraceService.dernierFix == 0L) T.t("fix_attente") else ago((System.currentTimeMillis() - TraceService.dernierFix) / 1000)
            etat.text = T.f("etat_on", TraceService.points, d)
        } else {
            val fs = Trace.dir(this).listFiles { x -> x.name.endsWith(".gpx") } ?: emptyArray()
            etat.text = T.f("etat_off", fs.size, fs.sumOf { Trace.lireFichierCompte(it) })
        }
        val v = carteDetectee()
        carte.text = when { enCours -> carte.text; sp.getString("arbre", null) != null -> T.f("carte_ok", sp.getString("nomarbre", "") ?: "") ; v != null -> T.f("carte_ok", v.getDescription(this)); else -> T.t("carte_non") }
        h.postDelayed({ tick() }, 3000)
    }
    private fun ago(s: Long) = if (s < 90) T.f("sec", s) else T.f("min", s / 60)

    private fun carteDetectee(): android.os.storage.StorageVolume? {
        val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.storageVolumes.firstOrNull { it.isRemovable && it.state == "mounted" }
    }

    // ---- trace ----
    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (i?.action == "com.jmdargere.tracephoto.MARQUER") { i.action = null; h.postDelayed({ marquerIci() }, 400) }
        if (i?.action == "com.jmdargere.tracephoto.DEMARRER") { i.action = null; if (!TraceService.actif) h.postDelayed({ basculeTrace() }, 400) }
    }
    private fun basculeTrace() {
        if (TraceService.actif) { startService(Intent(this, TraceService::class.java).setAction("STOP")); h.postDelayed({ tick() }, 800); return }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { Toast.makeText(this, T.t("perm"), Toast.LENGTH_LONG).show(); requestPermissions(need.toTypedArray(), RC_LOC); return }
        demarre()
    }
    private fun demarre() { startForegroundService(Intent(this, TraceService::class.java)); h.postDelayed({ tick() }, 800) }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (rc == RC_LOC && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) demarre()
    }

    // ---- géolocalisation ----
    private fun lancer(choisir: Boolean) {
        if (enCours) return
        sauveReglages()
        val s = sp.getString("arbre", null)
        if (!choisir && s != null) {
            val u = Uri.parse(s)
            if (accessible(u)) { demarreTri(u); return }
        }
        val v = carteDetectee()
        val it = if (!choisir && v != null && Build.VERSION.SDK_INT >= 29) v.createOpenDocumentTreeIntent() else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        Toast.makeText(this, T.t("carte_choisir"), Toast.LENGTH_LONG).show()
        startActivityForResult(it, RC_DOSSIER)
    }
    private fun accessible(u: Uri) = try {
        contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } &&
        contentResolver.query(android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(u, android.provider.DocumentsContract.getTreeDocumentId(u)), arrayOf("document_id"), null, null, null)?.use { true } == true
    } catch (_: Exception) { false }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        if (rc == RC_CALIB) { if (res == RESULT_OK && d?.data != null) calibPhoto(d.data!!); return }
        if (rc == RC_AUTH) { if (res == RESULT_OK) supprimerMaintenant(); return }
        if (rc == RC_DOSSIER && res == RESULT_OK && d?.data != null) {
            val u = d.data!!
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            sp.edit().putString("arbre", u.toString()).putString("nomarbre", android.provider.DocumentsContract.getTreeDocumentId(u)).apply()
            demarreTri(u)
        }
    }

    private fun demarreTri(u: Uri) {
        enCours = true
        banniere.visibility = View.GONE; bilanTxt.visibility = View.GONE
        barre.visibility = View.VISIBLE; sablier.visibility = View.VISIBLE; barre.isIndeterminate = true; btnStop.visibility = View.VISIBLE
        btnGeo.isEnabled = false
        carte.text = T.t("lecture")
        val auto = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        val fz = etFuseau.text.toString().trim().toDoubleOrNull()
        val offSec = if (fz != null) (fz * 3600).toInt() else auto
        val dec = etDecal.text.toString().trim().toIntOrNull() ?: 0
        if (TraceService.actif) { startService(Intent(this, TraceService::class.java).setAction("STOP")) }
        val g = Geoloc(this, u, offSec, dec, cbLieu.isChecked, cbRen.isChecked, cbCoord.isChecked, cbSans.isChecked, fz == null, cbVois.isChecked, etAlbum.text.toString().trim().ifBlank { "Fuji X4" }, cbParApp.isChecked, Lieux.disponible(this)); travail = g; dernierGeo = g; dernierBilan = null
        dernierArbre = u; dernierVisu = null; btnCarteVue.visibility = View.GONE
        Thread {
            if (TraceService.actif) Thread.sleep(1500)
            val pts = Trace.lireTout(this)
            if (pts.isEmpty()) { h.post { fin(null, T.t("pas_trace"), "#E65100") }; return@Thread }
            var lieuxOk = false
            if (cbLieu.isChecked || cbRen.isChecked) { h.post { carte.text = T.t("dl_lieux") }; lieuxOk = Lieux.assurer(this); g.lieuxOk = lieuxOk }
            h.post { carte.text = T.t("recherche") }
            val liste = g.lister()
            if (liste.isEmpty()) { h.post { fin(null, T.t("pas_photo"), "#E65100") }; return@Thread }
            h.post { barre.isIndeterminate = false; barre.max = liste.size }
            val b = g.traiter(liste, pts) { i, n -> h.post { barre.progress = i; hg(i, n); carte.text = T.f("traite", i, n) } }
            dernierVisu = Trace.exporterVisu(this, pts, b.wpts)
            h.post { fin(b, T.t(if (g.annule) "arrete" else "fini"), if (g.annule) "#E65100" else "#2E7D32") }
        }.start()
    }

    private fun ouvrirDossier() {
        val t = dernierArbre ?: return
        val d = android.provider.DocumentsContract.buildDocumentUriUsingTree(t, android.provider.DocumentsContract.getTreeDocumentId(t))
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(d, "vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra("android.provider.extra.INITIAL_URI", d)) } catch (_: Exception) {}
        }
    }

    private fun ouvrirCarte() {
        val u = dernierVisu ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/gpx+xml").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, T.t("carte_info")))
        } catch (_: Exception) { Toast.makeText(this, T.t("pas_carte"), Toast.LENGTH_LONG).show() }
    }

    private fun proposerSuppression() {
        val b = dernierBilan ?: return
        if (!cbSup.isChecked || b.aSupprimer.isEmpty() || dernierGeo?.annule == true) {
            val why = when { !cbSup.isChecked -> T.t("sup_why_opt"); dernierGeo?.annule == true -> T.t("sup_why_stop"); else -> T.t("sup_why_rien") }
            bilanTxt.text = bilanTxt.text.toString() + "\n\nℹ " + why; return
        }
        android.app.AlertDialog.Builder(this).setTitle(T.t("sup_titre")).setMessage(T.f("sup_msg", b.aSupprimer.size))
            .setPositiveButton(T.t("sup_oui")) { _, _ ->
                val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                if (!km.isDeviceSecure) { Toast.makeText(this, T.t("sup_pas_verrou"), Toast.LENGTH_LONG).show() }
                else {
                    @Suppress("DEPRECATION") val i = km.createConfirmDeviceCredentialIntent(T.t("sup_auth_t"), T.t("sup_auth_d"))
                    if (i != null) startActivityForResult(i, RC_AUTH) else Toast.makeText(this, T.t("sup_pas_verrou"), Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton(T.t("sup_non"), null).setCancelable(false).show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun marquerIci() {
        var l: android.location.Location? = TraceService.dernierLoc
        if (l == null || System.currentTimeMillis() - l.time > 120_000L) try { val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val g = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (g != null && (l == null || g.time > l.time)) l = g } catch (_: Exception) {}
        if (l == null) { Toast.makeText(this, T.t("marque_non"), Toast.LENGTH_LONG).show(); return }
        Trace.marquer(this, l.latitude, l.longitude, System.currentTimeMillis())
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
        Toast.makeText(this, T.f("marque_ok", l.latitude, l.longitude), Toast.LENGTH_SHORT).show()
    }

    private fun montrerHisto() {
        val tx = Trace.lireEvt(this).ifBlank { T.t("histo_vide") }
        val tv = TextView(this).apply { text = tx; textSize = 13f; typeface = Typeface.MONOSPACE; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        android.app.AlertDialog.Builder(this).setTitle(T.t("b_histo")).setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("OK", null).show()
    }

    private fun demanderToujours(force: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 29 && (force || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, T.t("toujours_msg"), Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    private fun nettoyerCarte() {
        if (enCours) return
        val s = sp.getString("arbre", null)
        if (s == null || !accessible(Uri.parse(s))) { Toast.makeText(this, T.t("carte_choisir"), Toast.LENGTH_LONG).show(); lancer(true); return }
        enCours = true; carte.text = T.t("recherche"); barre.visibility = View.VISIBLE; barre.isIndeterminate = true
        val u = Uri.parse(s)
        val g = Geoloc(this, u, 0, 0, false, false, false, true, false, false, "Fuji X4", false, false)
        Thread {
            val b = try { g.preparerSuppression(g.lister()) } catch (_: Exception) { Bilan() }
            h.post {
                enCours = false; barre.visibility = View.GONE; carte.text = ""
                dernierGeo = g; dernierBilan = b
                bilanTxt.text = T.f("sup_trouves", b.aSupprimer.size); bilanTxt.visibility = View.VISIBLE
                proposerSuppression()
            }
        }.start()
    }

    private fun supprimerMaintenant() {
        val g = dernierGeo ?: return; val b = dernierBilan ?: return
        enCours = true; barre.visibility = View.VISIBLE; sablier.visibility = View.VISIBLE; barre.isIndeterminate = false; barre.max = b.aSupprimer.size
        Thread {
            g.annule = false
            g.supprimerOriginaux(b) { i, n -> h.post { barre.progress = i; hg(i, n); carte.text = T.f("traite", i, n) } }
            val reste = try { g.lister().size } catch (_: Exception) { -1 }
            h.post {
                enCours = false; barre.visibility = View.GONE; sablier.visibility = View.GONE; carte.text = ""
                var t = bilanTxt.text.toString() + T.f("bilan_sup", b.supprimees) + (if (reste >= 0) "\n🔎 " + T.f("sup_reste", reste) else "")
                if (b.echecSupp > 0) t += T.f("bilan_sup_echec", b.echecSupp) + (if (b.errSupp.isNotEmpty()) "\n⚠ " + b.errSupp else "") + (if (b.copieAbsente > 0) "\n⚠ " + T.f("sup_copie_abs", b.copieAbsente) else "")
                bilanTxt.text = t; b.aSupprimer.clear()
                banniere.text = T.t("fini"); banniere.background = fond(Color.parseColor("#2E7D32"))
                try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_ACK, 600) } catch (_: Exception) {}
            }
        }.start()
    }

    private fun fin(b: Bilan?, titre: String, couleur: String) {
        enCours = false; btnGeo.isEnabled = true; barre.visibility = View.GONE; sablier.visibility = View.GONE; btnStop.visibility = View.GONE
        banniere.text = titre; banniere.background = fond(Color.parseColor(couleur)); banniere.visibility = View.VISIBLE
        if (b != null) {
            var t = T.f("bilan", b.importees, b.avecPos, b.verifiees, b.sansPos, b.deja, b.sansDate, b.erreurs)
            if (b.estimees > 0) t += T.f("bilan_est", b.estimees)
            if (b.lieux.isNotEmpty()) t += T.f("bilan_lieux", b.lieux.entries.sortedByDescending { it.value }.joinToString("\n") { "• ${it.key} : ${it.value}" })
            else if ((cbLieu.isChecked || cbRen.isChecked) && !Lieux.disponible(this)) t += "\n\n" + T.t("pas_lieux")
            bilanTxt.text = t; bilanTxt.visibility = View.VISIBLE
            dernierBilan = b
        }
        carte.text = ""
        if (b != null && dernierVisu != null) btnCarteVue.visibility = View.VISIBLE
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_ACK, 600) } catch (_: Exception) {}
        if (b != null) h.postDelayed({ proposerSuppression() }, 700)
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
        try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
    }
}
