package com.kairos.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Ui {
    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    /** leggero ingrandimento sull'elemento selezionato col telecomando (spento in modalità leggera) */
    fun zoom(v: View, scale: Float = 1.05f) {
        if (App.lite) return
        val prev = v.onFocusChangeListener
        v.setOnFocusChangeListener { x, has ->
            prev?.onFocusChange(x, has)
            x.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(110).start()
        }
    }

    fun toast(msg: String) {
        Toast.makeText(App.I, msg, Toast.LENGTH_LONG).show()
    }

    fun date(ymd: String): String =
        if (ymd.length == 10 && ymd[4] == '-') ymd.substring(8, 10) + "/" + ymd.substring(5, 7) + "/" + ymd.substring(0, 4) else ymd

    private val HM = SimpleDateFormat("HH:mm", Locale.ITALY)
    private val DAY = SimpleDateFormat("EEEE d MMMM", Locale.ITALY)
    fun hm(ms: Long): String = synchronized(HM) { HM.format(Date(ms)) }
    fun today(): String = synchronized(DAY) { DAY.format(Date()) }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ITALY, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ITALY, "%d:%02d", m, sec)
    }

    fun minutes(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    fun num(n: Int): String = String.format(Locale.ITALY, "%,d", n).replace(',', '.')

    private val AV = intArrayOf(
        0xFFFFB547.toInt(), 0xFF4FD1C5.toInt(), 0xFF7C9CFF.toInt(), 0xFFFF7A8A.toInt(), 0xFF8BD17C.toInt(), 0xFFC08CFF.toInt(),
        0xFFFF9F5A.toInt(), 0xFF5AC8FF.toInt(), 0xFFE8D25A.toInt(), 0xFFFF6FB0.toInt(), 0xFF6FE0A8.toInt(), 0xFFB0B8C8.toInt(),
    )
    fun avatarColor(av: String): Int {
        val n = av.removePrefix("a").toIntOrNull() ?: 1
        return AV[(n - 1).mod(AV.size)]
    }
    private val SPACES = Regex("\\s+")
    fun initials(name: String): String =
        name.trim().split(SPACES).filter { it.isNotEmpty() }.take(2).joinToString("") { it.substring(0, 1).uppercase(Locale.ITALY) }.ifEmpty { "?" }

    /** cerchio colorato con le iniziali (profili, account) */
    fun avatar(t: TextView, name: String, av: String, radiusDp: Int) {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(t.context, radiusDp).toFloat()
        g.setColor(avatarColor(av))
        t.background = g
        t.text = initials(name)
    }

    /** immagini a 16 bit (metà memoria): modalità leggera o dispositivo debole (classe D di Kairos Adaptive).
     *  Glide tiene comunque a 32 bit le immagini con trasparenza (loghi PNG) */
    val rgb565: Boolean get() = App.lite || App.weak

    /**
     * Immagine decodificata alla misura mostrata, mai all'originale (locandine, sfondi e loghi sono la prima causa di
     * memoria esaurita sulle chiavette):
     * - a 16 bit in modalità leggera e sui dispositivi deboli;
     * - sfondi e riquadri grandi al massimo larghi quanto lo schermo, mai oltre il Full HD (1280 px sui dispositivi deboli);
     * - immagini di TMDB chieste già nella misura adatta (vedi [tmdb]); se quella non arriva si ripiega sull'indirizzo originale;
     * - su disco restano sia l'originale (niente nuovi download) sia la versione già ridotta (si rilegge senza rifare la decodifica).
     */
    fun img(v: ImageView, url: String?, w: Int, h: Int) {
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            Glide.with(v).clear(v)
            v.setImageDrawable(null)
            return
        }
        var tw = w.coerceAtLeast(1)
        var th = h.coerceAtLeast(1)
        val cap = maxSide(v.context)
        val big = maxOf(tw, th)
        if (big > cap) { tw = (tw.toLong() * cap / big).toInt().coerceAtLeast(1); th = (th.toLong() * cap / big).toInt().coerceAtLeast(1) }
        val small = tmdb(url, tw, th)
        var rq = request(v, small, tw, th)
        if (small != url) rq = rq.error(request(v, url, tw, th))
        rq.into(v)
    }

    private fun request(v: ImageView, url: String, w: Int, h: Int): RequestBuilder<Drawable> {
        var rq = Glide.with(v).load(url).override(w, h).dontAnimate().diskCacheStrategy(DiskCacheStrategy.ALL)
        if (rgb565) rq = rq.format(DecodeFormat.PREFER_RGB_565)
        // la stessa trasformazione che Glide sceglierebbe da solo per la vista: vale anche per la richiesta di riserva
        return when (v.scaleType) {
            ImageView.ScaleType.CENTER_CROP -> rq.optionalCenterCrop()
            ImageView.ScaleType.CENTER_INSIDE, ImageView.ScaleType.FIT_XY -> rq.optionalCenterInside()
            ImageView.ScaleType.FIT_CENTER, ImageView.ScaleType.FIT_START, ImageView.ScaleType.FIT_END -> rq.optionalFitCenter()
            else -> rq
        }
    }

    /** lato più lungo di un'immagine decodificata: quello dello schermo, mai oltre 1920 px (1280 sui dispositivi deboli) */
    private fun maxSide(c: Context): Int {
        val dm = c.resources.displayMetrics
        val screen = maxOf(dm.widthPixels, dm.heightPixels).takeIf { it > 0 } ?: 1920
        return minOf(screen, if (App.weak) 1280 else 1920)
    }

    private val TMDB = Regex("^(https?://image\\.tmdb\\.org/t/p/)([^/]+)(/.+)$", RegexOption.IGNORE_CASE)
    private val TMDB_W = Regex("^w(\\d{2,4})(_and_h\\d+.*)?$", RegexOption.IGNORE_CASE)

    /**
     * Locandine e sfondi di TMDB (image.tmdb.org/t/p/<misura>/…): si chiede una misura più piccola adatta alla vista,
     * mai più grande di quella dell'indirizzo. Locandine (viste verticali) w185 o w342, sfondi (viste orizzontali) w780 o w1280;
     * sui dispositivi deboli al massimo w185 e w780.
     */
    fun tmdb(url: String, w: Int, h: Int): String {
        if (!url.contains("image.tmdb.org", ignoreCase = true)) return url
        val m = TMDB.find(url) ?: return url
        val size = m.groupValues[2]
        val cur = if (size.equals("original", ignoreCase = true)) Int.MAX_VALUE else TMDB_W.find(size)?.groupValues?.get(1)?.toIntOrNull() ?: return url
        val weak = App.weak
        val want = if (h >= w) (if (weak || w <= 185) 185 else 342) else (if (weak || w <= 780) 780 else 1280)
        if (want >= cur) return url
        return m.groupValues[1] + "w" + want + m.groupValues[3]
    }

    /** vista uscita dallo schermo (riciclata) o schermata chiusa: via la richiesta, l'immagine torna nella cache con il tetto */
    fun release(v: ImageView?) {
        if (v == null) return
        try { Glide.with(v).clear(v) } catch (_: Exception) {}
    }

    /**
     * Entrando nel lettore le immagini lasciano spazio al buffer video: le schermate sotto liberano sfondi e riquadri
     * grandi (tornano da soli quando si torna indietro, vedi [BaseActivity.releaseBig]) e la cache in memoria si svuota.
     */
    fun freeForPlayer(player: Activity) {
        for (a in App.alive.toList()) if (a !== player && a is BaseActivity) try { a.releaseBig() } catch (_: Exception) {}
        try { Glide.get(player).clearMemory() } catch (_: Throwable) {}
        // le immagini appena liberate passano dalla cache al riciclo un attimo dopo: si svuota ancora una volta
        App.main.postDelayed({ try { Glide.get(App.I).clearMemory() } catch (_: Throwable) {} }, 400)
    }

    fun dialog(a: Activity, title: String, msg: String, ok: String = "OK", cancel: String? = null, onOk: (() -> Unit)? = null): AlertDialog? {
        if (a.isFinishing) return null
        val b = AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton(ok) { _, _ -> onOk?.invoke() }
        if (cancel != null) b.setNegativeButton(cancel, null)
        return try { b.show() } catch (_: Exception) { null }
    }

    fun input(a: Activity, title: String, hint: String, numeric: Boolean, initial: String = "", onOk: (String) -> Unit) {
        if (a.isFinishing) return
        val e = EditText(a)
        e.hint = hint
        e.setText(initial)
        e.setSingleLine()
        e.setTextColor(androidx.core.content.ContextCompat.getColor(a, R.color.text))
        e.setHintTextColor(androidx.core.content.ContextCompat.getColor(a, R.color.dim))
        if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER
        val box = LinearLayout(a)
        val pad = dp(a, 20)
        box.setPadding(pad, dp(a, 8), pad, 0)
        box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        try {
            AlertDialog.Builder(a).setTitle(title).setView(box)
                .setPositiveButton("OK") { _, _ -> onOk(e.text.toString().trim()) }
                .setNegativeButton("Annulla", null).show()
            e.requestFocus()
        } catch (_: Exception) {}
    }

    fun choose(a: Activity, title: String, items: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        if (a.isFinishing) return
        try {
            AlertDialog.Builder(a).setTitle(title)
                .setSingleChoiceItems(items, checked) { d, i -> d.dismiss(); onPick(i) }
                .setNegativeButton("Annulla", null).show()
        } catch (_: Exception) {}
    }

    /** codice grande al centro (collegamento di un'altra TV) */
    fun bigCode(a: Activity, title: String, code: String, note: String) {
        if (a.isFinishing) return
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(a, 24)
        box.setPadding(pad, dp(a, 8), pad, 0)
        val t = TextView(a)
        t.text = code
        t.textSize = 44f
        t.letterSpacing = 0.12f
        t.setTextColor(androidx.core.content.ContextCompat.getColor(a, R.color.text))
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val n = TextView(a)
        n.text = note
        n.setTextColor(androidx.core.content.ContextCompat.getColor(a, R.color.muted))
        n.textSize = 15f
        n.gravity = Gravity.CENTER
        box.addView(t)
        box.addView(n)
        try { AlertDialog.Builder(a).setTitle(title).setView(box).setPositiveButton("Fatto", null).show() } catch (_: Exception) {}
    }
}
