package com.kairos.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** ExoPlayer configurato per IPTV su dispositivi anche vecchi (Kairos Adaptive v1: tutto dipende dalla classe effettiva) */
@OptIn(UnstableApi::class)
object Players {
    /** buffer per classe (KAIROS_ADAPTIVE §2): min / max / avvio / ripresa in ms, tetto in MB, back buffer in ms */
    class Buf(val min: Int, val max: Int, val start: Int, val rebuffer: Int, val mb: Int, val back: Int)

    fun buf(live: Boolean, cls: Int): Buf = if (live) when (cls) {
        Line.WEAK -> Buf(12_000, 30_000, 1_500, 6_000, 16, 0)
        Line.GOOD -> Buf(15_000, 60_000, 800, 3_000, 32, 0)
        else -> Buf(15_000, 50_000, 1_000, 4_000, 24, 0)
    } else when (cls) {
        Line.WEAK -> Buf(20_000, 40_000, 2_500, 4_000, 32, 6_000)
        Line.GOOD -> Buf(25_000, 90_000, 800, 2_500, 64, 12_000)
        else -> Buf(20_000, 60_000, 1_000, 3_000, 48, 10_000)
    }

    /** tetto di bitrate dell'ABR per classe della LINEA (§4, Line.net): sotto il tetto l'ABR resta attivo */
    fun maxBitrate(net: Int): Int = if (net == Line.WEAK) 5_000_000 else Int.MAX_VALUE

    /** anteprima nella lista canali in corso (niente lavori di fondo nel frattempo) */
    @Volatile var previewing = false

    @Volatile private var sharedMeter: DefaultBandwidthMeter? = null
    /** stima iniziale del misuratore condiviso: un campione uguale a questa non è una misura */
    @Volatile var meterInitial = 0L
        private set

    /** un solo misuratore per app: ciò che ha imparato un canale HLS vale per il successivo (l'anteprima ne ha uno suo) */
    fun meterFor(ctx: Context, net: Int): DefaultBandwidthMeter {
        sharedMeter?.let { return it }
        synchronized(this) {
            sharedMeter?.let { return it }
            val init = Line.initialBps(net)
            val m = DefaultBandwidthMeter.Builder(ctx.applicationContext).setInitialBitrateEstimate(init).build()
            meterInitial = init
            sharedMeter = m
            return m
        }
    }

