package com.kairos.tv

import android.app.Activity
import android.app.AlertDialog

/**
 * Finestra nostra prima di quella di Android per la VPN / il DNS cifrato (13 novembre 2026): il testo di Android
 * («… monitorare il traffico di rete …») non si può cambiare, quindi prima diciamo al cliente che la protezione l'ha
 * attivata il suo venditore e che deve premere OK. Un solo pulsante: «Continua» apre la finestra di Android.
 */
object ConsentIntro {
    /** finestra già aperta: una seconda richiesta nel frattempo non ne apre un'altra */
    @Volatile private var showing = false

    fun show(a: Activity, go: () -> Unit, fail: () -> Unit) {
        if (showing) return
        val launch = { showing = false; try { go() } catch (_: Exception) { fail() } }
        if (a.isFinishing) { launch(); return }
        showing = true
        try {
            AlertDialog.Builder(a)
                .setTitle("🔒 Protezione attivata dal tuo venditore")
                .setMessage("Il tuo venditore ha attivato la protezione della connessione per questa app. Nella prossima finestra premi OK.\n\nServe solo a proteggere la connessione dell'app: i tuoi dati non vengono letti.")
                .setCancelable(false)
                .setOnDismissListener { showing = false }
                .setPositiveButton("Continua") { d, _ -> d.dismiss(); launch() }
                .show()
                .getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        } catch (_: Exception) { launch() }
    }
}
