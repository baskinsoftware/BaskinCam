package io.github.baskinsoftware.baskincam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.library.generic.GenericStream

/**
 * StreamEncoder - wrapper attorno a RootEncoder (GenericStream) per push
 * RTMP verso YouTube Live / Facebook Live (o server RTMP proprio).
 *
 * Riceve i frame gia' compositi (crop 1080p + overlay tabellone) come
 * Bitmap e li disegna sulla Surface di input dell'encoder tramite
 * lockCanvas/unlockCanvasAndPost.
 *
 * ATTENZIONE VERSIONE LIBRERIA: RootEncoder evolve rapidamente e le firme
 * esatte di GenericStream/ConnectChecker sono cambiate piu' volte tra
 * major version. Il codice sotto riflette il pattern generale della
 * libreria (GenericStream + ConnectChecker + getGlInterface() per input
 * Surface personalizzato invece della camera interna), ma va VERIFICATO
 * e adattato contro la versione effettivamente dichiarata in
 * app/build.gradle (com.github.pedroSG94.RootEncoder:library:2.5.3) prima
 * di compilare - controllare gli esempi ufficiali del repo GitHub per
 * quella versione specifica, in particolare per il metodo di attacco di
 * un input Surface esterno (non e' sempre chiamato allo stesso modo tra
 * le versioni).
 */
class StreamEncoder(
    private val context: Context,
    private val rtmpUrl: String,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val bitrateBps: Int = 4_500_000,
    private val fps: Int = 30
) {
    companion object {
        private const val TAG = "StreamEncoder"
    }

    var isStreaming: Boolean = false
        private set

    private var encoderSurface: Surface? = null

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            Log.i(TAG, "Connessione RTMP avviata: $url")
        }
        override fun onConnectionSuccess() {
            Log.i(TAG, "Connesso, streaming attivo")
        }
        override fun onConnectionFailed(reason: String) {
            Log.e(TAG, "Connessione fallita: $reason")
            // TODO: notificare LocalControlServer/UI per mostrare stato errore
            // e valutare un retry automatico con backoff.
        }
        override fun onNewBitrate(bitrate: Long) { /* opzionale: log/monitor */ }
        override fun onDisconnect() {
            Log.i(TAG, "Disconnesso")
            isStreaming = false
        }
        override fun onAuthError() {
            Log.e(TAG, "Errore autenticazione RTMP")
        }
        override fun onAuthSuccess() { /* no-op */ }
    }

    private val genericStream = GenericStream(context, connectChecker)

    fun start() {
        val videoPrepared = genericStream.prepareVideo(width, height, bitrateBps, fps)      
        val audioPrepared = genericStream.prepareAudio(bitrate = 128 * 1024, isStereo = true, sampleRate = 44100)
        if (!videoPrepared || !audioPrepared) {
            Log.e(TAG, "prepareVideo/prepareAudio falliti - controllo parametri encoder")
            return
        }

        // Input Surface personalizzato: la pipeline BaskinCam disegna i
        // frame gia' compositi (crop+overlay) qui invece di far catturare
        // la camera direttamente a RootEncoder (che verrebbe scavalcato,
        // dato che la cattura reale e' gestita da CameraController/CameraX).
        encoderSurface = genericStream.getGlInterface().surface

        genericStream.startStream(rtmpUrl)
        isStreaming = true
    }

    fun stop() {
        if (genericStream.isStreaming) {
            genericStream.stopStream()
        }
        encoderSurface = null
        isStreaming = false
    }

    /**
     * Riceve un frame gia' composito (crop + overlay) e lo disegna sulla
     * Surface di input dell'encoder. No-op sicuro se l'encoder non e'
     * ancora pronto (es. primi frame durante l'avvio).
     */
    fun onCompositeFrame(bitmap: Bitmap) {
        val surface = encoderSurface
        if (surface == null || !surface.isValid) {
            // Silenzioso by design: puo' capitare regolarmente nei primi
            // istanti dopo start() prima che RootEncoder sia pronto.
            return
        }
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            Log.w(TAG, "lockCanvas fallito, frame scartato", e)
            return
        }
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
