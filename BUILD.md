# BaskinCam - Guida alla compilazione

**Premessa importante**: questo codice è stato scritto e revisionato
manualmente per correttezza sintattica (inclusi due bug reali di
Kotlin/CameraX trovati e corretti durante lo sviluppo), ma non è stato
compilato in un vero toolchain Android — l'ambiente in cui è stato
scritto non ha SDK Android né accesso di rete ai repository Maven di
Google. La prima compilazione reale la farai tu. Il punto più a rischio
è `StreamEncoder.kt` (integrazione RootEncoder), segnalato con un
commento esplicito nel file: le firme esatte della libreria vanno
verificate contro la versione dichiarata in `app/build.gradle`.

## 1. Requisiti

- **Android Studio** (consigliato: ultima versione stabile) — include JDK,
  Gradle e SDK Manager integrati, il modo più affidabile per partire
- In alternativa: JDK 17 + Android SDK (API 34) + Gradle installati a mano

## 2. Primo apertura del progetto

1. Estrai lo zip del progetto in una cartella (es. `~/BaskinCam`)
2. Apri Android Studio → **Open** → seleziona la cartella `BaskinCam`
   (quella con `settings.gradle` dentro, non `app/`)
3. Il **Gradle Wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) è già
   incluso nel progetto (punta a Gradle 8.7, compatibile con AGP 8.5.2):
   Android Studio lo userà automaticamente, nessuna generazione manuale
   necessaria
4. Attendi la sincronizzazione Gradle (barra in basso). La prima volta
   scarica le dipendenze (CameraX, MediaPipe, RootEncoder, NanoHTTPD):
   serve una connessione internet stabile, può richiedere qualche minuto

## 3. Scarica il modello MediaPipe (obbligatorio per il pan/zoom automatico)

Il file `efficientdet_lite0.tflite` **non è incluso** (binario, non
generabile in questo scheletro).

1. Scaricalo da:
   https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/index#models
2. Copialo in `app/src/main/assets/efficientdet_lite0.tflite`

Se salti questo passo, l'app compila comunque e funziona lo stesso —
semplicemente il pan/zoom automatico resta disattivato (vedi
`assets/models/README.md`).

## 4. Verifica l'integrazione RootEncoder (punto critico)

Prima di compilare, apri `StreamEncoder.kt` e leggi il commento in cima
al file. Il codice usa `GenericStream` + `ConnectChecker` dal pattern
generale di RootEncoder, ma **le firme esatte vanno confermate** contro
la versione dichiarata in `app/build.gradle`
(`com.github.pedroSG94.RootEncoder:library:2.5.3`):

1. Apri https://github.com/pedroSG94/RootEncoder e cerca gli esempi
   ufficiali per quella versione (cartella `app` del repo, o i tag delle
   release)
2. Confronta in particolare:
   - il costruttore di `GenericStream`
   - i metodi `prepareVideo`/`prepareAudio` (nomi parametri/overload)
   - come ottenere una Surface di input personalizzata (`getGlInterface()`
     o equivalente nella versione che usi)
3. Se qualcosa non combacia, Android Studio segnalerà l'errore esatto
   riga per riga — è il posto più probabile per il primo errore di
   compilazione

## 5. Compila

**Da Android Studio**: Build → Make Project (o il martelletto in alto).
Se vuoi generare l'APK: Build → Build Bundle(s)/APK(s) → Build APK(s).

**Da riga di comando**:
```bash
./gradlew assembleDebug
```
L'APK debug compare in `app/build/outputs/apk/debug/app-debug.apk`.

## 6. Installa sul telefono

1. Sul telefono: Impostazioni → Info telefono → tocca 7 volte "Numero
   build" per attivare le Opzioni sviluppatore, poi attiva "Debug USB"
2. Collega il telefono al PC via USB, autorizza il debug quando richiesto
3. In Android Studio: Run ▶ con il telefono selezionato come target,
   oppure trasferisci manualmente `app-debug.apk` sul telefono e installalo
   (serve consentire "Installa da sorgenti sconosciute" per il file manager
   usato)

## 7. Concedi i permessi

Al primo avvio l'app chiede Camera e Microfono — necessari entrambi
(il microfono serve per l'audio dello streaming, anche se lo streaming
resta opzionale). Se neghi i permessi, l'app mostra un messaggio e resta
inutilizzabile finché non li concedi dalle Impostazioni di sistema
dell'app.

## Problemi noti da aspettarsi

- **RootEncoder**: vedi punto 4, è il rischio più concreto
- **MediaPipe**: se il delegate GPU non è supportato dal telefono usato,
  l'inizializzazione fallisce in modo controllato (nessun crash) — vedi
  log Logcat tag `BallTracker` per conferma
- **Icona app/notifica**: sono placeholder minimali (forma semplice),
  non un'icona di design definitiva — funzionano ma sono "grezze"
