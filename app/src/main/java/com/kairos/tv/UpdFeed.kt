package com.kairos.tv

/**
 * Da dove arriva il file degli aggiornamenti (logica pura, senza Android: la provano i test JVM).
 *
 * Tentativi, in ordine:
 * 1. l'indirizzo dei download `BuildConfig.DL_BASE` + `/get/<id>.json`: una sola costante in app/build.gradle.kts
 *    (o la variabile KAIROS_DL_BASE del repository), da cambiare quando arriva il dominio dedicato ai download;
 * 2. il pannello [PANEL], stesso percorso: si salta se è lo stesso indirizzo del primo (oggi lo è);
 * 3. la riserva di oggi, il file dell'ultima release di GitHub: quando il repository diventerà privato fallirà senza danni.
 *
 * Si passa al tentativo dopo solo se quello prima non risponde, risponde con un errore o con un file non valido:
 * con il pannello che funziona la richiesta resta una sola, come prima. L'APK si scarica dal campo `url` del file trovato.
 */
object UpdFeed {
    /** pannello Kairos: secondo tentativo, con gli stessi percorsi /get/<id>.json dell'indirizzo dei download */
    const val PANEL = "https://panel-kairos.com"

    private val SEMVER = Regex("^(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})$")

    /** indirizzi da provare per l'app [id] (tv, premium, lite, xc), in ordine e senza doppioni; solo https */
    fun urls(dlBase: String, id: String, fallback: String): List<String> {
        val out = ArrayList<String>(3)
        for (base in arrayOf(dlBase, PANEL)) {
            val b = base.trim().trimEnd('/')
            if (b.startsWith("https://")) out.add("$b/get/$id.json")
        }
        val f = fallback.trim()
        if (f.startsWith("https://")) out.add(f)
        return out.distinct()
    }

    /**
     * Primo file valido nell'ordine di [urls]. [load] legge un indirizzo e dà null se risponde con un errore o con un file
     * non valido; un'eccezione (nessuna risposta, tempo scaduto) vale come tentativo fallito. null = nessuno ha risposto bene.
     */
    fun <T : Any> first(urls: List<String>, load: (String) -> T?): T? {
        for (u in urls) {
            val r = try { load(u) } catch (_: Exception) { null }
            if (r != null) return r
        }
        return null
    }

    /** numero della versione: da «X.Y.Z» come il workflow (1.2.3 → 10203), altrimenti [versionCode] del file */
    fun code(version: String, versionCode: Int): Int {
        val m = SEMVER.find(version) ?: return versionCode
        return m.groupValues[1].toInt() * 10000 + m.groupValues[2].toInt() * 100 + m.groupValues[3].toInt()
    }

    /** file valido: versione riconosciuta e link dell'APK in https */
    fun valid(code: Int, url: String): Boolean = code > 0 && url.startsWith("https://")
}
