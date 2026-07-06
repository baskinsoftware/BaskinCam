# BaskinCam

Regia automatica per streaming partite di Baskin da smartphone Android
dismesso: pan/zoom digitale che segue la palla (detection cosmetica, non
statistica) e overlay tabellone punteggio fedele allo stile della PWA
[Tabellone Baskin](https://www.uncledan.it/tabellone-baskin/).

**Autore:** Daniele Lolli (UncleDan)
**Versione:** 1.5

Versioning stile Mozilla (intero sequenziale), con `versionCode` interno
progressivo (6) come richiesto dal build system Android.

## Stato del progetto

Pipeline completa e collegata end-to-end: cattura 4K, detection palla
(cosmetica), pan/zoom con override manuale via tap, overlay tabellone
fedele, streaming RTMP configurabile, server locale per controllo remoto
e display su TV/tablet, binding MainActivity↔Service per anteprima e
funzionamento a schermo spento.

Il codice è stato revisionato a mano per correttezza (inclusi due bug
reali di Kotlin/CameraX trovati e corretti - vedi commenti in
`CameraController.kt`), ma **non è stato compilato in un vero toolchain
Android** — l'ambiente di sviluppo non ha SDK Android né accesso di rete
ai repository Maven necessari. La prima compilazione reale la fa
l'utente: vedi **[BUILD.md](BUILD.md)** per la guida passo-passo, che
segnala esplicitamente l'unico punto a rischio concreto (l'integrazione
RootEncoder in `StreamEncoder.kt`, da verificare contro la versione
esatta della libreria).

Per l'uso quotidiano (sia solo-tabellone-di-rete che streaming completo),
vedi **[USAGE.md](USAGE.md)**.

Deliberatamente rimandato: la questione F-Droid/MediaPipe (vedi sezione
dedicata sotto) e una licenza definitiva (vedi `LICENSE.md`) - il progetto
oggi è una app compilabile e installabile via sideload, non ancora pronta
per la distribuzione F-Droid.

## Configurazione URL RTMP

L'URL RTMP non è più hardcoded. Due modi per impostarlo:
- **In app**: campo testo in `MainActivity`, salvato in `AppPreferences`
  (letto al prossimo avvio del `Service`)
- **Da remoto**: `POST /config/rtmp {"url": "rtmp://..."}` sulla pagina di
  controllo (`http://<ip-telefono>:8080/`) — se il `Service` è già in
  esecuzione, l'encoder viene fermato e riavviato con il nuovo URL senza
  toccare il resto della pipeline (camera/overlay continuano invariati)

Se nessun URL è configurato, `StreamEncoder` semplicemente non parte:
camera, detection, overlay e `/display` restano comunque attivi — lo
streaming resta una funzione opzionale sopra il resto (vedi architettura
sopra).

## Fedeltà del display tabellone

`assets/overlay/index.html` è un porting verificato contro il sorgente
reale di Tabellone Baskin v1.13.3 (`app.js`/`styles.css` caricati in
chat), non una ricostruzione a memoria. Fedeli 1:1: algoritmo di
generazione cifre 7 segmenti, colori (`--green`/`--red`/`--yellow`/
`--bonus-off`), soglia di luminanza (0.25), logica timer FIBA (decimi
negli ultimi 60s), `timeoutPool()`, risoluzione bonus (`last2` calcolato
localmente, `teamFouls` letto dal latch inviato dalla PWA).

Compromesso dichiarato: layout impilato verticalmente con dimensioni in
px fissi (~35% dei clamp() massimi originali) invece della griglia
orizzontale a schermo intero in vh/vw — necessario perché questo box
vive in un angolo fisso del video, non a schermo intero.

**Payload atteso da `/score`**: vedi `LocalControlServer.defaultScoreJson()`
per lo schema esatto (nomi di campo allineati a `state`/`state.config`
reali, non generici).

## Architettura

```
CameraX (4K) --> BallTracker (MediaPipe, detection a bassa risoluzione)
              --> PanZoomController (crop smoothed, auto/manuale)
              --> crop + scala 1080p
              --> OverlayRenderer (WebView, tabellone LED stile Tabellone Baskin)
              --> StreamEncoder (RootEncoder, push RTMP)
```

Tutta la pipeline gira in `BaskinCamService`, un Foreground Service che
continua a funzionare a schermo spento (wake lock parziale).

`LocalControlServer` espone un server HTTP locale (NanoHTTPD) sulla LAN per:
- ricevere il push del punteggio da Tabellone Baskin (`POST /score`)
- servire il tabellone come pagina web (`GET /display`) apribile da qualunque
  browser sulla stessa rete — TV, tablet, un altro telefono — con lo stesso
  identico rendering usato per l'overlay video (polling di `GET /status`,
  nessuna duplicazione di codice tra i due usi)
