package com.kairos.tv

/**
 * Riquadro della qualità nel lettore («FHD · 1080p · 50 fps» in alto a destra sui canali in diretta): come lo vedono i
 * clienti lo decide il pannello, per tutte le app Kairos insieme, con la voce rc["qbox"] del controllo remoto.
 * - "off": il riquadro non compare mai, né all'apertura del canale, né al cambio di risoluzione, né con la barra
 *   informazioni: come con il permesso «resInd» del cliente bloccato.
 * - "noq": il riquadro resta, senza la sigla di qualità (4K, UHD, FHD, HD, SD): «1080p · 50 fps».
 * - "full", vuoto, assente o qualsiasi altro valore: come sempre.
 * Il permesso «resInd» bloccato lo nasconde comunque. Nomi dei canali, avvisi, qualità adattiva e dati per il pannello
 * (risoluzione e FPS in «Online ora», statistiche) non cambiano. Logica pura (niente Android), provata in QBoxTest.
 */
object QBox {
    const val FULL = "full"
    const val NOQ = "noq"
    const val OFF = "off"

    /** voce rc["qbox"] ridotta ai tre casi: cambiano qualcosa solo "off" e "noq", il resto (null e vuoto compresi) è [FULL] */
    fun mode(qbox: String?): String = when (qbox) { OFF -> OFF; NOQ -> NOQ; else -> FULL }

    /** il riquadro può comparire: permesso «resInd» del cliente non bloccato ([perm]) e voce [qbox] diversa da "off" */
    fun shown(perm: Boolean, qbox: String?): Boolean = perm && mode(qbox) != OFF

    /** sigla di qualità dall'altezza del video */
    fun tag(h: Int): String = when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; else -> "SD" }

    /**
     * Testo del riquadro: sigla [q], righe [h] e fotogrammi al secondo [fps] (già scritti, es. "50" o "29,97"; vuoto = non
     * noti) uniti da « · »; con [qbox] "noq" senza la sigla. Le parti vuote si saltano: mai « · » doppi, all'inizio o alla
     * fine. Altezza non nota (h <= 0): nessun riquadro ("").
     */
    fun text(q: String, h: Int, fps: String, qbox: String?): String {
        if (h <= 0) return ""
        val parts = ArrayList<String>(3)
        if (mode(qbox) != NOQ && q.isNotBlank()) parts.add(q.trim())
        parts.add("${h}p")
        if (fps.isNotBlank()) parts.add(fps.trim() + " fps")
        return parts.joinToString(" · ")
    }

    /**
     * Etichetta in cima alla barra informazioni del lettore («FHD · LIVE» sui canali, «FHD» su film e serie): con [qbox]
     * "noq" o "off" senza la sigla di qualità, cioè «LIVE» sui canali e niente su film e serie (l'etichetta sparisce).
     */
    fun badge(q: String, live: Boolean, qbox: String?): String {
        val qq = if (mode(qbox) == FULL) q.trim() else ""
        return if (live) (if (qq.isEmpty()) "LIVE" else "$qq · LIVE") else qq
    }
}
