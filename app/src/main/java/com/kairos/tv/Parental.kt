package com.kairos.tv

import android.app.Activity

/**
 * Controllo genitori: attivo di serie con il PIN 0000. Al primo avvio l'app chiede di scegliere un PIN proprio
 * («Più tardi» rimanda al giorno dopo). Le categorie per adulti chiedono il PIN una volta per apertura dell'app.
 * Il fornitore può riportare il PIN a 0000 dal pannello Kairos Control (comando «Reimposta PIN controllo genitori»).
 */
object Parental {
    const val DEFAULT = "0000"
    /** PIN inserito in questa apertura: le categorie protette restano aperte fino alla chiusura dell'app */
    @Volatile var unlocked = false

    fun on(): Boolean = Store.parental
    fun pinIsDefault(): Boolean = Store.pin.isEmpty()
    fun check(p: String): Boolean = p.trim() == Store.pin.ifEmpty { DEFAULT }
    fun locked(adult: Boolean): Boolean = adult && on() && !unlocked

    /** chiede il PIN; se è giusto sblocca per questa apertura ed esegue [onOk] */
    fun ask(a: Activity, title: String = "Contenuto protetto", onOk: () -> Unit) {
        Ui.input(a, title, "PIN di 4 cifre", true) { p ->
            if (check(p)) { unlocked = true; onOk() } else Ui.toast("PIN errato")
        }
    }

    /** scelta di un PIN nuovo (chiesto due volte) */
    fun choose(a: Activity, onDone: (() -> Unit)? = null) {
        Ui.input(a, "Scegli un PIN di 4 cifre", "PIN", true) { p1 ->
            if (p1.length != 4 || !p1.all { it.isDigit() }) { Ui.toast("Il PIN deve avere 4 cifre"); return@input }
            if (p1 == DEFAULT) { Ui.toast("0000 è il PIN iniziale: scegline un altro"); return@input }
            Ui.input(a, "Ripeti il PIN", "PIN", true) { p2 ->
                if (p1 != p2) { Ui.toast("I due PIN non coincidono"); return@input }
                Store.pin = p1; Store.pinLater = 0L; Ui.toast("✓ PIN impostato")
                onDone?.invoke()
            }
        }
    }

    /** primo avvio, o PIN reimpostato dal fornitore: si chiede di scegliere il proprio PIN */
    fun firstRun(a: Activity, force: Boolean = false) {
        if (a.isFinishing || !on() || !pinIsDefault()) return
        if (!force && System.currentTimeMillis() - Store.pinLater < 86_400_000L) return
        Store.pinLater = System.currentTimeMillis()
        Ui.dialog(a, "Controllo genitori", "Il controllo genitori è attivo: le categorie per adulti chiedono un PIN. Ora il PIN è 0000: scegline uno tuo di 4 cifre.\n\nPuoi farlo anche dopo, da Impostazioni.", "Scegli il PIN", "Più tardi") { choose(a) }
    }

    /** comando «Reimposta PIN» dal pannello: si torna a 0000 e l'app richiede di sceglierne uno */
    fun reset() { Store.pin = ""; Store.pinLater = 0L; unlocked = false }

    /** PIN reimpostato durante la visione: la scelta del nuovo PIN si chiede alla prima schermata dopo il lettore */
    @Volatile var askPending = false

    /** scelta del nuovo PIN subito (stessa finestra della prima installazione, con «Più tardi») */
    fun askNow(a: Activity) { askPending = false; firstRun(a, true) }
}