- controllo remoto auto/manuale e start/stop da altro dispositivo sulla stessa rete

`OverlayRenderer` (usato per compositare l'overlay nello streaming) carica
anch'esso `http://127.0.0.1:8080/display`: è un client come un altro del
server locale, non un caso speciale.

## Zoom sensore di setup (ibrido)

Oltre al crop digitale dinamico, è disponibile uno **zoom sensore fisso di
setup** (`POST /config/zoom`, slider nella pagina di controllo remoto):
CameraX (`setZoomRatio`) usa ottico/ibrido/digitale a seconda dell'hardware.
Serve a stringere l'inquadratura fissa fino a coprire esattamente il campo
in fase di posizionamento treppiede — così i pixel del sensore sono tutti
spesi sul campo e il crop dinamico parte da una base più densa (qualità
più "televisiva").

**Non è** lo zoom che segue l'azione: quello resta interamente crop
digitale, per un motivo architetturale preciso — il sensore deve vedere
sempre l'intero campo, altrimenti la detection perde la palla senza
possibilità di recupero (lo zoom hardware può solo ingrandire al centro,
non fare pan). Lo zoom viene riapplicato automaticamente ad ogni rebind
della camera (lo stato si azzera con `unbindAll`) e clampato al range
reale dell'hardware.

## Moduli principali

| File | Responsabilita' |
|---|---|
| `MainActivity.kt` | UI: preview camera, tap-to-target, toggle auto/manuale, start/stop, campo URL RTMP, si lega al Service via `bindService` |
| `BaskinCamService.kt` | Orchestrazione pipeline, foreground service, wake lock, `LocalBinder` per MainActivity |
| `CameraController.kt` | Sessione CameraX unica (analisi 4K + preview opzionale) |
| `BallTracker.kt` | Detection palla via MediaPipe Tasks Vision |
| `PanZoomController.kt` | Calcolo crop con smoothing, gestione auto/manuale |
| `OverlayRenderer.kt` | WebView offscreen per tabellone LED, cattura bitmap |
| `StreamEncoder.kt` | Wrapper RootEncoder per push RTMP (verificare API, vedi BUILD.md) |
| `LocalControlServer.kt` | Server HTTP locale (score push, display, controllo remoto, config RTMP) |
| `AppPreferences.kt` | Persistenza URL RTMP (SharedPreferences) |
| `assets/overlay/index.html` | Display tabellone, porting fedele da Tabellone Baskin v1.13.3 |

## Integrazione con Tabellone Baskin

La PWA deve essere modificata per inviare una `POST` verso l'IP locale del
telefono BaskinCam ad ogni variazione di stato, con lo stesso schema dello
stato interno (`scores`, `fouls`, `timeoutsUsed`, `names`, `colors`,
`period`, `remainingMs`, `running`, più i campi di config necessari per
calcolare bonus/OT lato overlay: `periods`, `bonus`, `timeoutsPerHalf`).

## Nota critica su F-Droid

MediaPipe Tasks Vision distribuisce librerie native precompilate (`.so`)
che il build server F-Droid potrebbe non riuscire a ricostruire da sorgente,
requisito centrale per l'accettazione. Vedi `fdroid/it.uncledan.baskincam.yml`
per le opzioni di mitigazione. **Da chiarire con i maintainer F-Droid prima
di investire tempo nell'integrazione detection**, se la pubblicazione su
F-Droid è un requisito non negoziabile.

RootEncoder (AGPL-3.0), NanoHTTPD (BSD-3-Clause), CameraX e Material
Components (Apache-2.0, AndroidX puro, nessuna dipendenza Google Play
Services) non presentano invece problemi di compatibilita'.

## Licenza

Vedi `LICENSE.md` - da finalizzare come AGPL-3.0-or-later o GPL-3.0-or-later
prima della pubblicazione (vincolo di compatibilita' con RootEncoder).

## Build e uso

Vedi le guide dedicate:
- **[BUILD.md](BUILD.md)** - compilazione passo-passo (Android Studio consigliato)
- **[USAGE.md](USAGE.md)** - uso quotidiano, sia solo-tabellone-di-rete che streaming completo

Due workflow GitHub in `.github/workflows/` automatizzano gli APK: `beta.yml`
(build di debug `-beta` a richiesta o su push del branch `beta`, artifact
scaricabile) e `release.yml` (APK stabile firmato + `-beta`, allegati alla
Release al push di un tag `v*`).
