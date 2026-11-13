package com.kairos.tv

/**
 * Lucchetto «DNS» accanto allo scudo della VPN (13 novembre 2026): dice a colpo d'occhio se il DNS cifrato acceso dal
 * fornitore è attivo. C'è solo quando il fornitore lo ha acceso dal pannello (voce «sdns»). Verde = attivo, o protetto
 * dalla VPN del fornitore; giallo = non partito (permesso da confermare o negato, altra VPN, errore); bianco tenue = in
 * avvio. Logica pura, senza Android: la provano i test JVM.
 */
object DnsBadge {
    /** stato da mostrare: "" = niente lucchetto; "vpn" = ci pensa la VPN del fornitore; altrimenti quello del servizio */
    fun state(wanted: Boolean, vpnOn: Boolean, service: String): String =
        if (!wanted) "" else if (vpnOn) "vpn" else service.ifEmpty { "off" }

    fun on(state: String): Boolean = state == "on" || state == "vpn"

    /** non è partito e serve attenzione (giallo); "off" = sta partendo */
    fun warn(state: String): Boolean = state.isNotEmpty() && !on(state) && state != "off"

    /** colore ARGB del lucchetto */
    fun color(state: String): Int = if (on(state)) 0xFF2EE59D.toInt() else if (warn(state)) 0xFFF5B041.toInt() else 0xCCFFFFFF.toInt()

    /** al tocco si può riprovare subito (permesso negato o errore) */
    fun canRetry(state: String): Boolean = state == "denied" || state == "error"

    /** frase per il cliente (al tocco e come descrizione del pulsante) */
    fun text(state: String): String = when (state) {
        "" -> ""
        "on" -> "DNS cifrato attivo · gestito dal fornitore"
        "vpn" -> "DNS protetto dalla VPN del fornitore"
        "asking" -> "DNS cifrato: conferma la richiesta di Android per attivarlo"
        "denied" -> "DNS cifrato non attivo: permesso negato"
        "error" -> "DNS cifrato non attivo al momento"
        "other" -> "DNS cifrato non attivo: sul dispositivo c'è un'altra VPN accesa"
        else -> "DNS cifrato in avvio…"
    }
}
