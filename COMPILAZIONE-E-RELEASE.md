# Compilazione, firma e pubblicazione — BaskinCam

Guida a come compilare l'app in locale, a come i workflow GitHub producono
gli APK (release firmato, beta non firmato) e alle note per la futura
pubblicazione su GitHub Releases e Google Play Store.

Questo file **non è linkato nel README** ed è pensato per uso personale
dello sviluppatore.

---

## 1. Compilazione in locale

Requisiti: JDK 17 e Android SDK (`compileSdk 35`, `targetSdk 35`). Dalla
cartella del progetto:

```bash
# APK di debug (firmato con la chiave di debug di Android) — versione "-beta"
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk   (versionName es. 26.0a3-beta)

# APK release (firmato solo se imposti le variabili keystore, vedi §3)
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

Le build **debug** portano automaticamente il suffisso **`-beta`** nel
`versionName` (`versionNameSuffix "-beta"` in `app/build.gradle`), così
sono riconoscibili a colpo d'occhio rispetto alla release.

> Nota: il Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) è
> incluso nel progetto e punta a Gradle 8.7 (compatibile con AGP 8.5.2).
> Nessuna generazione manuale necessaria.

---

## 2. I due workflow GitHub

Nella cartella `.github/workflows/`:

| Workflow | File | Quando parte | Cosa produce |
|---|---|---|---|
| **Release APK** | `release.yml` | push di un tag `v*` | **un solo** APK, firmato: `BaskinCam-<tag>-signed.apk`, allegato alla Release (titolo e testo presi dal README, vedi §4). Fallisce subito se i 4 segreti di firma non sono configurati (vedi §3) |
| **Beta APK (debug)** | `beta.yml` | ogni push sul branch `beta` (o "Run workflow" manuale) | `BaskinCam-beta-<short-sha>-unsigned.apk` come *artifact* del run, confezionato automaticamente in `BaskinCam-beta-<short-sha>-unsigned.zip` (niente segreti) |

Idea: `beta.yml` per i test rapidi (nessuna configurazione), `release.yml`
quando pubblichi una versione vera — e a differenza di prima **richiede**
i segreti di firma, perché il nome del file dichiara esplicitamente che è
firmato.

### Flusso tipico a due push

```bash
# 1) lavoro quotidiano / test beta
git checkout beta
git commit -am "prova nuova feature"
git push            # -> parte beta.yml, artifact scaricabile

# 2) release vera
git checkout main
git merge beta
git tag v26.0a3
git push origin main --tags   # -> parte release.yml, APK allegato alla Release
```

Il tag deve combaciare con `versionName` in `app/build.gradle` (convenzione,
non obbligo tecnico): tag `v26.0a3` ↔ `versionName "26.0a3"`.

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
lo usa per firmare, e lo cancella a fine job. Se anche uno solo dei 4 segreti
manca, il job **fallisce subito** con un errore esplicito, invece di produrre
un APK non firmato con un nome (`-signed`) che dichiarerebbe il contrario.

**Repository dell'organizzazione `baskinsoftware`** — i segreti vanno creati
nel repository `baskinsoftware/BaskinCam` (non come segreti
dell'organizzazione: ogni app ha la propria chiave di firma, da non
condividere con gli altri repository). In **Organization settings → Actions**
verifica inoltre che:
- le action di terze parti usate dai workflow (`android-actions/setup-android`
  e `softprops/action-gh-release`) siano consentite dalla policy dell'organizzazione;
- il token dei workflow possa avere permessi di scrittura: `release.yml`
  richiede `contents: write` per creare la Release.

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
allega **esattamente** `BaskinCam-<tag>-signed.apk` (es. tag `v26.0a3` →
`BaskinCam-v26.0a3-signed.apk`). Se i segreti di firma non sono configurati,
il workflow fallisce subito invece di produrre un APK non firmato con un nome
che dichiara il contrario.

Titolo e testo della Release vengono dal README:
- **titolo** = primo titolo `#` del README + spazio + tag (es. `BaskinCam v26.0a3`);
- **testo** = README a partire dalla prima riga non vuota dopo il titolo (i
  link relativi ai file del repository diventano link assoluti al tag);
- i tag con suffisso alpha o beta (es. `v26.0a3`, `v26.0b1`) sono
  contrassegnati come **pre-release**.

Per una release manuale senza aspettare il tag, puoi anche creare la Release
dall'interfaccia GitHub e caricare a mano un APK compilato in locale.

---

## 5. Pubblicazione su Google Play Store (note per il futuro)

Il Play Store richiede alcuni passaggi in più rispetto a GitHub. Non è
ancora configurato: questa sezione è un promemoria.

### 5.1 Prerequisiti bloccanti

- **Account Google Play Console** (costo una tantum ~25 USD). Decidere prima
  se pubblicare con un account personale o di organizzazione: i requisiti di
  verifica sono diversi (da controllare sulle regole Google correnti), e
  `baskinsoftware` è un'organizzazione GitHub, non un soggetto giuridico.
- **applicationId definitivo**: `io.github.baskinsoftware.baskincam`. Dopo il
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

- [ ] `versionCode` incrementato e `versionName` aggiornato in `app/build.gradle`
- [ ] versione allineata anche in `README.md`
- [ ] modello `efficientdet_lite0.tflite` presente in `app/src/main/assets/` (per il pan/zoom)
- [ ] `StreamEncoder.kt` verificato contro la versione RootEncoder (vedi BUILD.md)
- [ ] test rapido con `beta.yml` prima del tag
- [ ] tag `v<versionName>` (es. `v26.0a3`) che combacia col `versionName`
- [ ] segreti di firma (`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`) configurati, altrimenti `release.yml` fallisce subito
- [ ] push del tag → verifica che `release.yml` alleghi `BaskinCam-<tag>-signed.apk`
