package com.kairos.tv

/**
 * Da dove arrivano il file degli aggiornamenti e l'APK (logica pura, senza Android: la provano i test JVM).
 *
 * Basi, in ordine:
 * 1. l'indirizzo dei download `BuildConfig.DL_BASE` (https://store-k.download): una sola costante in app/build.gradle.kts
 *    (o la variabile KAIROS_DL_BASE del repository). Finché il dominio non è attivo fallisce in fretta (connessione 5 s);
 * 2. il pannello [PANEL], stessi percorsi `/get/<id>.json` e `/get/<id>.apk` (si salta se è uguale alla prima);
 * 3. la riserva di oggi, l'ultima release di GitHub: quando il repository diventerà privato fallirà senza danni.
 *
 * Il file vale solo se la risposta è 200 e un JSON con versione e link dell'APK in https; una pagina HTML (anche quella
 * di un dominio parcheggiato), un JSON rotto o senza quei campi contano come errore e si passa alla base successiva.
 * L'APK si scarica dal link del file che ha risposto e, se non riesce, dalle basi successive ([apkUrls]).
 */
object UpdFeed {
    /** pannello Kairos: seconda base, con gli stessi percorsi dell'indirizzo dei download */
    const val PANEL = "https://panel-kairos.com"

    private val SEMVER = Regex("^(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})$")

    /** basi https dei download, in ordine e senza doppioni: [dlBase], poi [PANEL] */
    fun bases(dlBase: String): List<String> =
        listOf(dlBase, PANEL).map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") }.distinct()

    /** file degli aggiornamenti da provare per l'app [id] (tv, premium, lite, xc): /get/<id>.json su ogni base, poi [fallback] */
    fun urls(dlBase: String, id: String, fallback: String): List<String> {
        val out = bases(dlBase).map { "$it/get/$id.json" }.toMutableList()
        val f = fallback.trim()
        if (f.startsWith("https://")) out.add(f)
        return out.distinct()
    }

    /**
     * APK da provare, in ordine: [url], il link del file che ha risposto; poi lo stesso APK (/get/<id>.apk) sulle basi
     * successive a quella del link; alla fine [ghApk], l'APK dell'ultima release di GitHub. Un link fuori dalle basi
     * (quello del file di riserva di GitHub) passa direttamente a [ghApk].
     */
    fun apkUrls(url: String, dlBase: String, id: String, ghApk: String): List<String> {
        val out = ArrayList<String>(4)
        if (url.startsWith("https://")) out.add(url)
        val b = bases(dlBase)
        val at = b.indexOfFirst { url.startsWith("$it/") }
        if (at >= 0) for (next in b.subList(at + 1, b.size)) out.add("$next/get/$id.apk")
        val g = ghApk.trim()
        if (g.startsWith("https://")) out.add(g)
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
