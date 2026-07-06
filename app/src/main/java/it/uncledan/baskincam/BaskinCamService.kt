package it.uncledan.baskincam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject

/**
 * BaskinCamService - Foreground Service che orchestra l'intera pipeline:
 *
 *   CameraX (4K) --> BallTracker (detection a bassa risoluzione)
 *                 --> PanZoomController (calcolo crop smoothed)
 *                 --> crop + scala 1080p
 *                 --> OverlayRenderer (WebView tabellone LED)
 *                 --> StreamEncoder (RootEncoder, push RTMP)
 *
 * Gira anche a schermo spento grazie a foreground service + wake lock parziale.
 * LocalControlServer espone endpoint HTTP sulla LAN per:
 *   - override manuale del punto di interesse
 *   - start/stop
 *   - ricezione push del punteggio da Tabellone Baskin
 */
class BaskinCamService : LifecycleService() {

    companion object {
        const val ACTION_START = "it.uncledan.baskincam.action.START"
        const val ACTION_STOP = "it.uncledan.baskincam.action.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "baskincam_running"
        private const val NOTIFICATION_ID = 1

        // Output finale dello streaming (crop 4K -> qui).
        private const val OUTPUT_WIDTH = 1920
        private const val OUTPUT_HEIGHT = 1080

        // Riquadro overlay tabellone: angolo alto-destra, dimensione fissa
        // indipendente dallo zoom (vedi discussione di progetto: il tabellone
        // "maxi" deve stare comodo in un angolo, stile ripresa reale).
        private const val OVERLAY_WIDTH = 420
        private const val OVERLAY_HEIGHT = 260
        private const val OVERLAY_MARGIN = 24

        // Ricattura del WebView overlay: non serve a 30fps, il punteggio
        // cambia di rado e index.html interpola gia' il tempo localmente.
        private const val OVERLAY_CAPTURE_INTERVAL_MS = 250L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var controlServer: LocalControlServer? = null
    private var cameraController: CameraController? = null
    private var overlayRenderer: OverlayRenderer? = null
    private var streamEncoder: StreamEncoder? = null

    private lateinit var ballTracker: BallTracker
    private lateinit var panZoom: PanZoomController

    private val mainHandler = Handler(Looper.getMainLooper())

    // Bitmap overlay piu' recente, aggiornato solo sul main thread (WebView)
    // ma letto dal thread di analisi CameraX per il compositing: @Volatile
    // e' sufficiente qui, non serve altra sincronizzazione perche' e' una
    // singola reference sostituita atomicamente, mai mutata in-place.
    @Volatile
    private var cachedOverlayBitmap: Bitmap? = null

    private val overlayCaptureLoop = object : Runnable {
        override fun run() {
            overlayRenderer?.let { renderer ->
                cachedOverlayBitmap = renderer.captureFrame(OVERLAY_WIDTH, OVERLAY_HEIGHT)
            }
            mainHandler.postDelayed(this, OVERLAY_CAPTURE_INTERVAL_MS)
        }
    }

    /**
     * Binder per MainActivity: espone solo cio' che serve alla UI in
     * primo piano (anteprima camera, override manuale, stato). Il resto
     * della pipeline resta interno al Service - MainActivity non tocca
     * mai direttamente CameraController/PanZoomController/StreamEncoder.
     */
    inner class LocalBinder : Binder() {
        fun getService(): BaskinCamService = this@BaskinCamService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    /** true se la pipeline (camera/overlay/eventuale encoder) e' attiva. */
    fun isPipelineRunning(): Boolean = cameraController != null

    fun isStreaming(): Boolean = streamEncoder?.isStreaming == true

    /** Instrada la preview camera verso la PreviewView di MainActivity, se visibile. */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        cameraController?.attachPreviewSurfaceProvider(surfaceProvider)
    }

    /** Da chiamare quando MainActivity va in background: risparmia risorse. */
    fun detachPreview() {
        cameraController?.attachPreviewSurfaceProvider(null)
    }

    fun setManualTarget(xNorm: Float, yNorm: Float) {
        if (::panZoom.isInitialized) panZoom.setManualTarget(xNorm, yNorm)
    }

    fun setAutoMode() {
        if (::panZoom.isInitialized) panZoom.setAutoMode()
    }

    fun currentModeIsManual(): Boolean =
        ::panZoom.isInitialized && panZoom.mode == PanZoomController.Mode.MANUAL

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startPipeline()
            ACTION_STOP -> stopPipeline()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        if (pipelineActive) return  // gia' in esecuzione (es. doppio tap su "Avvia"): no-op
        pipelineActive = true

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        acquireWakeLock()

        panZoom = PanZoomController()
        ballTracker = BallTracker(
            context = applicationContext,
            onBallDetected = { x, y, _ -> panZoom.onBallPosition(x, y) },
            onBallLost = { panZoom.onBallLost() }
        )

        controlServer = LocalControlServer(
            context = applicationContext,
            port = 8080,
            onScoreUpdate = { _ ->
                // Nessun inoltro manuale necessario: LocalControlServer tiene
                // gia' internamente l'ultimo stato e lo serve via /status,
                // che sia OverlayRenderer che qualunque browser esterno
                // (TV/tablet) leggono via polling autonomo. Questo hook
                // resta disponibile per usi futuri (es. logging, trigger
                // di eventi particolari sul cambio punteggio).
            },
            onManualOverride = { x, y -> mainHandler.post { panZoom.setManualTarget(x, y) } },
            onAutoMode = { mainHandler.post { panZoom.setAutoMode() } },
            onRtmpUrlChanged = { newUrl -> mainHandler.post { restartStreamEncoder(newUrl) } },
            onSetupZoomChanged = { ratio -> mainHandler.post { updateSetupZoom(ratio) } },
            onStopRequested = { mainHandler.post { stopPipeline() } },
            statusProvider = {
                JSONObject().apply {
                    put("mode", if (panZoom.mode == PanZoomController.Mode.MANUAL) "manual" else "auto")
                    put("streaming", streamEncoder?.isStreaming == true)
                    put("recording", cameraController != null)
                }.toString()
            }
        ).also { it.start() }

        // OverlayRenderer richiede il WebView su main thread: BaskinCamService
        // (Service normale) riceve onStartCommand gia' sul main thread, quindi
        // l'istanziazione qui e' sicura senza post() esplicito.
        overlayRenderer = OverlayRenderer(applicationContext, controlServerPort = 8080)
        mainHandler.post(overlayCaptureLoop)

        streamEncoder = createStreamEncoderIfConfigured()

        // this (LifecycleService) e' il LifecycleOwner: CameraX segue
        // automaticamente lo stato del Service (STARTED finche' il service
        // e' vivo, indipendentemente dallo stato di MainActivity).
        cameraController = CameraController(applicationContext, this).apply {
            onLowResFrame = { bitmap -> ballTracker.onFrame(bitmap) }
            onHighResFrame = { bitmap -> processAndEncodeFrame(bitmap) }
            setSetupZoom(AppPreferences.getSetupZoomRatio(applicationContext))
            start()
        }
    }

