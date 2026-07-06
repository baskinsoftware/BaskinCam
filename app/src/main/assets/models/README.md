# Modello EfficientDet-Lite0 per BallTracker

Questo file NON è incluso nello scheletro (binario, non generabile qui).

Scaricare `efficientdet_lite0.tflite` dal model zoo ufficiale MediaPipe:
https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/index#models

e copiarlo in `app/src/main/assets/efficientdet_lite0.tflite`
(NON dentro `assets/overlay/` - quella cartella è solo per il display HTML).

Se il file manca, `BallTracker` degrada in modo controllato: nessun
crash, semplicemente il pan/zoom automatico resta disattivato e
`PanZoomController` rimane a campo largo/centro (vedi commento in
`BallTracker.kt`).

## Nota F-Droid

Il modello `.tflite` è un blob di dati (non codice), spesso tollerato da
F-Droid con l'anti-feature "NonFreeAssets" anche quando non ricostruibile
da sorgente — diverso dal problema delle librerie native `.so` di
MediaPipe stesso, che è il punto realmente critico. Vedi
`fdroid/it.uncledan.baskincam.yml` per i dettagli.
