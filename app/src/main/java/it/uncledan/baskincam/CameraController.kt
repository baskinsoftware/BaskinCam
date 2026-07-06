package it.uncledan.baskincam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraController - unica sessione CameraX dell'app, posseduta dal
 * Foreground Service (BaskinCamService), cosi' continua a catturare anche
 * a schermo spento / Activity non in primo piano.
 *
 * Design:
 * - UN SOLO ImageAnalysis use case ad alta risoluzione (target ~4K, il
 *   massimo che il sensore offre in modo affidabile). CameraX non supporta
 *   il binding di due ImageAnalysis contemporaneamente, quindi il frame a
 *   bassa risoluzione per BallTracker viene ottenuto per downscale software
 *   dello stesso frame ad alta risoluzione, non da una sessione separata.
 * - Preview use case OPZIONALE: bindata solo se l'Activity e' in primo
 *   piano e fornisce un SurfaceProvider (vedi attachPreviewSurfaceProvider).
 *   Se l'Activity non e' visibile, il Preview use case resta unbound:
 *   meno lavoro per la GPU, la pipeline di analisi/encoding non dipende
 *   comunque da esso.
 * - Formato output RGBA_8888 (richiede camera-core >= 1.3): permette di
 *   costruire un Bitmap direttamente dal buffer, senza conversione YUV
 *   manuale.
 *
 * Requisito Manifest/lifecycle: BaskinCamService deve estendere
 * androidx.lifecycle.LifecycleService (non Service semplice) per poter
 * fungere da LifecycleOwner qui.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "CameraController"

        // Target di cattura: 4K se il sensore lo offre, altrimenti CameraX
        // sceglie la risoluzione piu' vicina disponibile (ResolutionStrategy
        // FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER).
        private const val TARGET_WIDTH = 3840
        private const val TARGET_HEIGHT = 2160

        // Frame passati a BallTracker: bassa risoluzione, sufficiente per
        // detection cosmetica, riduce carico GPU/CPU dell'inferenza.
        const val ANALYSIS_LOW_RES_WIDTH = 320
        const val ANALYSIS_LOW_RES_HEIGHT = 180
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null

    // Zoom sensore di setup richiesto (verra' applicato/riapplicato ad
    // ogni bind). 1.0 = nessuno. Vedi setSetupZoom().
    @Volatile
    private var requestedSetupZoom: Float = 1.0f

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Callback invocata per ogni frame ad alta risoluzione, gia' pronto
     * per il crop (PanZoomController) + overlay + encoding.
     * Il Bitmap e' valido solo per la durata della callback: se serve
     * trattenerlo oltre, va copiato (Bitmap.copy).
     */
    var onHighResFrame: ((Bitmap) -> Unit)? = null

    /**
     * Callback invocata con il frame downscalato per BallTracker.
     */
    var onLowResFrame: ((Bitmap) -> Unit)? = null

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(TARGET_WIDTH, TARGET_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) { imageProxy -> processFrame(imageProxy) }
            }
        val analysis = imageAnalysis ?: return

        preview = Preview.Builder().build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Preview NON bindata di default: viene aggiunta solo quando
            // l'Activity chiama attachPreviewSurfaceProvider(). Bindiamo
            // qui solo ImageAnalysis, che e' cio' che serve alla pipeline
            // headless (funziona anche a schermo spento).
            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, analysis)
            applySetupZoom()
            Log.i(TAG, "CameraX ImageAnalysis bound, target ${TARGET_WIDTH}x${TARGET_HEIGHT}")
        } catch (e: Exception) {
            Log.e(TAG, "Bind CameraX fallito", e)
        }
    }

    /**
     * Da chiamare da MainActivity quando visibile, per instradare il feed
     * anche verso la preview a schermo. Rebinda includendo anche Preview.
     * Da chiamare con null quando l'Activity va in background, per
     * ri-semplificare il binding (risparmio risorse a schermo spento).
     */
    fun attachPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        val provider = cameraProvider ?: return
        val analysis = imageAnalysis ?: return
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        provider.unbindAll()
        try {
            camera = if (surfaceProvider != null) {
                val previewUseCase = preview ?: return
                previewUseCase.setSurfaceProvider(surfaceProvider)
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, previewUseCase, analysis)
            } else {
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, analysis)
            }
            // Lo zoom va riapplicato dopo OGNI rebind: unbindAll() azzera
            // lo stato del CameraControl.
            applySetupZoom()
        } catch (e: Exception) {
            Log.e(TAG, "Re-bind CameraX (preview toggle) fallito", e)
        }
    }

    /**
     * Imposta lo zoom sensore di setup (ottico/ibrido/digitale, deciso da
     * CameraX/HAL in base all'hardware: sui multi-camera puo' comportare
     * il cambio automatico di lente). Da usare SOLO in fase di
     * posizionamento del treppiede, con valore poi fisso per tutta la
     * partita: lo zoom dinamico che segue l'azione resta il crop digitale
     * di PanZoomController (vedi discussione architetturale nel README -
     * il sensore deve continuare a vedere l'intero campo, altrimenti la
     * detection perde la palla senza possibilita' di recupero).
     */
    fun setSetupZoom(ratio: Float) {
        requestedSetupZoom = ratio
        applySetupZoom()
    }

    private fun applySetupZoom() {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value
        val maxZoom = zoomState?.maxZoomRatio ?: 1.0f
        val minZoom = zoomState?.minZoomRatio ?: 1.0f
        val clamped = requestedSetupZoom.coerceIn(minZoom, maxZoom)
        if (clamped != requestedSetupZoom) {
            Log.w(TAG, "Zoom richiesto $requestedSetupZoom fuori range hardware [$minZoom..$maxZoom], applicato $clamped")
        }
        cam.cameraControl.setZoomRatio(clamped)
        Log.i(TAG, "Setup zoom applicato: $clamped")
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val highResBitmap = imageProxy.toRgbaBitmap()
            if (highResBitmap != null) {
                onHighResFrame?.invoke(highResBitmap)

                val lowRes = Bitmap.createScaledBitmap(
                    highResBitmap, ANALYSIS_LOW_RES_WIDTH, ANALYSIS_LOW_RES_HEIGHT, true
                )
                onLowResFrame?.invoke(lowRes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore elaborazione frame", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Converte un ImageProxy in formato RGBA_8888 (singolo plane) in Bitmap.
     * Richiede ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 configurato sopra.
     */
    private fun ImageProxy.toRgbaBitmap(): Bitmap? {
        if (format != android.graphics.PixelFormat.RGBA_8888) {
            Log.w(TAG, "Formato frame inatteso: $format")
            return null
        }
        val plane = planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