    /**
     * Zoom sensore di setup: persiste il valore e lo applica alla camera
     * attiva. Chiamabile da MainActivity (binder) o da LocalControlServer
     * (POST /config/zoom). Da usare in fase di posizionamento, non durante
     * l'azione (vedi CameraController.setSetupZoom).
     */
    fun updateSetupZoom(ratio: Float) {
        AppPreferences.setSetupZoomRatio(applicationContext, ratio)
        cameraController?.setSetupZoom(AppPreferences.getSetupZoomRatio(applicationContext))
    }

    /**
     * Crea e avvia StreamEncoder solo se in AppPreferences e' gia' salvato
     * un URL RTMP valido. Se manca, la pipeline prosegue comunque (camera,
     * overlay, display su /display restano attivi): lo streaming e' una
     * funzione opzionale sopra il resto, non un prerequisito - vedi nota
     * di architettura nel README su BaskinCam usabile anche solo come
     * "tabellone su TV" senza streaming video.
     */
    private fun createStreamEncoderIfConfigured(): StreamEncoder? {
        val url = AppPreferences.getRtmpUrl(applicationContext)
        if (!AppPreferences.isValidRtmpUrl(url)) {
            // Nessun log di errore: e' una condizione normale finche'
            // l'operatore non configura l'URL da /config/rtmp.
            return null
        }
        return StreamEncoder(context = applicationContext, rtmpUrl = url).apply { start() }
    }

