package io.github.baskinsoftware.baskincameisi

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ToggleButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * MainActivity - UI minima:
 * - preview camera (per inquadratura iniziale e override manuale via tap)
 * - toggle Auto/Manuale
 * - start/stop registrazione+streaming
 * - pulsante "schermo spento" (minimizza l'app: il Foreground Service
 *   continua a girare in background, vedi nota in stopScreenHint())
 * - campo URL RTMP
 *
 * Tutta la logica pesante (cattura, detection, encoding, overlay, server
 * locale) vive in BaskinCamService, NON qui - cosi' puo' continuare a
 * girare a schermo spento. MainActivity si lega al Service (bindService)
 * solo per ottenere l'anteprima camera e inoltrare i tap manuali.
 */
class MainActivity : AppCompatActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private var boundService: BaskinCamService? = null
    private var isBound = false

    private lateinit var previewView: PreviewView
    private lateinit var toggleAutoManual: ToggleButton
    private lateinit var btnStartStop: Button
    private lateinit var btnScreenOff: Button
    private lateinit var editRtmpUrl: EditText
    private lateinit var btnSaveRtmp: Button

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BaskinCamService.LocalBinder
            boundService = binder.getService()
            isBound = true
            boundService?.attachPreview(previewView.surfaceProvider)
            syncUiWithServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            onPermissionsGranted()
        } else {
            Toast.makeText(
                this,
                "Permessi camera/microfono necessari per funzionare",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        toggleAutoManual = findViewById(R.id.toggleAutoManual)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnScreenOff = findViewById(R.id.btnScreenOff)
        editRtmpUrl = findViewById(R.id.editRtmpUrl)
        btnSaveRtmp = findViewById(R.id.btnSaveRtmp)

        editRtmpUrl.setText(AppPreferences.getRtmpUrl(this))

        btnSaveRtmp.setOnClickListener { saveRtmpUrl() }
        btnStartStop.setOnClickListener { toggleServiceRunning() }
        btnScreenOff.setOnClickListener { hintScreenOff() }

        toggleAutoManual.setOnCheckedChangeListener { _, isManual ->
            if (!isManual) boundService?.setAutoMode()
            // Passare a manuale non richiede un target immediato: resta
            // sull'ultimo centro noto finche' l'utente non tocca il preview.
        }

        // Tap-to-target sul preview: converte le coordinate del tocco in
        // normalizzato 0..1 e le inoltra al Service, forzando la modalita'
        // manuale (coerente con "tocchi = prendi il controllo").
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val xNorm = (event.x / view.width).coerceIn(0f, 1f)
                val yNorm = (event.y / view.height).coerceIn(0f, 1f)
                boundService?.setManualTarget(xNorm, yNorm)
                toggleAutoManual.isChecked = true
                view.performClick()
            }
            true
        }

        if (hasAllPermissions()) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun onPermissionsGranted() {
        // Se il Service e' gia' in esecuzione (es. tornando da schermo
        // spento), ci si lega subito per recuperare l'anteprima.
        bindToServiceIfRunning()
    }

    private fun bindToServiceIfRunning() {
        val intent = Intent(this, BaskinCamService::class.java)
        // flag 0: si lega SOLO se il Service e' gia' in esecuzione (avviato
        // altrove con startForegroundService), non lo crea. La creazione
        // passa esclusivamente da startPipelineAndBind() con BIND_AUTO_CREATE.
        bindService(intent, serviceConnection, 0)
    }

    private fun toggleServiceRunning() {
        if (boundService?.isPipelineRunning() == true) {
            stopPipelineAndUnbind()
        } else {
            startPipelineAndBind()
        }
    }

    private fun startPipelineAndBind() {
        val intent = Intent(this, BaskinCamService::class.java).apply {
            action = BaskinCamService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        if (!isBound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        btnStartStop.setText(R.string.btn_stop)
    }

    private fun stopPipelineAndUnbind() {
        boundService?.detachPreview()
        val intent = Intent(this, BaskinCamService::class.java).apply {
            action = BaskinCamService.ACTION_STOP
        }
        startService(intent)
        unbindIfNeeded()
        btnStartStop.setText(R.string.btn_start)
    }

    /**
     * L'app NON puo' spegnere fisicamente lo schermo (nessuna API pubblica
     * lo permette senza permessi di sistema/device-admin). Questo pulsante
     * minimizza semplicemente l'app: la pipeline resta viva in background
     * grazie al Foreground Service + wake lock parziale, e l'utente puo'
     * poi premere il tasto fisico di accensione per spegnere il display
     * senza interrompere nulla. Vedi USAGE.md per il flusso completo.
     */
    private fun hintScreenOff() {
        if (boundService?.isPipelineRunning() != true) {
            Toast.makeText(this, "Avvia prima la registrazione", Toast.LENGTH_SHORT).show()
            return
        }
        boundService?.detachPreview()
        Toast.makeText(
            this,
            "Puoi spegnere lo schermo: la registrazione continua in background",
            Toast.LENGTH_LONG
        ).show()
        moveTaskToBack(true)
    }

    private fun saveRtmpUrl() {
        val url = editRtmpUrl.text.toString().trim()
        if (AppPreferences.isValidRtmpUrl(url)) {
            AppPreferences.setRtmpUrl(this, url)
            Toast.makeText(this, "URL RTMP salvato", Toast.LENGTH_SHORT).show()
            // Se la pipeline e' gia' attiva, il salvataggio qui NON riavvia
            // l'encoder automaticamente: usare la pagina di controllo remoto
            // (POST /config/rtmp) per un cambio a caldo. Qui si aggiorna
            // solo il valore letto al prossimo avvio del Service.
        } else if (url.isEmpty()) {
            AppPreferences.setRtmpUrl(this, "")
            Toast.makeText(this, "Streaming disattivato (nessun URL)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "URL non valido (atteso rtmp:// o rtmps://)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncUiWithServiceState() {
        val running = boundService?.isPipelineRunning() == true
        btnStartStop.setText(if (running) R.string.btn_stop else R.string.btn_start)
        toggleAutoManual.isChecked = boundService?.currentModeIsManual() == true
    }

    override fun onStart() {
        super.onStart()
        bindToServiceIfRunning()
    }

    override fun onStop() {
        super.onStop()
        // Si stacca solo la preview (via detachPreview quando serve davvero
        // risparmiare, es. su hintScreenOff), non il binding stesso: se
        // l'utente torna all'app rapidamente si ricollega senza rifare
        // permessi/setup. Il Service prosegue comunque in background.
        unbindIfNeeded()
    }

    private fun unbindIfNeeded() {
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                // Service gia' non legato (es. distrutto nel frattempo): ignorabile.
            }
            isBound = false
            boundService = null
        }
    }
}
