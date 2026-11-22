package io.github.baskinsoftware.baskincam

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * LocalControlServer - server HTTP locale sulla LAN (NanoHTTPD).
 *
 * Endpoint previsti:
 *   POST /score            <- push del payload punteggio da Tabellone Baskin
 *   GET  /status            <- ultimo stato ricevuto, in JSON (polling)
 *   GET  /display           <- pagina HTML del tabellone (identica alla PWA),
 *                              apribile da qualunque browser sulla LAN
 *                              (TV, tablet) oltre che dal WebView offscreen
 *                              usato per l'overlay video
 *   GET  /config            <- configurazione corrente (es. URL RTMP)
 *   POST /config/rtmp {url} <- imposta/aggiorna l'URL RTMP di destinazione,
 *                              persistito (AppPreferences) e propagato alla
 *                              pipeline in esecuzione senza riavviare l'app
 *   POST /config/zoom {ratio} <- zoom sensore di setup (1.0-3.0), applicato
 *                              a caldo e persistito. Da usare in fase di
 *                              posizionamento treppiede, non durante l'azione
 *   POST /control/manual {x, y}   <- override manuale punto di interesse (0..1 normalizzato)
 *   POST /control/auto    <- torna a modalita' automatica
 *   POST /control/start   <- no-op documentato (vedi implementazione):
 *                              se il server risponde, la pipeline e' gia'
 *                              attiva per definizione. Mantenuto per
 *                              simmetria dell'API.
 *   POST /control/stop    <- ferma l'intera pipeline (equivalente ad ACTION_STOP)
 *   GET  /control/status  <- stato pipeline (per pagina di regia remota, diverso da /status punteggio)
 *
 * /display e il WebView offscreen di OverlayRenderer condividono lo STESSO
 * meccanismo: entrambi fanno polling di /status ogni secondo (vedi
 * assets/overlay/index.html) - nessuna differenza di codice tra "overlay
 * per lo streaming" e "display pubblico su TV/tablet".
 *
 * Nessuna autenticazione nella v1: presuppone rete locale fidata (stessa LAN
 * campo/palestra). TODO valutare in futuro un token semplice condiviso via
 * QR code all'avvio, se serve un minimo di protezione su reti condivise.
 */
