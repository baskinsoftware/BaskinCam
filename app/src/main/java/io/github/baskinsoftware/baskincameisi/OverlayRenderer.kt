package io.github.baskinsoftware.baskincameisi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView

/**
 * OverlayRenderer - WebView offscreen che carica /display dal server locale
 * (LocalControlServer), lo STESSO endpoint che una TV/tablet sulla LAN
 * aprirebbe per vedere il tabellone. Nessuna iniezione JS manuale: il
 * WebView fa semplicemente polling di /status come qualunque altro client,
 * garantendo che overlay video e display esterno mostrino sempre
 * esattamente la stessa cosa con lo stesso identico codice di rendering.
 *
 * Sfondo: nero pieno, identico alla PWA (nessuna trasparenza richiesta).
 *
 * Va istanziato SOLO dopo che LocalControlServer e' gia' in ascolto,
 * altrimenti il primo caricamento fallisce (il polling successivo si
 * riprende comunque da solo, ma meglio evitare l'errore iniziale).
 */
class OverlayRenderer(context: Context, controlServerPort: Int) {

    private val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        loadUrl("http://127.0.0.1:$controlServerPort/display")
    }

    /**
     * Cattura il WebView come bitmap per il compositing sopra il frame video.
     * Frequenza moderata (2-4 volte/sec e' piu' che sufficiente: il display
     * si aggiorna comunque via polling a 1s con interpolazione locale del
     * cronometro, vedi assets/overlay/index.html).
     */
    fun captureFrame(widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, widthPx, heightPx)
        webView.draw(canvas)
        return bitmap
    }

    fun destroy() {
        webView.destroy()
    }
}
