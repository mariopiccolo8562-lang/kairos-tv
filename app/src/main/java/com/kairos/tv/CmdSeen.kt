package com.kairos.tv

/**
 * Comandi del controllo remoto già visti in questa apertura dell'app (logica pura, senza Android: la provano i test JVM).
 * Il pannello rimanda un comando a ogni battito finché la conferma non gli arriva (rete lenta, conferma persa), quindi:
 * - un comando già eseguito che torna si conferma di nuovo con lo stesso esito, senza rifarlo (prima il PIN tornava a 0000
 *   una seconda volta, le impostazioni si riaprivano…);
 * - uno ancora in corso (es. il controllo dell'aggiornamento) non riparte: la conferma arriva a lavoro finito.
 * Restano gli ultimi [keep] esiti. Riavvio, svuota cache e chiudi il pannello non li rimanda dopo che l'app si è riaperta.
 */
class CmdSeen(private val keep: Int = 40) {
    class Done(val ok: Boolean, val info: String)

    private val done = LinkedHashMap<Long, Done>()
    private val running = HashSet<Long>()

    /** comando [id] arrivato: null = da eseguire (da ora risulta in corso), [BUSY] = già in corso, altrimenti l'esito già dato */
    @Synchronized
    fun arrive(id: Long): Done? {
        done[id]?.let { return it }
        if (!running.add(id)) return BUSY
        return null
    }

    /** esito di [id]: non è più in corso e, se torna, si conferma soltanto */
    @Synchronized
    fun finish(id: Long, ok: Boolean, info: String) {
        running.remove(id)
        done.remove(id)
        done[id] = Done(ok, info)
        while (done.size > keep) done.remove(done.keys.first())
    }

    companion object {
        /** il comando è ancora in corso: niente da fare */
        val BUSY = Done(true, "")
    }
}