class LocalControlServer(
    private val context: Context,
    port: Int,
    private val onScoreUpdate: (JSONObject) -> Unit,
    private val onManualOverride: (x: Float, y: Float) -> Unit,
    private val onAutoMode: () -> Unit,
    private val onRtmpUrlChanged: (String) -> Unit,
    private val onSetupZoomChanged: (Float) -> Unit,
    private val onStopRequested: () -> Unit,
    private val statusProvider: () -> String
) : NanoHTTPD(port) {

    // Ultimo payload ricevuto da Tabellone Baskin: fonte di verita' unica
    // sia per /status (polling display) sia per l'eventuale lettura interna.
    @Volatile
    private var lastScoreJson: String = defaultScoreJson()

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/score" -> handleScore(session)
                "/status" -> okJson(lastScoreJson)
                "/display" -> serveDisplayPage()
                "/config" -> okJson(configJson())
                "/config/rtmp" -> handleRtmpConfig(session)
                "/config/zoom" -> handleZoomConfig(session)
                "/control/manual" -> handleManual(session)
                "/control/auto" -> {
                    onAutoMode()
                    okJson("""{"ok":true}""")
                }
                "/control/start" -> {
                    // No-op documentato: se questo endpoint risponde, il
                    // Service (e quindi la pipeline) e' per definizione gia'
                    // in esecuzione - LocalControlServer vive dentro
                    // startPipeline(). Mantenuto per simmetria dell'API e
                    // per eventuali usi futuri (es. fasi separate camera/
                    // streaming), non collegato a nulla oggi.
                    okJson("""{"ok":true,"note":"pipeline gia' attiva"}""")
                }
                "/control/stop" -> {
                    onStopRequested()
                    okJson("""{"ok":true}""")
                }
                "/control/status" -> okJson(statusProvider())
                "/" -> newFixedLengthResponse(Response.Status.OK, "text/html", remoteControlPageHtml())
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"ok":false,"error":"${e.message}"}"""
            )
        }
    }

    private fun handleRtmpConfig(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return okJson(configJson())
        }
        val body = readBody(session)
        val json = JSONObject(body)
        val url = json.optString("url", "").trim()
        if (!AppPreferences.isValidRtmpUrl(url)) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"ok":false,"error":"URL non valido, atteso rtmp:// o rtmps://"}"""
            )
        }
        AppPreferences.setRtmpUrl(context, url)
        onRtmpUrlChanged(url)
        return okJson("""{"ok":true}""")
    }

    private fun handleZoomConfig(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return okJson(configJson())
        }
        val body = readBody(session)
        val json = JSONObject(body)
        val ratio = json.optDouble("ratio", Double.NaN)
        if (ratio.isNaN() || ratio < AppPreferences.MIN_SETUP_ZOOM || ratio > AppPreferences.MAX_SETUP_ZOOM) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"ok":false,"error":"ratio atteso tra ${AppPreferences.MIN_SETUP_ZOOM} e ${AppPreferences.MAX_SETUP_ZOOM}"}"""
            )
        }
        onSetupZoomChanged(ratio.toFloat())
        return okJson("""{"ok":true}""")
    }

    private fun configJson(): String {
        val url = AppPreferences.getRtmpUrl(context)
        return JSONObject().apply {
            put("rtmpUrl", url)
            put("rtmpConfigured", url.isNotEmpty())
            put("setupZoom", AppPreferences.getSetupZoomRatio(context).toDouble())
        }.toString()
    }

    private fun handleScore(session: IHTTPSession): Response {
        val body = readBody(session)
        val json = JSONObject(body)
        lastScoreJson = json.toString()
        onScoreUpdate(json)
        return okJson("""{"ok":true}""")
    }

    private fun serveDisplayPage(): Response {
        // Stesso file usato dal WebView offscreen di OverlayRenderer
        // (assets/overlay/index.html): un solo sorgente per entrambi i casi
        // d'uso (overlay video + display browser su TV/tablet).
        val html = context.assets.open("overlay/index.html").bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleManual(session: IHTTPSession): Response {
        val body = readBody(session)
        val json = JSONObject(body)
        val x = json.getDouble("x").toFloat()
        val y = json.getDouble("y").toFloat()
        onManualOverride(x, y)
        return okJson("""{"ok":true}""")
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: "{}"
    }

    private fun okJson(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun remoteControlPageHtml(): String {
        val currentUrl = AppPreferences.getRtmpUrl(context)
        val currentZoom = AppPreferences.getSetupZoomRatio(context)
        // TODO: pagina completa di regia remota (auto/manuale, start/stop);
        // per ora solo link al display e configurazione URL RTMP.
        return """
            <html><body style="font-family:sans-serif;max-width:480px;margin:24px auto;">
            <h3>BaskinCam - Controllo remoto</h3>
            <p><a href="/display">Apri il tabellone</a></p>
            <h4>URL RTMP di destinazione</h4>
            <form onsubmit="return salvaRtmp(event)">
              <input id="rtmpInput" type="text" style="width:100%;padding:8px;box-sizing:border-box;"
                     placeholder="rtmp://a.rtmp.youtube.com/live2/XXXX-XXXX-XXXX-XXXX"
                     value="${currentUrl.replace("\"", "&quot;")}">
              <button type="submit" style="margin-top:8px;padding:8px 16px;">Salva</button>
              <span id="rtmpStatus" style="margin-left:8px;"></span>
            </form>
            <h4>Zoom sensore di setup</h4>
            <p style="font-size:13px;color:#555;">Da regolare col treppiede in posizione,
            guardando l'anteprima: stringe l'inquadratura fissa fino a coprire esattamente
            il campo. Non e' lo zoom che segue l'azione.</p>
            <input id="zoomSlider" type="range" min="1.0" max="3.0" step="0.1"
                   value="$currentZoom" style="width:80%;vertical-align:middle;"
                   oninput="document.getElementById('zoomVal').textContent=this.value+'x'">
            <span id="zoomVal">${currentZoom}x</span>
            <button onclick="salvaZoom()" style="margin-left:8px;padding:6px 12px;">Applica</button>
            <span id="zoomStatus" style="margin-left:8px;"></span>
            <script>
            function salvaZoom(){
              var r = parseFloat(document.getElementById('zoomSlider').value);
              fetch('/config/zoom', {
                method:'POST', headers:{'Content-Type':'application/json'},
                body: JSON.stringify({ratio: r})
              }).then(function(x){ return x.json(); }).then(function(j){
                document.getElementById('zoomStatus').textContent = j.ok ? 'Applicato' : (j.error || 'Errore');
              }).catch(function(){
                document.getElementById('zoomStatus').textContent = 'Errore di rete';
              });
            }
            function salvaRtmp(e){
              e.preventDefault();
              var url = document.getElementById('rtmpInput').value.trim();
              fetch('/config/rtmp', {
                method:'POST', headers:{'Content-Type':'application/json'},
                body: JSON.stringify({url: url})
              }).then(function(r){ return r.json(); }).then(function(j){
                document.getElementById('rtmpStatus').textContent = j.ok ? 'Salvato' : (j.error || 'Errore');
              }).catch(function(){
                document.getElementById('rtmpStatus').textContent = 'Errore di rete';
              });
              return false;
            }
            </script>
            </body></html>
        """.trimIndent()
    }

    private fun defaultScoreJson(): String = """
        {
          "period": 1, "remainingMs": 480000, "running": false,
          "scores": [0, 0], "fouls": [0, 0],
          "timeoutsUsed": [0, 0], "bonusActive": [false, false], "possession": [false, false],
          "names": ["Squadra 1", "Squadra 2"], "colors": ["#ffffff", "#ffffff"],
          "config": {
            "periodsRegular": 4, "timeoutMode": "baskin", "timeoutsPerHalf": 2,
            "timeoutsOvertime": 1, "bonusMode": "last2", "bonus": 5,
            "manualFouls": false, "possession": false, "scoreTeamColor": false
          }
        }
    """.trimIndent()
}

