# BaskinCam EISI

## Cos'è il Baskin

Il Baskin è uno sport fondato sul basket, inclusivo per progettazione, che prevede per regolamento la partecipazione simultanea degli atleti senza divisione in categorie grazie ad un sistema basato sui ruoli in campo: atleti di entrambi i sessi, con e senza disabilità, con disabilità fisiche e/o cognitive giocano insieme sullo stesso campo la stessa partita. Il regolamento del Baskin è una proprieta intellettuale di *Associazione Baskin* ed i campionati in Italia sono organizzati da *EISI - Ente Italiano Sport Inclusivi*, Ente di Promozione Paralimpica riconosciuto dal *Comitato Italiano Paralimpico*.

Per approfondire: [baskin.it](https://baskin.it) · [eisi.it](https://eisi.it)

> I marchi «EISI» e «Baskin» sono registrati rispettivamente da Ente
> Italiano Sport Inclusivi e Associazione Baskin.

---

## L'app

Regia automatica per streaming partite di Baskin da smartphone Android
dismesso: pan/zoom digitale che segue la palla (detection cosmetica, non
statistica) e overlay tabellone punteggio fedele allo stile della PWA
[Tabellone Baskin EISI (PWA)](https://baskinsoftware.github.io/tabellone-baskin-eisi-pwa/).

**Autore:** Daniele Lolli (UncleDan)  
**Organizzazione:** [baskinsoftware](https://github.com/baskinsoftware) — repository [baskinsoftware/BaskinCam-EISI](https://github.com/baskinsoftware/BaskinCam-EISI)  
**Versione:** 26.0a3

Versioning secondo la regola comune ai progetti Baskin (v3): `AA.M[.P[.W]]`
con eventuale suffisso `aN`, `bN` o `RCN`. `AA` è l'anno a due cifre
(26 = 2026), `M` il numero progressivo di release dell'anno da 0, `P` la
patch (scritta solo se serve); `aN` è il numero di alpha, che prosegue la
numerazione delle versioni già rilasciate con i nomi precedenti (26.0a3 =
terza versione rilasciata, ancora alpha). Il `versionCode` Android segue la
formula `(((AA×100 + M)×100 + P)×10 + W)×100 + contatore` (contatore 01–98
per le pre-release, 99 per la finale): `26.0a3` → `260000003`, `26.0` →
`260000099`. Gradle verifica in build che versionCode e versionName siano
coerenti. Identificativo dell'app: `io.github.baskinsoftware.baskincameisi`.

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

Il progetto oggi è una app compilabile e installabile via sideload/GitHub
Releases; la pubblicazione su Google Play Store è preparata ma non ancora
attiva (vedi `COMPILAZIONE-E-RELEASE.md` §5).

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
              --> OverlayRenderer (WebView, tabellone LED stile Tabellone Baskin EISI)
              --> StreamEncoder (RootEncoder, push RTMP)
```

Tutta la pipeline gira in `BaskinCamService`, un Foreground Service che
continua a funzionare a schermo spento (wake lock parziale).

`LocalControlServer` espone un server HTTP locale (NanoHTTPD) sulla LAN per:
- ricevere il push del punteggio da Tabellone Baskin EISI (`POST /score`)
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

## Integrazione con Tabellone Baskin EISI

La PWA deve essere modificata per inviare una `POST` verso l'IP locale del
telefono BaskinCam EISI ad ogni variazione di stato, con lo stesso schema dello
stato interno (`scores`, `fouls`, `timeoutsUsed`, `names`, `colors`,
`period`, `remainingMs`, `running`, più i campi di config necessari per
calcolare bonus/OT lato overlay: `periods`, `bonus`, `timeoutsPerHalf`).

## Licenze di terze parti

RootEncoder è **AGPL-3.0**: è questa dipendenza a determinare la licenza
dell'intero progetto (vedi sezione "Licenza" sotto). NanoHTTPD è
BSD-3-Clause, CameraX e Material Components sono Apache-2.0 (AndroidX puro,
nessuna dipendenza Google Play Services): nessuna di queste presenta
problemi di compatibilità con AGPL-3.0.

## Licenza

**AGPL-3.0-or-later** — vedi il file [`LICENSE`](LICENSE) per il testo
completo. Scelta obbligata dalla dipendenza RootEncoder (AGPL-3.0): l'intero
progetto deve essere distribuito sotto una licenza compatibile, e AGPL è
anche la più aperta possibile a questo vincolo (permissiva quanto la
combinazione con RootEncoder consente, e la variante "or-later" lascia
aperta l'adozione di future versioni della licenza).

Copyright (C) 2026 Daniele Lolli (UncleDan).

## Build e uso

Vedi le guide dedicate:
- **[BUILD.md](BUILD.md)** - compilazione passo-passo (Android Studio consigliato)
- **[USAGE.md](USAGE.md)** - uso quotidiano, sia solo-tabellone-di-rete che streaming completo

Due workflow GitHub in `.github/workflows/`, gli stessi della PWA
Tabellone Baskin EISI, automatizzano le release:
- **Release** (`release.yml`, lancio manuale da *Actions → Release → Run
  workflow*): crea tag `v<versione>` e Release dal README del ramo scelto
  (pre-release per `a`, `b` e `RC`) e, con l'interruttore «App Android»,
  avvia sul nuovo tag il workflow dell'APK;
- **App Android** (`app-android.yml`, sui tag `v*` o a mano): produce
  `BaskinCam-EISI-<versione>-debug.apk` e, se ci sono i segreti di firma,
  `BaskinCam-EISI-<versione>.apk` firmato; li allega alla Release del tag e
  li lascia anche tra gli artifact dell'esecuzione.

Nessuna build automatica sui push dei rami.
Dettagli completi in **[COMPILAZIONE-E-RELEASE.md](COMPILAZIONE-E-RELEASE.md)**.
