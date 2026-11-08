package com.kairos.tv

/**
 * Qualità dei canali (versioni sorelle 4K / FHD / HD / SD): regole pure, provate in app/src/test.
 *
 * Principio: con la linea buona la qualità non si tocca. La chiavetta da 1 GB non abbassa mai la qualità da sola:
 * decide solo che il 4K non serve (lei e gli schermi non 4K non lo mostrano) e, se l'ha imparato sul campo, che la
 * versione FHD le va «a scatti». La linea abbassa la qualità solo con prove recenti (banda misurata da poco, blocchi
 * nell'ultima mezz'ora), mai con misure vecchie.
 */
object QualityPolicy {
    /** per quanto una misura di banda (segmenti HLS) vale per giudicare la linea: poi si torna «normale» */
    const val BW_FRESH_MS = 2 * 3_600_000L
    /** dopo una riduzione per blocchi della linea i canali successivi partono un gradino sotto per… */
    const val CARRY_MS = 10 * 60_000L
    /** immagine a scatti imparata: vale 7 giorni dall'ultima volta, poi si riprova la versione piena */
    const val HEAVY_MS = 7 * 24 * 3_600_000L
    /** volte (su canali diversi) prima di partire più leggeri */
    const val HEAVY_N = 2

    /** tetto scelto dal cliente o dal pannello («Qualità video»): 4 = nessuno */
    fun settingQ(quality: String): Int = when (quality) { "1080" -> 3; "720" -> 2; "480" -> 1; else -> 4 }

    /** tetto del dispositivo (la qualità adattiva accesa): niente 4K sulle chiavette deboli (non lo decodificano); [heavyCap] imparato */
    fun deviceQ(dev: String, heavyCap: Int): Int = minOf(if (dev == "D") 3 else 4, heavyCap)

    /** tetto per la linea: solo se è debole adesso; SD solo con una banda misurata da poco sotto 2,5 Mbps */
    fun lineQ(weak: Boolean, bw: Long, bwFresh: Boolean): Int = if (!weak) 4 else if (bwFresh && bw in 1L until 2_500_000L) 1 else 2

    /** una versione del canale: [q] 4 = 4K, 3 = FHD, 2 = HD, 1 = SD */
    data class V(val id: Long, val q: Int, val hevc: Boolean)

    /** [full] = versione piena per questo dispositivo (la risalita torna qui) · [start] = quella con cui si parte */
    class Start(val full: V, val start: V) {
        /** si parte più leggeri per la linea (avviso al cliente, risalita dopo qualche minuto senza blocchi) */
        val byLine: Boolean get() = start.id != full.id
    }

    /**
     * Versione di partenza del canale [ch] fra le sorelle [sisters] (mai HEVC come ripiego di un canale che non lo è).
     * 1. Tetti fissi: «Qualità video» (1080p ⇒ FHD, 720p ⇒ HD, 480p ⇒ SD) e, con la qualità adattiva accesa
     *    ([adaptOn]), il dispositivo: niente 4K sulle chiavette deboli, versione «a scatti» imparata.
     * 2. Senza perdita visibile: schermo non 4K ⇒ FHD invece del 4K (non con «Massima»); «Leggerissima» ⇒ HD (non HEVC).
     * 3. La linea, solo se debole adesso ([lineQ]) o dopo un calo recente ([carry]: un gradino sotto); mai con «Massima»
     *    né con la qualità adattiva spenta.
     */
    fun start(ch: V, sisters: List<V>, adaptOn: Boolean, quality: String, liteMax: Boolean, dev: String, screen4k: Boolean,
              heavyCap: Int, lineQ: Int, carry: Boolean): Start {
        val all = listOf(ch) + sisters.filter { it.id != ch.id && (!it.hevc || ch.hevc) }
        fun bestAtMost(cap: Int): V? = all.filter { it.q <= cap }.maxWithOrNull(compareBy<V>({ it.q }, { if (it.hevc) 0 else 1 }))
        var hard = settingQ(quality)
        if (adaptOn) hard = minOf(hard, deviceQ(dev, heavyCap))
        var full = if (ch.q <= hard) ch else bestAtMost(hard) ?: ch
        if (adaptOn && quality != "max" && !screen4k && full.q == 4) all.filter { it.q == 3 }.minByOrNull { if (it.hevc) 1 else 0 }?.let { full = it }
        if (liteMax && full.q >= 3) all.firstOrNull { it.q == 2 && !it.hevc }?.let { full = it }
        if (!lineDowngrades(adaptOn, quality)) return Start(full, full)
        var cap = lineQ
        if (carry) cap = minOf(cap, full.q - 1)
        if (cap >= full.q) return Start(full, full)
        val s = bestAtMost(cap) ?: return Start(full, full)
        return Start(full, s)
    }

