# Compilazione, firma e pubblicazione — BaskinCam

Guida a come compilare l'app in locale, a come i workflow GitHub producono
gli APK (stabile firmato e beta di debug) e alle note per la futura
pubblicazione su GitHub Releases e Google Play Store.

Questo file **non è linkato nel README** ed è pensato per uso personale
dello sviluppatore.

---

## 1. Compilazione in locale

Requisiti: JDK 17 e Android SDK (`compileSdk 34`). Dalla cartella del
progetto:

```bash
# APK di debug (firmato con la chiave di debug di Android) — versione "-beta"
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk   (versionName es. 1.5-beta)

# APK release (firmato solo se imposti le variabili keystore, vedi §3)
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

Le build **debug** portano automaticamente il suffisso **`-beta`** nel
`versionName` (`versionNameSuffix "-beta"` in `app/build.gradle`), così
sono riconoscibili a colpo d'occhio rispetto alla release.

> Nota: se non hai ancora il Gradle Wrapper (`gradlew`, `gradle/wrapper/
> gradle-wrapper.jar`), generalo aprendo una volta il progetto in Android
> Studio, oppure con `gradle wrapper` se hai Gradle installato. Vedi
> BUILD.md per i dettagli.

---

## 2. I due workflow GitHub

Nella cartella `.github/workflows/`:

| Workflow | File | Quando parte | Cosa produce |
|---|---|---|---|
| **Release APK** | `release.yml` | push di un tag `v*` | APK **stabile firmato** + APK **`-beta`** di debug, allegati alla Release |
| **Beta APK (debug)** | `beta.yml` | "Run workflow" manuale, o push sul branch `beta` | APK **`-beta`** di debug come *artifact* scaricabile dal run (niente segreti) |

Idea: `beta.yml` per i test rapidi (nessuna configurazione), `release.yml`
quando pubblichi una versione vera.

### Flusso tipico a due push

```bash
# 1) lavoro quotidiano / test beta
git checkout beta
git commit -am "prova nuova feature"
git push            # -> parte beta.yml, artifact scaricabile

# 2) release vera
git checkout main
git merge beta
git tag v1.5
git push origin main --tags   # -> parte release.yml, APK allegati alla Release
```

Il tag deve combaciare con `versionName` in `app/build.gradle` (convenzione,
non obbligo tecnico): tag `v1.5` ↔ `versionName "1.5"`.

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

Il workflow `release.yml` decodifica `KEYSTORE_BASE64` in un file temporaneo,
lo usa per firmare, e lo cancella a fine job. Se i segreti non ci sono,
l'APK release viene comunque prodotto ma **non firmato** (utile per un test
del workflow, non installabile come aggiornamento firmato).

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

È già automatica: al push di un tag `v*`, `release.yml` crea la Release e vi
allega `BaskinCam-vX.Y.apk` (stabile) e `BaskinCam-vX.Y-beta.apk` (debug),
con note di rilascio generate automaticamente dai commit.

Per una release manuale senza aspettare il tag, puoi anche creare la Release
dall'interfaccia GitHub e caricare a mano un APK compilato in locale.

---

## 5. Pubblicazione su Google Play Store (note per il futuro)

Il Play Store richiede alcuni passaggi in più rispetto a GitHub. Non è
ancora configurato: questa sezione è un promemoria.

### 5.1 Prerequisiti bloccanti

- **Account Google Play Console** (costo una tantum ~25 USD).
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
  non raccoglie dati. Serve una URL pubblica (può stare su uncledan.it).
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

- [ ] `versionCode` incrementato e `versionName` aggiornato in `app/build.gradle`
- [ ] versione allineata anche in `README.md` e `fdroid/it.uncledan.baskincam.yml`
- [ ] modello `efficientdet_lite0.tflite` presente in `app/src/main/assets/` (per il pan/zoom)
- [ ] `StreamEncoder.kt` verificato contro la versione RootEncoder (vedi BUILD.md)
- [ ] test rapido con `beta.yml` prima del tag
- [ ] tag `vX.Y` che combacia col `versionName`
- [ ] push del tag → verifica che `release.yml` alleghi entrambi gli APK
