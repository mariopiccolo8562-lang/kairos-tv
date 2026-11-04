package com.kairos.tv

import android.os.Bundle

/** Home stile Netflix (vedi [BrowseActivity]); qui in più: messaggi del fornitore e conferma d'uscita */
class HomeActivity : BrowseActivity() {
    override val kind: String get() = "home"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) messages()
    }

    override fun onStateChanged() {
        super.onStateChanged()
        messages()
    }

    /** INDIETRO sulla home: si chiede conferma prima di chiudere l'app */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Ui.dialog(this, "Vuoi uscire?", "Kairos TV si chiude. Canali e preferiti restano pronti per la prossima volta.", "Esci", "Annulla") {
            finishAffinity()
        }
    }

    private fun messages() {
        val m = K.unseenMessages()
        if (m.isEmpty()) return
        val text = m.joinToString("\n\n") { (if (it.title.isNotEmpty()) it.title + "\n" else "") + it.body }
        Store.seenMsgs = Store.seenMsgs + m.map { it.id }
        Ui.dialog(this, if (m.size == 1) "Messaggio dal tuo fornitore" else "Messaggi dal tuo fornitore", text)
    }
}
