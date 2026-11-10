package com.kairos.tv

/**
 * Da dove arrivano il file degli aggiornamenti e l'APK (logica pura, senza Android: la provano i test JVM).
 *
 * Basi, in ordine (dal 10 novembre 2026 niente più pannello né GitHub negli aggiornamenti):
 * 1. l'indirizzo dei download `BuildConfig.DL_BASE` (https://store-k.download, o la variabile KAIROS_DL_BASE del
 *    repository). Se il dominio non risponde si passa in fretta alla base dopo (connessione 5 s);
 * 2. le riserve, con gli stessi percorsi `/get/<id>.json` e `/get/<id>.apk`: `BuildConfig.DL_BASE2` (variabile
 *    KAIROS_DL_BASE2, vuota finché non c'è un secondo dominio) e i domini che il file degli aggiornamenti annuncia in
 *    «mirrors» (l'app li ricorda in [Store.dlMirrors]: un dominio di riserva comprato dopo arriva anche alle app già
 *    installate, senza una versione nuova).
 *
 * Il file vale solo se la risposta è 200 e un JSON con versione e link dell'APK in https; una pagina HTML (anche quella
 * di un dominio parcheggiato), un JSON rotto o senza quei campi contano come errore e si passa alla base successiva.
 * L'APK si scarica dal link del file che ha risposto e, se non riesce, dalle altre basi ([apkUrls]).
 */
object UpdFeed {
    private val SEMVER = Regex("^(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})$")
    private val BASE = Regex("^https://[a-z0-9.-]+\\.[a-z]{2,}(:\\d{1,5})?$", RegexOption.IGNORE_CASE)

    /** basi https dei download, in ordine e senza doppioni: [dlBase], poi le riserve [extra]; al massimo 4 */
    fun bases(dlBase: String, extra: List<String> = emptyList()): List<String> =
        (listOf(dlBase) + extra).map { it.trim().trimEnd('/') }.filter { BASE.matches(it) }.distinct().take(4)

    /** file degli aggiornamenti da provare per l'app [id] (tv): /get/<id>.json su ogni base */
    fun urls(dlBase: String, id: String, extra: List<String> = emptyList()): List<String> =
        bases(dlBase, extra).map { "$it/get/$id.json" }

    /**
     * APK da provare, in ordine: [url], il link del file che ha risposto; poi lo stesso APK (/get/<id>.apk) sulle basi
     * successive a quella del link. Un link fuori dalle basi passa a tutte le basi.
     */
    fun apkUrls(url: String, dlBase: String, id: String, extra: List<String> = emptyList()): List<String> {
        val out = ArrayList<String>(5)
        if (url.startsWith("https://")) out.add(url)
        val b = bases(dlBase, extra)
        val at = b.indexOfFirst { url.startsWith("$it/") }
        for (next in if (at >= 0) b.subList(at + 1, b.size) else b) out.add("$next/get/$id.apk")
        return out.distinct()
    }

    /** domini di riserva annunciati dal file degli aggiornamenti («mirrors»): solo https validi, senza doppioni, al massimo 3 */
    fun mirrors(list: List<String>): List<String> =
        list.map { it.trim().trimEnd('/') }.filter { BASE.matches(it) }.distinct().take(3)

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

    /**
     * Prova [urls] in ordine finché [attempt] non riesce (nessuna eccezione) e ne dà il risultato. Un'eccezione per cui
     * [stop] è vero ferma tutto senza passare agli altri (es. visione iniziata: non è un errore). Se nessuno riesce,
     * l'errore del primo tentativo (quello del link che ha dato il file).
     */
    fun <T> firstOk(urls: List<String>, stop: (Exception) -> Boolean = { false }, attempt: (String) -> T): T {
        var err: Exception? = null
        for (u in urls) {
            try {
                return attempt(u)
            } catch (e: Exception) {
                if (stop(e)) throw e
                if (err == null) err = e
            }
        }
        throw err ?: IllegalStateException("nessun indirizzo da provare")
    }

    /** numero della versione: da «X.Y.Z» come il workflow (1.2.3 → 10203), altrimenti [versionCode] del file */
    fun code(version: String, versionCode: Int): Int {
        val m = SEMVER.find(version) ?: return versionCode
        return m.groupValues[1].toInt() * 10000 + m.groupValues[2].toInt() * 100 + m.groupValues[3].toInt()
    }

    /** file valido: versione riconosciuta e link dell'APK in https */
    fun valid(code: Int, url: String): Boolean = code > 0 && url.startsWith("https://")
}