    /** si scende di versione per la linea? No con «Massima» né con la qualità adattiva spenta */
    fun lineDowngrades(adaptOn: Boolean, quality: String): Boolean = adaptOn && quality != "max"

    /**
     * Discesa anticipata: il buffer scende davvero, in modo costante, verso lo stallo. [samples] = (istante, buffer in ms)
     * degli ultimi 25 s, [cur] = buffer adesso. Vero solo se: sotto [threshold], calato di almeno 3 s rispetto a 15 s fa
     * e in calo sia nella prima sia nella seconda metà della finestra (un singolo vuoto del Wi-Fi non basta).
     */
    fun draining(samples: List<Pair<Long, Long>>, now: Long, cur: Long, threshold: Long): Boolean {
        if (cur >= threshold) return false
        val old = samples.lastOrNull { now - it.first >= 15_000L } ?: return false
        val mid = samples.lastOrNull { now - it.first >= 7_000L } ?: return false
        if (mid === old) return false
        return old.second - cur >= 3_000L && old.second - mid.second >= 1_000L && mid.second - cur >= 1_000L
    }

    /** soglia della discesa anticipata: vicino allo stallo (mai sopra 5 s, anche con i buffer grandi delle chiavette) */
    fun drainThreshold(rebufferMs: Int): Long = minOf(rebufferMs + 2_000L, 5_000L)

    /**
     * Versione «a scatti» imparata sul dispositivo: [q] = la versione più bassa che è andata a scatti, [n] = canali diversi,
     * [at] = ultima volta (orologio di sistema), [key] = ultimo canale (lo stesso canale non conta due volte).
     */
    data class Heavy(val q: Int, val n: Int, val at: Long, val key: String) {
        fun save(): String = "$q|$n|$at|${key.replace("|", "")}"

        companion object {
            fun load(s: String?): Heavy? {
                val p = (s ?: "").split('|')
                if (p.size < 4) return null
                val q = p[0].toIntOrNull() ?: return null
                val n = p[1].toIntOrNull() ?: return null
                val at = p[2].toLongOrNull() ?: return null
                return Heavy(q, n, at, p[3])
            }
        }
    }

    /** immagine a scatti sulla versione [q] (solo FHD o 4K: sotto non si impara nulla) del canale [key] */
    fun heavyNote(h: Heavy?, q: Int, key: String, now: Long): Heavy? {
        if (q < 3) return h
        val cur = if (h == null || now - h.at > HEAVY_MS) null else h
        if (cur == null) return Heavy(q, 1, now, key)
        if (cur.key == key) return cur.copy(q = minOf(cur.q, q), at = now)
        return Heavy(minOf(cur.q, q), cur.n + 1, now, key)
    }

    /** tetto imparato (4 = nessuno): dopo [HEAVY_N] canali diversi a scatti si parte un gradino sotto, per [HEAVY_MS] */
    fun heavyCap(h: Heavy?, now: Long): Int =
        if (h == null || h.n < HEAVY_N || now - h.at > HEAVY_MS || now < h.at) 4 else (h.q - 1).coerceAtLeast(1)

    /**
     * Anteprima nella lista canali ([available] = versioni sorelle: qualità, HEVC): i canali HD e SD restano come sono;
     * FHD e 4K passano alla versione HD se c'è (niente HEVC), altrimenti alla più leggera sotto quella scelta. null = il canale stesso
     */
    fun previewQ(q0: Int, available: List<Pair<Int, Boolean>>): Int? {
        if (q0 <= 2) return null
        val lower = available.filter { !it.second && it.first < q0 }.map { it.first }
        if (lower.isEmpty()) return null
        return lower.filter { it <= 2 }.maxOrNull() ?: lower.minOrNull()
    }
}