    /**
     * [soft] = decodifica software (rimedio quando il decoder hardware si blocca); [cls] = classe effettiva (Line.cls(): memoria,
     * buffer, distanza dalla diretta); [net] = classe della linea (Line.net(): qualità, ABR). La chiavetta da 1 GB ha buffer
     * più piccoli ma, con la linea buona, la stessa qualità degli altri dispositivi
     */
    fun build(ctx: Context, live: Boolean, soft: Boolean = false, preview: Boolean = false, cls: Int = Line.cls(), net: Int = Line.net()): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000) // con IPv6 morto si perderebbe tutto il timeout prima di passare all'IPv4
            .setReadTimeoutMs(20_000)
        // flussi progressivi (TS in diretta, film mp4/mkv): arrivano alla velocità del canale, o frenati dal buffer pieno,
        // non alla velocità della linea: il misuratore di banda non li conta (misura solo i segmenti HLS)
        val resolver = ResolvingDataSource.Resolver { spec ->
            if (spec.isFlagSet(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)) spec.buildUpon().setFlags(spec.flags or DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED).build() else spec
        }
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, ResolvingDataSource.Factory(http, resolver)), extractors)
        // HLS in diretta (§3): più lontani dal bordo e senza rincorsa sulle linee deboli, altrimenti i buffer sono irraggiungibili
        if (live && !preview) {
            when (cls) {
                Line.WEAK -> sources.setLiveTargetOffsetMs(20_000).setLiveMinSpeed(1f).setLiveMaxSpeed(1f)
                Line.NORMAL -> sources.setLiveTargetOffsetMs(12_000).setLiveMaxSpeed(1.02f)
            }
        }
        // memoria per il video: il tetto in byte è la vera protezione dagli OOM sulle chiavette (con «true» il lettore
        // lo ignorerebbe finché non raggiunge minBuffer). Anteprima: pochi secondi, si guarda per qualche istante
        val load = if (preview) DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 5_000, 800, 1_500)
            .setTargetBufferBytes(3 shl 20)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        else {
            val b = buf(live, cls)
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(b.min, b.max, b.start, b.rebuffer)
                .setTargetBufferBytes((if (App.weak && App.lite) b.mb * 3 / 4 else b.mb).coerceAtLeast(4) shl 20) // 1 GB: memoria all'interfaccia
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(if (App.weak && App.lite) 0 else b.back, false)
                .build()
        }
        val renderers = KxRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if (soft) renderers.setMediaCodecSelector { mime, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
        }
        // stima iniziale: la banda salvata (×0,8) oppure 2 / 6 / 12 Mbps per classe; l'anteprima non insegna niente alla linea
        val meter = if (preview) DefaultBandwidthMeter.Builder(ctx).setInitialBitrateEstimate(Line.initialBps(net)).build() else meterFor(ctx, net)
        // ABR nei flussi HLS a più qualità (§4): quota di banda usabile e tempo prima di salire, per classe della linea
        val abr = when (net) {
            Line.WEAK -> AdaptiveTrackSelection.Factory(15_000, AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS, AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, 0.55f)
            Line.GOOD -> AdaptiveTrackSelection.Factory(6_000, AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS, AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, 0.80f)
            else -> AdaptiveTrackSelection.Factory(10_000, AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS, AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, 0.70f)
        }
        val selector = DefaultTrackSelector(ctx, abr)
        // niente qualità più alta dello schermo (una TV 720p non scarica il 1080p)
        selector.setParameters(selector.buildUponParameters().setViewportSizeToPhysicalDisplaySize(ctx, true).setPreferredAudioLanguage("it"))
        // anteprima: nei canali HLS con più qualità si prende la più bassa adatta al riquadro
        if (preview) selector.setParameters(selector.buildUponParameters().setMaxVideoSize(854, 480).setMaxVideoBitrate(1_500_000))
        else {
            // linea debole: tetto 1080p e 5 Mbps (mai forceHighestSupportedBitrate: sotto il tetto l'ABR resta attivo)
            if (net == Line.WEAK) selector.setParameters(selector.buildUponParameters().setMaxVideoSize(1920, 1080).setMaxVideoBitrate(maxBitrate(net)))
            // «Leggerissima»: con i flussi a più qualità si resta a 720p, il decoder delle chiavette vecchie respira
            if (App.max) selector.setParameters(selector.buildUponParameters().setMaxVideoSize(1280, 720))
        }
        val p = ExoPlayer.Builder(ctx, renderers)
            .setBandwidthMeter(meter)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setMediaSourceFactory(sources)
            .build()
        p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        return p
    }

    /** il flusso è HLS (segmenti) e non TS / progressivo; [hls] = indirizzo senza estensione che si è rivelato HLS */
    fun isHls(url: String, hls: Boolean = false): Boolean {
        val low = url.lowercase().substringBefore('?')
        return hls || low.endsWith(".m3u8") || low.endsWith(".m3u")
    }

    fun item(url: String, hls: Boolean = false): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(url))
        val low = url.lowercase().substringBefore('?')
        when {
            isHls(url, hls) -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            low.endsWith(".ts") -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        return b.build()
    }
}

/**
 * Rimedio di KAIROS TV per alcuni Fire TV (chip MediaTek MT8695): al cambio di superficie il decoder
 * si blocca (audio che continua, immagine ferma). Su quei modelli il decoder viene ricreato.
 */
object SurfaceFix {
    private val MODELS = setOf("AFTSSS", "AFTSS", "AFTMM")
    private const val KEY = "surfaceFixLearned"
    @Volatile private var learned: Boolean? = null
    fun needed(ctx: Context): Boolean {
        if (MODELS.contains(android.os.Build.MODEL)) return true
        return learned ?: ctx.applicationContext.getSharedPreferences("lite", Context.MODE_PRIVATE).getBoolean(KEY, false).also { learned = it }
    }
    fun learn(ctx: Context) {
        if (learned == true) return
        learned = true
        try { ctx.applicationContext.getSharedPreferences("lite", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply() } catch (_: Exception) {}
    }
}

@OptIn(UnstableApi::class)
class KxRenderersFactory(private val ctx: Context) : DefaultRenderersFactory(ctx) {
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) {
        if (!SurfaceFix.needed(ctx)) {
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
            return
        }
        out.add(object : MediaCodecVideoRenderer(
            context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
        ) {
            override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean =
                !(name.startsWith("OMX.google") || name.startsWith("c2.android")) || super.codecNeedsSetOutputSurfaceWorkaround(name)
        })
    }
}
