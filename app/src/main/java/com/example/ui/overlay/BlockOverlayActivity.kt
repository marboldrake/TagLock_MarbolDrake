package com.example.ui.overlay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.R
import com.example.data.LockMode
import com.example.data.MockAppBlockerController
import com.example.data.NfcSecurityManager
import com.example.databinding.ActivityBlockOverlayBinding
import com.example.notification.TagLockNotificationManager
import com.example.util.NfcTagHelper
import java.util.Locale

/**
 * Pantalla Overlay de Bloqueo de Aplicaciones de TagLock.
 * Muestra:
 * 1. La aplicación que intentó abrirse.
 * 2. Temporizador circular con el tiempo restante de enfoque (MM:SS).
 * 3. Botón de emergencia de 30 segundos continuos para desbloquear si es urgente.
 * 4. Intercepción de tags NFC para desbloqueo inmediato.
 */
class BlockOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockOverlayBinding
    private lateinit var controller: MockAppBlockerController
    private lateinit var nfcSecurityManager: NfcSecurityManager

    private var blockedPackageName: String? = null

    // Temporizador circular del tiempo de enfoque
    private var sessionCountdownTimer: CountDownTimer? = null

    // Temporizador para el botón de emergencia de 30 segundos
    private var emergencyTimer: CountDownTimer? = null
    private var isHoldingEmergency: Boolean = false

    // NFC Foreground Dispatch
    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null
    private var nfcIntentFilters: Array<IntentFilter>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityBlockOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.overlayRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        controller = MockAppBlockerController.getInstance(this)
        nfcSecurityManager = NfcSecurityManager.getInstance(this)

        blockedPackageName = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)

        setupBlockedAppDetails()
        setupLockModeUi()
        setupAdd5MinButton()
        setupEmergencyButton()
        setupHomeButton()
        initNfcAdapter()
        handleNfcIntent(intent)

        // Evitar que el botón atrás regrese a la app bloqueada; redirigir al Home de Android
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitToHomeScreen()
            }
        })
    }

    /**
     * Ajusta la interfaz del overlay según el modo de bloqueo seleccionado:
     * - TIMER: Muestra temporizador circular decreciente, opción de +5 min y botón de emergencia.
     * - NFC_ONLY: Sin tiempo. Oculta temporizador y +5 min; resalta el uso de tarjeta NFC.
     * - EMERGENCY_ONLY: Sin tiempo. Oculta temporizador, +5 min y pista NFC; resalta el botón de emergencia de 30s.
     */
    private fun setupLockModeUi() {
        val mode = controller.getLockMode()
        when (mode) {
            LockMode.TIMER -> {
                binding.tvOverlayModeBadge.setText(R.string.overlay_mode_badge_timer)
                binding.layoutCircularTimer.visibility = View.VISIBLE
                binding.layoutAddTimeSection.visibility = View.VISIBLE
                binding.tvOverlaySessionDesc.setText(R.string.overlay_session_active_desc)
                binding.layoutOverlayNfcHint.visibility = View.VISIBLE
                binding.cardOverlayEmergency.visibility = View.VISIBLE
                setupCircularCountdown()
            }
            LockMode.NFC_ONLY -> {
                binding.tvOverlayModeBadge.setText(R.string.overlay_mode_badge_nfc_only)
                binding.layoutCircularTimer.visibility = View.GONE
                binding.layoutAddTimeSection.visibility = View.GONE
                binding.tvOverlaySessionDesc.setText(R.string.overlay_nfc_only_instruction)
                binding.layoutOverlayNfcHint.visibility = View.VISIBLE
                binding.cardOverlayEmergency.visibility = View.VISIBLE
            }
            LockMode.EMERGENCY_ONLY -> {
                binding.tvOverlayModeBadge.setText(R.string.overlay_mode_badge_emergency_only)
                binding.layoutCircularTimer.visibility = View.GONE
                binding.layoutAddTimeSection.visibility = View.GONE
                binding.tvOverlaySessionDesc.setText(R.string.overlay_emergency_only_instruction)
                binding.layoutOverlayNfcHint.visibility = View.GONE
                binding.cardOverlayEmergency.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Si el bloqueo ya no está activo, cerrar inmediatamente el overlay
        if (!controller.isLocked()) {
            finish()
            return
        }
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionCountdownTimer?.cancel()
        emergencyTimer?.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    /**
     * Muestra el icono y nombre legible de la app interceptada.
     */
    private fun setupBlockedAppDetails() {
        val pkg = blockedPackageName
        if (!pkg.isNullOrBlank()) {
            try {
                val pm = packageManager
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                binding.tvBlockedAppName.text = pm.getApplicationLabel(appInfo)
                binding.ivBlockedAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
                binding.ivBlockedAppIcon.clearColorFilter()
            } catch (e: Exception) {
                binding.tvBlockedAppName.text = pkg
            }
        } else {
            binding.tvBlockedAppName.setText(R.string.overlay_default_app_name)
        }
    }

    /**
     * Inicia y sincroniza el temporizador circular con el tiempo restante de la sesión de enfoque.
     */
    private fun setupCircularCountdown() {
        sessionCountdownTimer?.cancel()

        val remainingMs = controller.getSessionRemainingMillis()
        val totalMs = controller.getSessionDurationMillis().coerceAtLeast(1L)

        if (remainingMs <= 0) {
            binding.circularTimerProgress.progress = 0
            binding.tvRemainingTime.text = "00:00"
            controller.unlock()
            finish()
            return
        }

        updateCircularUi(remainingMs, totalMs)

        sessionCountdownTimer = object : CountDownTimer(remainingMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                updateCircularUi(millisUntilFinished, totalMs)
                TagLockNotificationManager.getInstance(this@BlockOverlayActivity)
                    .updateRemainingTimeNotification(millisUntilFinished)
            }

            override fun onFinish() {
                binding.circularTimerProgress.progress = 0
                binding.tvRemainingTime.text = "00:00"
                controller.unlock()
                TagLockNotificationManager.getInstance(this@BlockOverlayActivity)
                    .showLockDeactivatedNotification()
                Toast.makeText(this@BlockOverlayActivity, "¡Sesión de enfoque completada! Aplicaciones liberadas.", Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    /**
     * Permite aumentar el tiempo de enfoque en intervalos de +5 minutos (nunca bajar).
     */
    private fun setupAdd5MinButton() {
        binding.btnOverlayAdd5Min.setOnClickListener {
            if (controller.isLocked()) {
                val extended = controller.extendSessionMinutes(5)
                if (extended) {
                    // Reiniciar el temporizador con el nuevo tiempo extendido
                    setupCircularCountdown()
                    TagLockNotificationManager.getInstance(this)
                        .updateRemainingTimeNotification(controller.getSessionRemainingMillis())
                    Toast.makeText(this, getString(R.string.notification_extended_toast), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateCircularUi(remainingMs: Long, totalMs: Long) {
        val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        binding.tvRemainingTime.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        val progressRatio = (remainingMs.toDouble() / totalMs.toDouble()).coerceIn(0.0, 1.0)
        binding.circularTimerProgress.progress = (progressRatio * 1000).toInt()
    }

    /**
     * Lógica idéntica del botón de desbloqueo de emergencia de 30 segundos continuos.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupEmergencyButton() {
        resetEmergencyProgress()

        binding.btnOverlayEmergency.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startEmergencyHold()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelEmergencyHold()
                    true
                }
                else -> false
            }
        }
    }

    private fun startEmergencyHold() {
        isHoldingEmergency = true
        binding.btnOverlayEmergency.setBackgroundColor(ContextCompat.getColor(this, R.color.pastel_red_dark))
        binding.btnOverlayEmergency.text = getString(R.string.overlay_emergency_holding_btn, 1)

        emergencyTimer?.cancel()
        val durationMs = 30_000L
        val intervalMs = 50L

        emergencyTimer = object : CountDownTimer(durationMs, intervalMs) {
            override fun onTick(millisUntilFinished: Long) {
                if (!isHoldingEmergency) {
                    cancel()
                    return
                }
                val elapsedMs = durationMs - millisUntilFinished
                val progress = ((elapsedMs.toFloat() / durationMs.toFloat()) * 1000).toInt()
                binding.overlayEmergencyProgressBar.progress = progress

                val secondsElapsed = (elapsedMs / 1000).toInt() + 1
                binding.btnOverlayEmergency.text =
                    getString(R.string.overlay_emergency_holding_btn, secondsElapsed.coerceAtMost(30))
            }

            override fun onFinish() {
                if (isHoldingEmergency) {
                    binding.overlayEmergencyProgressBar.progress = 1000
                    onEmergencySuccess()
                }
            }
        }.start()
    }

    private fun cancelEmergencyHold() {
        if (!isHoldingEmergency) return
        isHoldingEmergency = false
        emergencyTimer?.cancel()
        resetEmergencyProgress()
        Toast.makeText(this, getString(R.string.emergency_cancelled_toast), Toast.LENGTH_SHORT).show()
    }

    private fun resetEmergencyProgress() {
        binding.overlayEmergencyProgressBar.progress = 0
        binding.btnOverlayEmergency.setBackgroundColor(ContextCompat.getColor(this, R.color.pastel_red_primary))
        binding.btnOverlayEmergency.setText(R.string.overlay_emergency_hold_btn)
    }

    private fun onEmergencySuccess() {
        controller.unlock()
        TagLockNotificationManager.getInstance(this).showLockDeactivatedNotification()
        Toast.makeText(this, getString(R.string.overlay_emergency_unlocked_toast), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun setupHomeButton() {
        binding.btnExitToHome.setOnClickListener {
            exitToHomeScreen()
        }
    }

    private fun exitToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    // --- Manejo de NFC en la pantalla de Overlay ---
    private fun initNfcAdapter() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try { addDataType("*/*") } catch (_: Exception) {}
        }
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        nfcIntentFilters = arrayOf(ndefFilter, techFilter, tagFilter)
    }

    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val pendingIntent = nfcPendingIntent ?: return
        if (adapter.isEnabled) {
            try {
                adapter.enableForegroundDispatch(this, pendingIntent, nfcIntentFilters, null)
            } catch (_: Exception) {}
        }
    }

    private fun disableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        try {
            adapter.disableForegroundDispatch(this)
        } catch (_: Exception) {}
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED) {

            val tag: Tag = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }) ?: return

            val tagId = NfcTagHelper.getTagUid(tag)
            val secret = NfcTagHelper.readSecretFromTag(tag)

            // Si el modo configurado es exclusivamente Solo Botón de Emergencia, informar que se requiere el botón
            if (controller.getLockMode() == LockMode.EMERGENCY_ONLY) {
                Toast.makeText(this, getString(R.string.overlay_emergency_only_instruction), Toast.LENGTH_LONG).show()
                return
            }

            if (nfcSecurityManager.hasPairedTag()) {
                if (!nfcSecurityManager.isTagAuthorized(tagId, secret)) {
                    Toast.makeText(this, getString(R.string.nfc_tag_unauthorized_toast), Toast.LENGTH_LONG).show()
                    return
                }
            }

            controller.unlock()
            TagLockNotificationManager.getInstance(this).showLockDeactivatedNotification()
            Toast.makeText(this, getString(R.string.nfc_hardware_tag_unlocked), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"

        fun start(context: Context, packageName: String) {
            val intent = Intent(context, BlockOverlayActivity::class.java).apply {
                putExtra(EXTRA_BLOCKED_PACKAGE, packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }
    }
}
