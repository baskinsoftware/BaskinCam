package io.github.baskinsoftware.baskincameisi

import android.content.Context

/**
 * AppPreferences - configurazione persistita (SharedPreferences), oggi
 * limitata all'URL RTMP di destinazione. Nessuna dipendenza esterna,
 * nessun DataStore: per un singolo valore stringa non serve altro.
 */
object AppPreferences {
    private const val PREFS_NAME = "baskincam_prefs"
    private const val KEY_RTMP_URL = "rtmp_url"
    private const val KEY_SETUP_ZOOM = "setup_zoom_ratio"

    /** Zoom sensore di setup: 1.0 = nessuno. Vedi CameraController. */
    const val DEFAULT_SETUP_ZOOM = 1.0f
    const val MIN_SETUP_ZOOM = 1.0f
    const val MAX_SETUP_ZOOM = 3.0f

    fun getRtmpUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RTMP_URL, "") ?: ""
    }

    fun setRtmpUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RTMP_URL, url.trim()).apply()
    }

    /**
     * Zoom sensore applicato in fase di setup (ottico/ibrido/digitale,
     * gestito da CameraX in modo trasparente). Serve a stringere
     * l'inquadratura fissa fino a coprire esattamente il campo, cosi' i
     * pixel del sensore sono tutti spesi sul campo e il crop dinamico
     * parte da una base piu' densa. NON e' lo zoom che segue l'azione
     * (quello resta crop digitale in PanZoomController).
     */
    fun getSetupZoomRatio(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_SETUP_ZOOM, DEFAULT_SETUP_ZOOM)
            .coerceIn(MIN_SETUP_ZOOM, MAX_SETUP_ZOOM)
    }

    fun setSetupZoomRatio(context: Context, ratio: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_SETUP_ZOOM, ratio.coerceIn(MIN_SETUP_ZOOM, MAX_SETUP_ZOOM)).apply()
    }

    /**
     * Validazione minima: rtmp:// o rtmps://, non vuoto. Non garantisce
     * che l'URL sia raggiungibile - solo che ha una forma plausibile,
     * per evitare di passare stringhe vuote/malformate a RootEncoder.
     */
    fun isValidRtmpUrl(url: String): Boolean =
        url.startsWith("rtmp://") || url.startsWith("rtmps://")
}