    /**
     * Callback da LocalControlServer quando l'URL RTMP viene aggiornato
     * (POST /config/rtmp). Ferma l'encoder corrente (se attivo) e ne avvia
     * uno nuovo con il nuovo URL, senza toccare il resto della pipeline
     * (camera/overlay continuano a girare invariati).
     */
    private fun restartStreamEncoder(newUrl: String) {
        streamEncoder?.stop()
        streamEncoder = if (AppPreferences.isValidRtmpUrl(newUrl)) {
            StreamEncoder(context = applicationContext, rtmpUrl = newUrl).apply { start() }
        } else {
            null
        }
    }

    /**
     * Pipeline per ogni frame 4K: crop secondo PanZoomController, scala a
     * 1920x1080, overlay tabellone compositato in alto a destra, invio
     * all'encoder. Gira sul thread di analisi CameraX (background), MAI sul
     * main thread: solo la cattura del bitmap overlay (overlayCaptureLoop)
     * tocca il main thread, qui si legge solo la cache gia' pronta.
     */
    private fun processAndEncodeFrame(sourceBitmap: Bitmap) {
        val crop = panZoom.currentCropRect()
        val cropped = cropAndScale(sourceBitmap, crop, OUTPUT_WIDTH, OUTPUT_HEIGHT)

        cachedOverlayBitmap?.let { overlay ->
            val canvas = Canvas(cropped)
            val left = OUTPUT_WIDTH - overlay.width - OVERLAY_MARGIN
            val top = OVERLAY_MARGIN
            canvas.drawBitmap(overlay, left.toFloat(), top.toFloat(), null)
        }

        streamEncoder?.onCompositeFrame(cropped)
    }

    /**
     * Ritaglia sourceBitmap secondo il rettangolo normalizzato (0..1) di
     * PanZoomController e scala il risultato a outW x outH.
     */
    private fun cropAndScale(
        source: Bitmap,
        crop: PanZoomController.CropRect,
        outW: Int,
        outH: Int
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val rect = Rect(
            (crop.left * srcW).toInt().coerceIn(0, srcW - 1),
            (crop.top * srcH).toInt().coerceIn(0, srcH - 1),
            (crop.right * srcW).toInt().coerceIn(1, srcW),
            (crop.bottom * srcH).toInt().coerceIn(1, srcH)
        )
        val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        return Bitmap.createScaledBitmap(cropped, outW, outH, true)
    }

    @Volatile
    private var pipelineActive = false

    private fun stopPipeline() {
        if (!pipelineActive) return  // idempotente: stopSelf() qui sotto richiama onDestroy(),
                                      // che chiama di nuovo stopPipeline() - senza questa guardia
                                      // si rischia un doppio close() sul detector MediaPipe.
        pipelineActive = false
        mainHandler.removeCallbacks(overlayCaptureLoop)
        controlServer?.stop()
        controlServer = null
        cameraController?.stop()
        cameraController = null
        if (::ballTracker.isInitialized) ballTracker.close()
        streamEncoder?.stop()
        streamEncoder = null
        overlayRenderer?.destroy()
        overlayRenderer = null
        cachedOverlayBitmap = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BaskinCam::CapturingWakeLock"
        ).apply { acquire(12 * 60 * 60 * 1000L /* 12h max, safety */) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title_recording))
            .setContentText(getString(R.string.notification_text_tap_to_open))
            .setSmallIcon(R.drawable.ic_notification_rec)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        stopPipeline()
        super.onDestroy()
    }
}
