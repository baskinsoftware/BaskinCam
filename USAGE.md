# BaskinCam EISI - Guida all'uso

BaskinCam EISI si usa in due modalità indipendenti, attivabili anche insieme:

- **Solo tabellone di rete**: nessuno streaming video, solo il punteggio
  di Tabellone Baskin visualizzato su TV/tablet in palestra
- **Streaming completo**: video con pan/zoom automatico sulla palla e
  overlay tabellone, trasmesso in diretta (YouTube Live, Facebook Live, ecc.)

In entrambi i casi il telefono con BaskinCam EISI e il dispositivo che gestisce
Tabellone Baskin devono stare **sulla stessa rete Wi-Fi locale**.

---

## Modalità 1 - Solo tabellone di rete (senza streaming)

Utile per proiettare il punteggio su una TV in palestra, senza bisogno di
riprendere la partita.

### Passi

1. **Apri BaskinCam EISI** sul telefono dedicato, concedi i permessi richiesti
2. **Non serve configurare l'URL RTMP** — lascia il campo vuoto
3. Tocca **Avvia**: la pipeline parte (camera, overlay, server locale),
   anche se lo streaming resta spento per mancanza di URL
4. Trova l'indirizzo IP del telefono sulla rete Wi-Fi (Impostazioni →
   Wi-Fi → tocca la rete connessa → dettagli, oppure impostazioni del
   router)
5. Su qualunque browser di TV/tablet/altro telefono sulla stessa rete,
   apri:
   ```
   http://<ip-del-telefono>:8080/display
   ```
   Compare il tabellone a schermo intero, fedele allo stile della PWA,
   aggiornato in tempo reale
6. **Collega Tabellone Baskin**: nelle Impostazioni della PWA, sezione
   "Streaming BaskinCam", attiva l'invio e inserisci `<ip-del-telefono>:8080`
   come destinazione. Da quel momento ogni variazione di punteggio,
   falli, timeout, periodo si riflette automaticamente su tutti i
   display collegati

### Note pratiche

- Puoi aprire `/display` su **più dispositivi contemporaneamente** (TV
  principale + tablet di controllo, ad esempio) — nessun limite
- Se la Wi-Fi ha un problema momentaneo, i display mostrano l'ultimo
  stato noto invece di azzerarsi, e si riallineano da soli appena la
  rete torna
- Per fermare tutto: torna nell'app BaskinCam EISI e tocca **Ferma**, oppure
  da un browser vai su `http://<ip-del-telefono>:8080/` e usa il link
  di controllo remoto

---

## Modalità 2 - Streaming completo (video + overlay)

### Preparazione

1. **Posiziona il telefono** su un supporto/treppiede fisso, inquadratura
   grandangolare che copra l'intero campo da Baskin (il pan/zoom digitale
   ritaglia una porzione di questa inquadratura, non muove fisicamente
   la camera)
2. **Regola lo zoom sensore di setup** (opzionale ma consigliato): dalla
   pagina di controllo remoto (`http://<ip-del-telefono>:8080/`), usa lo
   slider "Zoom sensore di setup" guardando l'anteprima sul telefono, e
   stringi l'inquadratura fino a coprire *esattamente* il campo —
   eliminando spalti/soffitto inutili, i pixel del sensore sono tutti
   spesi sul campo e lo zoom dinamico successivo pixela meno. **Regola:
   tutto il campo deve restare visibile**, altrimenti la detection perde
   la palla senza possibilità di recupero. Il valore resta memorizzato
   tra una sessione e l'altra
3. **Ottieni l'URL RTMP** dal servizio di destinazione:
   - YouTube Live: Studio YouTube → Crea → Trasmetti in diretta → copia
     "URL server" + "Chiave stream" (concatenali:
     `rtmp://a.rtmp.youtube.com/live2/LA-TUA-CHIAVE`)
   - Facebook Live: Creator Studio → Strumenti live → analogamente

### Configurazione

1. Apri BaskinCam EISI, incolla l'URL RTMP completo nel campo dedicato, tocca
   **Salva**
2. Tocca **Avvia**: parte cattura, detection, pan/zoom automatico,
   overlay e streaming verso l'URL configurato
3. Verifica sulla piattaforma di destinazione (YouTube Studio, ecc.) che
   il segnale sia in arrivo — può richiedere 10-20 secondi

### Durante la partita

- **Pan/zoom automatico**: segue la palla da solo. Se sbaglia inquadratura
  (palla persa, azione confusa), **tocca il punto giusto sullo schermo
  dell'anteprima**: passa in automatico a modalità Manuale e centra lì
- **Tornare all'automatico**: tocca il pulsante Auto/Manuale per
  disattivare l'override e lasciare che l'IA riprenda il controllo
- **Spegnere lo schermo per risparmiare batteria**: tocca **Spegni
  schermo**, poi premi il tasto fisico di accensione del telefono. La
  registrazione/streaming **continuano in background** grazie al
  Foreground Service — non serve tenere lo schermo acceso durante tutta
  la partita
- **Riprendere il controllo**: riaccendi lo schermo e riapri l'app,
  BaskinCam EISI si riaggancia automaticamente alla pipeline già in corso

### Cambiare URL RTMP a partita in corso

Da un altro dispositivo sulla stessa rete, apri
`http://<ip-del-telefono>:8080/` e usa il form "URL RTMP di destinazione":
il salvataggio da qui **riavvia l'encoder a caldo** con il nuovo URL,
senza interrompere camera/overlay (utile se devi cambiare piattaforma di
destinazione senza fermare tutto).

### Il tabellone su TV funziona anche qui

Le due modalità non si escludono: mentre trasmetti in streaming, puoi
**anche** aprire `/display` su una TV a bordocampo per chi è in palestra
dal vivo — stesso meccanismo della Modalità 1, gratis in aggiunta.

### Fine partita

Tocca **Ferma** nell'app (o `POST /control/stop` da remoto): si ferma
tutto — camera, streaming, server locale, wake lock rilasciato.

---

## Risoluzione problemi comuni

| Sintomo | Causa probabile |
|---|---|
| `/display` non si apre da altro dispositivo | Telefono e dispositivo non sulla stessa Wi-Fi, o IP cambiato (i router spesso riassegnano IP) |
| Punteggio non si aggiorna su `/display` | Verifica che "Streaming BaskinCam" sia attivo nelle Impostazioni della PWA con l'IP corretto |
| Pan/zoom non si muove mai | Il modello MediaPipe potrebbe mancare (vedi BUILD.md punto 3) — il video funziona comunque, resta fisso al centro |
| Streaming non parte | Verifica l'URL RTMP (deve iniziare con `rtmp://` o `rtmps://`) e che la chiave stream sia ancora valida sulla piattaforma di destinazione |
| App si ferma quando spengo lo schermo | Verifica che i permessi Batteria per l'app siano su "Nessuna restrizione" (alcuni produttori Android limitano aggressivamente le app in background) |
