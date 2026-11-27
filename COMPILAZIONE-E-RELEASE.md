# Compilazione, firma e pubblicazione — BaskinCam EISI

Guida a come compilare l'app in locale, a come i workflow GitHub producono
gli APK (debug e release firmato) e alle note per la futura
pubblicazione su GitHub Releases e Google Play Store.

Questo file **non è linkato nel README** ed è pensato per uso personale
dello sviluppatore.

---

## 1. Compilazione in locale

Requisiti: JDK 17 e Android SDK (`compileSdk 35`, `targetSdk 35`). Dalla
cartella del progetto:

```bash
# APK di debug (firmato con la chiave di debug di Android) — versione "-debug"
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk   (versionName es. 26.0a3-debug)

# APK release (firmato solo se imposti le variabili keystore, vedi §3)
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

Le build **debug** portano automaticamente il suffisso **`-debug`** nel
`versionName` (`versionNameSuffix "-debug"` in `app/build.gradle`), così
sono riconoscibili a colpo d'occhio rispetto alla release.

> Nota: il Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) è
> incluso nel progetto e punta a Gradle 8.7 (compatibile con AGP 8.5.2).
> Nessuna generazione manuale necessaria.

---

## 2. I due workflow GitHub

Nella cartella `.github/workflows/`, gli stessi della PWA Tabellone Baskin EISI:

| Workflow | File | Quando parte | Cosa produce |
|---|---|---|---|
| **Release** | `release.yml` | lancio manuale (*Actions → Release → Run workflow*) | tag `v<versione>` e Release, con titolo e testo dal README del ramo scelto (vedi §4); con l'interruttore **«App Android»** (attivo di default) avvia `app-android.yml` sul nuovo tag |
| **App Android** | `app-android.yml` | tag `v*` (avviato da Release) o lancio manuale | `BaskinCam-EISI-<versione>-debug.apk` sempre; `BaskinCam-EISI-<versione>.apk` firmato se ci sono i 4 segreti (§3). Sul tag li allega alla Release (pre-release per `a`/`b`/`RC`); ogni esecuzione li lascia anche come artifact `BaskinCam-EISI-<versione>-android` |

Nessuna build automatica sui push dei rami: per un APK di prova si lancia a
mano **App Android** sul ramo voluto (es. `beta`) e lo si scarica dagli
artifact.

Input del workflow **Release**: `versione` (senza `v`, es. `26.0a3`),
`ramo` (default `main`; per le alpha/beta si può indicare `beta`), `readme`,
`prefisso_tag`, `prerelease` (automatico per `aN`/`bN`/`RCN`), `bozza`,
`app_android`, `altri_workflow`. Il workflow si ferma se la versione non
rispetta la regola v3, se il tag esiste già o se non combacia con il
`versionName` di `app/build.gradle` nel ramo scelto.

> Un tag creato da un workflow con `GITHUB_TOKEN` non avvia gli altri
> workflow `on: push tags`: per questo è Release ad avviare esplicitamente
> App Android sul tag. Pushando un tag a mano (`git push origin v26.0a3`)
> App Android parte da solo, ma la Release viene creata senza il testo dal
> README.

### Flusso tipico

```bash
# 1) lavoro sul ramo beta
git checkout beta
git commit -am "nuova funzione"
git push
# (facoltativo) Actions → App Android → Run workflow sul ramo beta → APK negli artifact
```

2) release: *Actions → Release → Run workflow* con `versione` = versionName
di `app/build.gradle` (es. `26.0a3`) e `ramo` = `beta` o `main`.

---

## 3. Configurazione della firma (una tantum)

Serve solo per gli APK **release** firmati (obbligatori per il Play Store,
consigliati per le Release GitHub).

### 3.1 Genera il keystore

```bash
keytool -genkey -v \
  -keystore baskincam-release.jks \
  -keyalias baskincam \
  -keyalg RSA -keysize 2048 -validity 10000
