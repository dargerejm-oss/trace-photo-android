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
    private lateinit var cbRemp: CheckBox
    private lateinit var cbLieu: CheckBox
    private lateinit var cbRen: CheckBox
    private lateinit var cbAuto: CheckBox
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

        carte = txt("", 14f).apply { setPadding(dp(4), dp(18), dp(4), dp(0)) }
        root.addView(carte)
        btnGeo = gros(T.t("geo"), Color.parseColor("#1565C0")).also { it.setOnClickListener { lancer(false) } }
        root.addView(btnGeo)
        root.addView(Button(this).apply { text = T.t("autre"); isAllCaps = false; setOnClickListener { lancer(true) } })

        barre = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE; max = 100 }
        root.addView(barre, LinearLayout.LayoutParams(-1, dp(26)).apply { topMargin = dp(10) })
        btnStop = Button(this).apply { text = T.t("stop_trt"); setTextColor(Color.WHITE); background = fond(Color.parseColor("#B71C1C")); visibility = View.GONE
            setOnClickListener { travail?.annule = true } }
        root.addView(btnStop, LinearLayout.LayoutParams(-1, dp(56)))
        banniere = txt("", 26f, Color.WHITE).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; visibility = View.GONE; setPadding(0, dp(18), 0, dp(18)) }
        root.addView(banniere, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        bilanTxt = txt("", 16f).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); background = fond(Color.WHITE); visibility = View.GONE }
        root.addView(bilanTxt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(txt(T.t("reglages"), 18f, Color.parseColor("#1565C0")).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(26), 0, dp(4)) })
        root.addView(txt(T.t("fuseau"), 13f))
        etFuseau = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(sp.getString("fuseau", "")) }
        root.addView(etFuseau)
        root.addView(txt(T.t("decal"), 13f))
        etDecal = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setText(sp.getString("decal", "")) }
        root.addView(etDecal)
        cbRemp = CheckBox(this).apply { text = T.t("remplacer"); isChecked = sp.getBoolean("remp", false) }
        root.addView(cbRemp)
        cbLieu = CheckBox(this).apply { text = T.t("o_lieu"); isChecked = sp.getBoolean("lieu", true) }; root.addView(cbLieu)
        cbRen = CheckBox(this).apply { text = T.t("o_ren"); isChecked = sp.getBoolean("ren", false) }; root.addView(cbRen)
        cbAuto = CheckBox(this).apply { text = T.t("o_auto"); isChecked = sp.getBoolean("auto", false) }; root.addView(cbAuto)
        root.addView(Button(this).apply { text = T.t("batt"); isAllCaps = false
            setOnClickListener { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {} } })
        root.addView(txt(T.t("garde") + "\n" + T.t("credit"), 12f, Color.parseColor("#546E7A")).apply { setPadding(0, dp(12), 0, 0) })
    }

    private var autoFait = false
    override fun onResume() {
        super.onResume(); tick()
        if (!autoFait && sp.getBoolean("auto", false) && !enCours && carteDetectee() != null && sp.getString("arbre", null) != null) { autoFait = true; lancer(false) }
    }
    override fun onPause() { super.onPause(); h.removeCallbacksAndMessages(null); sauveReglages() }

    private fun sauveReglages() { sp.edit().putString("fuseau", etFuseau.text.toString()).putString("decal", etDecal.text.toString()).putBoolean("remp", cbRemp.isChecked).putBoolean("lieu", cbLieu.isChecked).putBoolean("ren", cbRen.isChecked).putBoolean("auto", cbAuto.isChecked).apply() }

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
        barre.visibility = View.VISIBLE; barre.isIndeterminate = true; btnStop.visibility = View.VISIBLE
        btnGeo.isEnabled = false
        carte.text = T.t("lecture")
        val auto = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        val fz = etFuseau.text.toString().trim().toDoubleOrNull()
        val offSec = if (fz != null) (fz * 3600).toInt() else auto
        val dec = etDecal.text.toString().trim().toIntOrNull() ?: 0
        if (TraceService.actif) { startService(Intent(this, TraceService::class.java).setAction("STOP")) }
        val g = Geoloc(this, u, offSec, dec, cbRemp.isChecked, cbLieu.isChecked, cbRen.isChecked, Lieux.disponible(this)); travail = g
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
            val b = g.traiter(liste, pts) { i, n -> h.post { barre.progress = i; carte.text = T.f("traite", i, n) } }
            h.post { fin(b, T.t(if (g.annule) "arrete" else "fini"), if (g.annule) "#E65100" else "#2E7D32") }
        }.start()
    }

    private fun fin(b: Bilan?, titre: String, couleur: String) {
        enCours = false; btnGeo.isEnabled = true; barre.visibility = View.GONE; btnStop.visibility = View.GONE
        banniere.text = titre; banniere.background = fond(Color.parseColor(couleur)); banniere.visibility = View.VISIBLE
        if (b != null) {
            var t = T.f("bilan", b.geo, b.deja, b.hors, b.xmp, b.xmpExiste, b.sansDate, b.erreurs)
            if (b.lieux.isNotEmpty()) t += T.f("bilan_lieux", b.lieux.entries.sortedByDescending { it.value }.joinToString("\n") { "• ${it.key} : ${it.value}" })
            else if ((cbLieu.isChecked || cbRen.isChecked) && !Lieux.disponible(this)) t += "\n\n" + T.t("pas_lieux")
            if (b.renomme > 0) t += T.f("bilan_ren", b.renomme)
            bilanTxt.text = t; bilanTxt.visibility = View.VISIBLE
        }
        carte.text = ""
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_ACK, 600) } catch (_: Exception) {}
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
        try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
    }
}
