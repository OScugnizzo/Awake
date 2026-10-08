package com.example.awake

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * AwakeTileService - Quick Settings Tile per mantenere lo schermo sempre acceso.
 *
 * Su MIUI / HyperOS i tradizionali SCREEN_BRIGHT_WAKE_LOCK in background vengono soppressi dal sistema.
 * Questo approccio modifica direttamente Settings.System.SCREEN_OFF_TIMEOUT:
 * 1. Salva il timeout utente originale (es. 30 sec) nelle SharedPreferences
 * 2. Imposta il timeout al valore massimo (Int.MAX_VALUE), disattivando di fatto lo spegnimento
 * 3. Al click di spegnimento o al termine del timeout di sicurezza (30 min), ripristina il valore originale.
 */
class AwakeTileService : TileService() {

    companion object {
        private const val PREFS_NAME = "awake_prefs"
        private const val KEY_ORIGINAL_TIMEOUT = "original_timeout"
        private const val KEY_IS_ACTIVE = "is_active"

        // Massimo timeout consentito (Int.MAX_VALUE = ~24.8 giorni di schermo acceso)
        private const val MAX_TIMEOUT_VALUE = Int.MAX_VALUE

        // Timeout di sicurezza: 30 minuti di sessione prima del ripristino automatico
        private const val SAFETY_TIMEOUT_MS = 30 * 60 * 1000L

        private val handler = Handler(Looper.getMainLooper())
        private var safetyTimeoutRunnable: Runnable? = null
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        // Controlla se abbiamo il permesso di modificare le impostazioni di sistema
        if (!Settings.System.canWrite(this)) {
            promptWriteSettingsPermission()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean(KEY_IS_ACTIVE, false)

        if (isActive) {
            deactivateAwake()
        } else {
            activateAwake()
        }

        updateTileState()
    }

    private fun promptWriteSettingsPermission() {
        Toast.makeText(this, R.string.permission_required_toast, Toast.LENGTH_LONG).show()

        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ richiede PendingIntent per avviare activity da TileService
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun activateAwake() {
        try {
            val contentResolver = applicationContext.contentResolver
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Legge il timeout corrente impostato dall'utente
            val currentTimeout = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                60000 // default fallback: 1 minuto
            )

            // Se non era già salvato un timeout, salva quello corrente dell'utente
            if (currentTimeout != MAX_TIMEOUT_VALUE) {
                prefs.edit().putInt(KEY_ORIGINAL_TIMEOUT, currentTimeout).apply()
            }

            // Imposta il timeout di sistema a infinito
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                MAX_TIMEOUT_VALUE
            )

            prefs.edit().putBoolean(KEY_IS_ACTIVE, true).apply()

            // Risveglia o mantiene il display attivo
            wakeUpScreenBriefly()

            // Pianifica il timeout di sicurezza di 30 minuti
            safetyTimeoutRunnable?.let { handler.removeCallbacks(it) }
            safetyTimeoutRunnable = Runnable {
                deactivateAwake()
                updateTileState()
            }
            handler.postDelayed(safetyTimeoutRunnable!!, SAFETY_TIMEOUT_MS)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deactivateAwake() {
        try {
            safetyTimeoutRunnable?.let {
                handler.removeCallbacks(it)
                safetyTimeoutRunnable = null
            }

            val contentResolver = applicationContext.contentResolver
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Recupera il timeout originale salvato prima dell'attivazione
            val originalTimeout = prefs.getInt(KEY_ORIGINAL_TIMEOUT, 60000)

            // Ripristina il timeout originale dell'utente
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                originalTimeout
            )

            prefs.edit().putBoolean(KEY_IS_ACTIVE, false).apply()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpScreenBriefly() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Awake::KickWakeLock"
            )
            wl.acquire(1000L) // Solo 1 secondo per risvegliare il display se oscurato
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        // Verifica la consistenza: se il permesso non c'è, deve risultare inattivo
        val hasPermission = Settings.System.canWrite(this)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isActive = hasPermission && prefs.getBoolean(KEY_IS_ACTIVE, false)

        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) {
                getString(R.string.tile_state_active)
            } else {
                getString(R.string.tile_state_inactive)
            }
        }

        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
