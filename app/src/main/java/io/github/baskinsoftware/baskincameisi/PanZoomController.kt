package io.github.baskinsoftware.baskincameisi

/**
 * PanZoomController - calcola la regione di crop (pan+zoom digitale) da applicare
 * al frame 4K sorgente, per seguire l'azione senza scatti bruschi.
 *
 * Logica:
 * - modalita' Auto: centro = smoothing esponenziale sulla posizione palla
 * - modalita' Manuale: centro fisso impostato via override (tap schermo o
 *   controllo remoto), ignora BallTracker finche' non si torna in Auto
 * - se la palla si perde per troppi frame, il crop torna gradualmente al
 *   campo largo (centro frame, zoom minimo)
 */
class PanZoomController(
    private val smoothingAlpha: Float = 0.18f,
    private val minZoom: Float = 1.0f,
    private val maxZoom: Float = 1.8f
) {

    enum class Mode { AUTO, MANUAL }

    var mode: Mode = Mode.AUTO
        private set

    private var currentCenterX = 0.5f
    private var currentCenterY = 0.5f
    private var currentZoom = minZoom

    private var manualCenterX = 0.5f
    private var manualCenterY = 0.5f

    fun setManualTarget(xNorm: Float, yNorm: Float) {
        mode = Mode.MANUAL
        manualCenterX = xNorm.coerceIn(0f, 1f)
        manualCenterY = yNorm.coerceIn(0f, 1f)
    }

    fun setAutoMode() {
        mode = Mode.AUTO
    }

    /**
     * Da chiamare quando BallTracker rileva una posizione valida.
     * Ignorato se mode == MANUAL.
     */
    fun onBallPosition(xNorm: Float, yNorm: Float) {
        if (mode == Mode.MANUAL) return
        currentCenterX += smoothingAlpha * (xNorm - currentCenterX)
        currentCenterY += smoothingAlpha * (yNorm - currentCenterY)
        // TODO: modulare currentZoom in base alla velocita' di movimento
        // della palla tra frame successivi (piu' rapido -> zoom piu' largo).
    }

    /**
     * Da chiamare quando BallTracker segnala perdita del target.
     * Riporta gradualmente il crop verso il campo largo.
     */
    fun onBallLost() {
        if (mode == Mode.MANUAL) return
        val recenterAlpha = smoothingAlpha * 0.5f
        currentCenterX += recenterAlpha * (0.5f - currentCenterX)
        currentCenterY += recenterAlpha * (0.5f - currentCenterY)
        currentZoom += recenterAlpha * (minZoom - currentZoom)
    }

    /**
     * Ritorna il rettangolo di crop normalizzato (0..1) da applicare al
     * frame sorgente, calcolato a partire dal centro e zoom correnti.
     */
    fun currentCropRect(): CropRect {
        val cx = if (mode == Mode.MANUAL) manualCenterX else currentCenterX
        val cy = if (mode == Mode.MANUAL) manualCenterY else currentCenterY
        val zoom = if (mode == Mode.MANUAL) maxZoom * 0.7f else currentZoom

        val halfW = 0.5f / zoom
        val halfH = 0.5f / zoom

        val left = (cx - halfW).coerceIn(0f, 1f - 2 * halfW)
        val top = (cy - halfH).coerceIn(0f, 1f - 2 * halfH)

        return CropRect(left, top, left + 2 * halfW, top + 2 * halfH)
    }

    data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