```

Conserva `baskincam-release.jks` **fuori dal repository** (mai committarlo)
e annota password/alias in un posto sicuro: se perdi questa chiave non
potrai più aggiornare l'app sul Play Store con lo stesso identificativo.

### 3.2 Configura i segreti GitHub

Su GitHub: **Settings → Secrets and variables → Actions → New repository
secret**. Crea:

| Segreto | Valore |
|---|---|
| `KEYSTORE_BASE64` | output di `base64 -w0 baskincam-release.jks` (Linux) o `base64 baskincam-release.jks` (macOS) |
| `KEYSTORE_PASSWORD` | password dello store |
| `KEY_ALIAS` | `baskincam` (o l'alias scelto) |
| `KEY_PASSWORD` | password della chiave |

Il workflow `app-android.yml` decodifica `KEYSTORE_BASE64` in un file
temporaneo, lo usa per firmare e lo cancella subito dopo. Senza
`KEYSTORE_BASE64` produce solo l'APK debug (firma di sviluppo), come la PWA.

**Repository dell'organizzazione `baskinsoftware`** — i segreti vanno creati
nel repository `baskinsoftware/BaskinCam-EISI` (non come segreti
dell'organizzazione: ogni app ha la propria chiave di firma, da non
condividere con gli altri repository). In **Organization settings → Actions**
verifica inoltre che:
- le action di terze parti usate da `app-android.yml`
  (`softprops/action-gh-release`, `gradle/actions/setup-gradle`) siano
  consentite dalla policy dell'organizzazione (le altre sono action
  ufficiali `actions/*`);
- il token dei workflow possa avere permessi di scrittura: `release.yml`
  richiede `contents: write` (crea tag e Release) e `actions: write` (avvia
  App Android), `app-android.yml` `contents: write`.

### 3.3 Firma in locale (opzionale)

```bash
export KEYSTORE_FILE=/percorso/baskincam-release.jks
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=baskincam
export KEY_PASSWORD=...
./gradlew assembleRelease
```

---

## 4. Pubblicazione su GitHub Releases

Con il workflow **Release** (lancio manuale, §2): crea il tag e la Release,
poi App Android allega gli APK (`BaskinCam-EISI-<versione>-debug.apk` e, con
i segreti di firma, `BaskinCam-EISI-<versione>.apk`).

Titolo e testo della Release vengono dal README del ramo scelto:
- **titolo** = primo titolo `#` del README + spazio + tag (es. `BaskinCam EISI v26.0a3`);
- **testo** = README dalla prima riga non vuota dopo il titolo, fino alla fine;
- le versioni con suffisso `aN`, `bN` o `RCN` (es. `26.0a3`, `26.0b1`) sono
  sempre contrassegnate come **pre-release**.

Il README va quindi sistemato nella forma voluta **prima** di lanciare
Release.

---

## 5. Pubblicazione su Google Play Store (note per il futuro)

Il Play Store richiede alcuni passaggi in più rispetto a GitHub. Non è
ancora configurato: questa sezione è un promemoria.

### 5.1 Prerequisiti bloccanti

- **Account Google Play Console** (costo una tantum ~25 USD). Decidere prima
  se pubblicare con un account personale o di organizzazione: i requisiti di
  verifica sono diversi (da controllare sulle regole Google correnti), e
  `baskinsoftware` è un'organizzazione GitHub, non un soggetto giuridico.
- **applicationId definitivo**: `io.github.baskinsoftware.baskincameisi`. Dopo il
  primo upload su Play Console non è più modificabile.
- **Livello API**: `targetSdk` e `compileSdk` sono già a 35. Con Android 15 le
  app che puntano a 35 sono disegnate a bordo schermo (edge-to-edge): da
  verificare su un dispositivo Android 15 che i controlli in basso in
  `MainActivity` non finiscano sotto la barra di navigazione. Verificare
  anche il livello minimo richiesto da Play Console al momento della
  pubblicazione.
- **App Bundle (`.aab`), non APK**: il Play Store da tempo richiede il
  formato AAB per le nuove app. Va aggiunto un task di build:
  ```bash
  ./gradlew bundleRelease
  # output: app/build/outputs/bundle/release/app-release.aab
  ```
  (il workflow attuale produce APK; per il Play Store andrà aggiunto
  `bundleRelease` — vedi §5.4).
- **Play App Signing**: Google gestisce la chiave di firma finale; tu
  carichi con una "upload key". Il keystore del §3 diventa la upload key.

### 5.2 Ostacoli specifici di questa app da verificare PRIMA

- **Permessi sensibili**: `CAMERA`, `RECORD_AUDIO`, `FOREGROUND_SERVICE_*`.
  Il Play Store chiede una **dichiarazione d'uso** per il foreground service
  di tipo camera/microfono e per l'uso in background. Va compilata la
  sezione "App content" spiegando che l'app riprende/trasmette una partita
  e continua a schermo spento — motivazione legittima ma da dichiarare
  esplicitamente, altrimenti la review rifiuta.
- **`usesCleartextTraffic="true"`**: serve per il server HTTP locale sulla
  LAN, ma il Play Store lo segnala. Va giustificato (traffico solo verso
  rete locale, non internet in chiaro). In alternativa, restringerlo con
  una network security config che consenta cleartext solo verso IP privati.
- **Privacy policy**: obbligatoria se l'app usa camera/microfono, anche se
  non raccoglie dati. Serve una URL pubblica (può stare sul sito
  GitHub Pages dell'organizzazione, `baskinsoftware.github.io`).
- **Data safety form**: dichiarare che i dati video/audio NON vengono
  raccolti da te (vanno direttamente all'endpoint RTMP scelto dall'utente).

### 5.3 Nota su MediaPipe / dimensione

MediaPipe include librerie native `.so` per più architetture: l'AAB con
split per ABI tiene giù la dimensione scaricata. Verificare che
`app/build.gradle` non escluda ABI necessarie del telefono target.

### 5.4 Estensione futura del workflow

Per automatizzare anche il Play Store servirà:
- aggiungere `bundleRelease` al workflow,
- un service account Google Play + la action `r0adkll/upload-google-play`
  (o equivalente) con il JSON delle credenziali come segreto,
- gestione dei track (internal → closed → production).

Non è incluso ora: si aggiunge quando l'account Play Console è pronto.

---

## 6. Checklist rapida per una release

- [ ] `versionName` aggiornato in `app/build.gradle` e `versionCode` secondo la formula v3 (Gradle lo verifica: es. `26.0a3` → `260000003`)
- [ ] versione allineata anche in `README.md`, e README nella forma voluta (è il testo della Release)
- [ ] modello `efficientdet_lite0.tflite` presente in `app/src/main/assets/` (per il pan/zoom)
- [ ] `StreamEncoder.kt` verificato contro la versione RootEncoder (vedi BUILD.md)
- [ ] test rapido: *App Android → Run workflow* sul ramo, APK dagli artifact
- [ ] segreti di firma (`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`) configurati, altrimenti si ottiene solo l'APK debug
- [ ] *Release → Run workflow* con versione = `versionName` e ramo giusto → verifica che la Release abbia gli APK `BaskinCam-EISI-<versione>…`
