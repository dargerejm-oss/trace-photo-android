package com.jmdargere.tracephoto

import java.util.Locale

/** Textes : français par défaut, anglais si le téléphone est en anglais (autres langues à venir). */
object T {
    private val fr = mapOf(
        "notif" to "Trace en cours — touche pour ouvrir",
        "sub" to "Suis ta position, géolocalise tes photos",
        "go" to "▶  DÉMARRER LA TRACE",
        "stop" to "⏹  ARRÊTER LA TRACE",
        "etat_on" to "● Trace en cours : %d points\nDernière position il y a %s",
        "etat_off" to "Trace arrêtée.\n%d trace(s) enregistrée(s), %d points au total.",
        "fix_attente" to "en attente du GPS…",
        "sec" to "%d s", "min" to "%d min",
        "geo" to "📷  GÉOLOCALISER LES PHOTOS DE LA CARTE",
        "carte_ok" to "Carte détectée : %s",
        "carte_non" to "Aucune carte détectée : branche le lecteur de carte.",
        "carte_choisir" to "Choisis la carte (touche « Utiliser cet espace »).",
        "autre" to "📁 Choisir un autre dossier",
        "reglages" to "Réglages",
        "fuseau" to "Heure de l'appareil photo : UTC + (heures, vide = fuseau du téléphone)",
        "decal" to "Décalage de l'horloge de l'appareil (secondes, +/-)",
        "remplacer" to "Remplacer les positions déjà présentes",
        "batt" to "🔋 Autoriser en arrière-plan (Xiaomi)",
        "stop_trt" to "⏹ STOP",
        "lecture" to "Lecture des traces…",
        "recherche" to "Recherche des photos…",
        "traite" to "Photo %d / %d",
        "pas_trace" to "Aucune trace enregistrée : démarre d'abord une trace.",
        "pas_photo" to "Aucune photo trouvée dans ce dossier.",
        "fini" to "✅  TERMINÉ",
        "arrete" to "⏹  ARRÊTÉ",
        "bilan" to "📍 %d photo(s) géolocalisée(s)\n⏭ %d déjà géolocalisée(s)\n❓ %d hors de la trace (pas de position)\n📄 %d fichier(s) XMP créé(s) pour les RAW\n⚠ %d RAW avec XMP déjà présent (non touchés)\n🕓 %d sans date de prise de vue\n❌ %d erreur(s)",
        "perm" to "La permission de position est nécessaire.",
        "perm_bg" to "Pour une trace fiable, choisis « Toujours autoriser » ou « Autoriser tout le temps ».",
        "versiontxt" to "par JM.DARGERE",
        "garde" to "Les originaux JPEG reçoivent la position dans leurs données EXIF. Les RAW ne sont jamais modifiés : un petit fichier .xmp est créé à côté.",
    )
    private val en = mapOf(
        "notif" to "Recording track — tap to open",
        "sub" to "Track your position, geotag your photos",
        "go" to "▶  START TRACK",
        "stop" to "⏹  STOP TRACK",
        "etat_on" to "● Recording: %d points\nLast position %s ago",
        "etat_off" to "Track stopped.\n%d track(s) saved, %d points in total.",
        "fix_attente" to "waiting for GPS…",
        "sec" to "%d s", "min" to "%d min",
        "geo" to "📷  GEOTAG THE CARD'S PHOTOS",
        "carte_ok" to "Card detected: %s",
        "carte_non" to "No card detected: plug in the card reader.",
        "carte_choisir" to "Pick the card (tap “Use this folder”).",
        "autre" to "📁 Choose another folder",
        "reglages" to "Settings",
        "fuseau" to "Camera time zone: UTC + (hours, empty = phone's zone)",
        "decal" to "Camera clock offset (seconds, +/-)",
        "remplacer" to "Replace positions already present",
        "batt" to "🔋 Allow in background (Xiaomi)",
        "stop_trt" to "⏹ STOP",
        "lecture" to "Reading tracks…",
        "recherche" to "Looking for photos…",
        "traite" to "Photo %d / %d",
        "pas_trace" to "No track recorded: start a track first.",
        "pas_photo" to "No photo found in this folder.",
        "fini" to "✅  DONE",
        "arrete" to "⏹  STOPPED",
        "bilan" to "📍 %d photo(s) geotagged\n⏭ %d already geotagged\n❓ %d outside the track (no position)\n📄 %d XMP file(s) created for RAW\n⚠ %d RAW with existing XMP (untouched)\n🕓 %d without capture date\n❌ %d error(s)",
        "perm" to "Location permission is required.",
        "perm_bg" to "For a reliable track, choose “Allow all the time”.",
        "versiontxt" to "by JM.DARGERE",
        "garde" to "JPEG originals get the position in their EXIF data. RAW files are never modified: a small .xmp file is created next to them.",
    )
    fun t(k: String): String {
        val m = if (Locale.getDefault().language == "fr") fr else en
        return m[k] ?: fr[k] ?: k
    }
    fun f(k: String, vararg a: Any): String = String.format(t(k), *a)
}
