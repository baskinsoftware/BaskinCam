package io.github.baskinsoftware.baskincam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * BallTracker - wrapper attorno a MediaPipe Tasks Object Detector
 * (EfficientDet-Lite0). Uso puramente cosmetico (non alimenta dati/
 * statistiche): filtra la classe COCO "sports ball" e restituisce
 * l'ultima posizione normalizzata (0..1) per alimentare PanZoomController.
 *
 * Gira su onFrame() chiamato dal thread di analisi CameraX (background,
 * mai il main thread) - detect() e' bloccante ma va bene qui perche' non
 * siamo su UI thread e CameraX scarta gia' i frame in eccesso
 * (ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST in CameraController).
 *
 * MODELLO: richiede efficientdet_lite0.tflite in app/src/main/assets/
 * (NON committato in questo scheletro - vedi assets/models/README.md per
 * il link di download). Se il modello manca, l'inizializzazione fallisce
 * in modo silenzioso e onFrame() diventa no-op con "palla sempre persa":
 * la pipeline video continua comunque a funzionare (PanZoomController
 * resta a campo largo/centro), solo senza pan/zoom automatico.
 */
class BallTracker(
    context: Context,
    private val onBallDetected: (xNorm: Float, yNorm: Float, confidence: Float) -> Unit,
    private val onBallLost: () -> Unit
) {
    companion object {
        private const val TAG = "BallTracker"
        private const val MODEL_ASSET_PATH = "efficientdet_lite0.tflite"
        private const val SCORE_THRESHOLD = 0.4f
        private const val MAX_RESULTS = 5
    }

    private var framesSinceLastDetection = 0
    private val maxFramesBeforeLost = 15 // ~1s a 15fps di analisi

    private val detector: ObjectDetector? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .setDelegate(Delegate.GPU)
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setCategoryAllowlist(listOf("sports ball"))
            .build()

        ObjectDetector.createFromOptions(context, options)
    } catch (e: Exception) {
        // Modello mancante, delegate GPU non disponibile sul device, o
        // altro errore di inizializzazione: log e degrado controllato
        // (nessun crash, la pipeline video prosegue senza tracking).
        Log.e(TAG, "Inizializzazione ObjectDetector fallita: pan/zoom automatico disattivato", e)
        null
    }

    /**
     * Da chiamare per ogni frame a bassa risoluzione (vedi
     * CameraController.ANALYSIS_LOW_RES_WIDTH/HEIGHT).
     */
    fun onFrame(bitmap: Bitmap) {
        val activeDetector = detector
        if (activeDetector == null) {
            onBallLost()
            return
        }

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = activeDetector.detect(mpImage)

            val best = result.detections().maxByOrNull { detection ->
                detection.categories().firstOrNull()?.score() ?: 0f
            }

            if (best != null) {
                val box = best.boundingBox()
                val confidence = best.categories().firstOrNull()?.score() ?: 0f
                val xNorm = ((box.left + box.right) / 2f) / bitmap.width
                val yNorm = ((box.top + box.bottom) / 2f) / bitmap.height

                framesSinceLastDetection = 0
                onBallDetected(xNorm.coerceIn(0f, 1f), yNorm.coerceIn(0f, 1f), confidence)
            } else {
                registerMiss()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Errore durante detect(), frame scartato", e)
            registerMiss()
        }
    }

    private fun registerMiss() {
        framesSinceLastDetection++
        if (framesSinceLastDetection > maxFramesBeforeLost) {
            onBallLost()
        }
    }

    fun close() {
        detector?.close()
    }
}
