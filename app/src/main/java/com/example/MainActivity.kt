package com.example

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.data.AppPreferences
import com.example.data.LockMode
import com.example.data.MockAppBlockerController
import com.example.data.NfcSecurityManager
import com.example.databinding.ActivityMainBinding
import com.example.databinding.ItemPairedNfcCardBinding
import com.example.notification.TagLockNotificationManager
import com.example.service.AppBlockerAccessibilityService
import com.example.ui.easteregg.EasterEggActivity
import com.example.ui.overlay.BlockOverlayActivity
import com.example.ui.setup.InitialSetupActivity
import com.example.util.NfcTagHelper
import com.example.util.PermissionHelper
import java.util.Locale

/**
 * Pantalla Principal de FocusBlock (Soporte NFC Físico + Simulación + Botón de Emergencia de 30s).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: MockAppBlockerController
    private lateinit var nfcSecurityManager: NfcSecurityManager

    // Adaptador NFC de hardware y PendingIntent para Foreground Dispatch
    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null
    private var nfcIntentFilters: Array<IntentFilter>? = null

    // Modo de grabación de Tag NFC exclusivo
    private var isProgrammingTagMode: Boolean = false

    // Temporizador para el botón de emergencia (30 segundos = 30,000 ms)
    private var emergencyTimer: CountDownTimer? = null
    private var isHoldingEmergency: Boolean = false
    private var emergencyElapsedMs: Long = 0L

    // Rastreo de toques al título de la app para desbloquear el Easter Egg
    private var appTitleTapCount = 0
    private var lastAppTitleTapTime = 0L

    private val lockListener: (Boolean) -> Unit = { isLocked ->
        runOnUiThread {
            updateStatusUi(isLocked)
            val notifManager = TagLockNotificationManager.getInstance(this)
            if (isLocked) {
                notifManager.showLockActivatedNotification()
                notifManager.updateRemainingTimeNotification(controller.getSessionRemainingMillis())
            } else {
                notifManager.showLockDeactivatedNotification()
            }
        }
    }

    private val blockedPackagesListener: (Set<String>) -> Unit = { packages ->
        runOnUiThread {
            updateBlockedPackagesSummary(packages)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        controller = MockAppBlockerController.getInstance(this)
        nfcSecurityManager = NfcSecurityManager.getInstance(this)

        val appPreferences = AppPreferences.getInstance(this)
        appPreferences.applySavedTheme()
        appPreferences.applySavedLanguage()

        // Si es el primer inicio y no ha completado el asistente de bienvenida/setup, abrirlo
        if (!appPreferences.isSetupCompleted()) {
            InitialSetupActivity.start(this)
        }

        initNfcAdapter()
        setupButtons()
        setupNfcKeyManagement()
        setupAccessibilityCard()
        setupEmergencyButton()

        controller.addLockStateListener(lockListener)
        controller.addBlockedPackagesListener(blockedPackagesListener)

        // Manejar si la actividad fue lanzada o despertada directamente por un Intent NFC
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateBlockedPackagesSummary(controller.getBlockedPackages())
        updateStatusUi(controller.isLocked())
        updateNfcHardwareUi()
        updateNfcKeyManagementUi()
        updateAccessibilityCardUi()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.removeLockStateListener(lockListener)
        controller.removeBlockedPackagesListener(blockedPackagesListener)
        emergencyTimer?.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    /**
     * Inicialización del adaptador NFC del dispositivo.
     */
    private fun initNfcAdapter() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // PendingIntent reutilizable para Foreground Dispatch en tiempo de ejecución
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType("*/*")
            } catch (e: IntentFilter.MalformedMimeTypeException) {
                // Tipo MIME por defecto
            }
        }
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        nfcIntentFilters = arrayOf(ndefFilter, techFilter, tagFilter)

        binding.btnEnableNfcSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    /**
     * Habilita la captura prioritaria de Tags NFC mientras la app está abierta en pantalla.
     */
    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val pendingIntent = nfcPendingIntent ?: return
        if (adapter.isEnabled) {
            try {
                adapter.enableForegroundDispatch(this, pendingIntent, nfcIntentFilters, null)
            } catch (e: Exception) {
                // Control preventivo si la actividad no está en primer plano
            }
        }
    }

    /**
     * Deshabilita la captura prioritaria de Tags al pausar la pantalla.
     */
    private fun disableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        try {
            adapter.disableForegroundDispatch(this)
        } catch (e: Exception) {
            // Ignorado en pausa
        }
    }

    /**
     * Actualiza la tarjeta visual del estado del hardware NFC en el dispositivo.
     */
    private fun updateNfcHardwareUi() {
        val adapter = nfcAdapter
        when {
            adapter == null -> {
                binding.tvNfcHardwareStatus.setText(R.string.nfc_status_not_supported)
                binding.tvNfcHardwareStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvNfcHardwareDetail.text = "Este dispositivo o emulador no cuenta con sensor NFC físico. Puedes usar el botón de simulación abajo."
                binding.ivNfcHardwareIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
                binding.btnEnableNfcSettings.visibility = View.GONE
            }
            !adapter.isEnabled -> {
                binding.tvNfcHardwareStatus.setText(R.string.nfc_status_disabled)
                binding.tvNfcHardwareStatus.setTextColor(ContextCompat.getColor(this, R.color.pastel_red_primary))
                binding.tvNfcHardwareDetail.text = "El sensor NFC está apagado en este teléfono. Toca 'Activar' para encenderlo."
                binding.ivNfcHardwareIcon.setColorFilter(ContextCompat.getColor(this, R.color.pastel_red_primary))
                binding.btnEnableNfcSettings.visibility = View.VISIBLE
            }
            else -> {
                binding.tvNfcHardwareStatus.setText(R.string.nfc_status_ready)
                binding.tvNfcHardwareStatus.setTextColor(ContextCompat.getColor(this, R.color.pastel_green_primary))
                binding.tvNfcHardwareDetail.setText(R.string.nfc_listening_banner)
                binding.ivNfcHardwareIcon.setColorFilter(ContextCompat.getColor(this, R.color.pastel_green_primary))
                binding.btnEnableNfcSettings.visibility = View.GONE
            }
        }
    }

    /**
     * Procesa los intents de tags NFC recibidos (tanto al lanzar la app como en onNewIntent).
     */
    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED) {

            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            val tagIdBytes = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID) ?: tag?.id
            val tagIdHex = tagIdBytes?.joinToString(separator = ":") { "%02X".format(it) } ?: "DESCONOCIDO"

            if (tag != null) {
                processNfcTag(tag, tagIdHex)
            }
        }
    }

    /**
     * Procesa la tarjeta NFC detectada según el modo actual:
     * - Modo Grabación: Escribe la clave secreta y el AAR (package id) y la vincula como llave única.
     * - Modo Normal: Verifica si el tag está autorizado y alterna el estado de bloqueo.
     */
    private fun processNfcTag(tag: Tag, tagId: String) {
        if (isProgrammingTagMode) {
            writeKeyToTag(tag, tagId)
            return
        }

        // Modo Normal: Verificar autorización
        val secretFromTag = NfcTagHelper.readSecretFromTag(tag)
        val hasPaired = nfcSecurityManager.hasPairedTag()

        if (hasPaired) {
            val authorized = nfcSecurityManager.isTagAuthorized(tagId, secretFromTag)
            if (!authorized) {
                binding.tvLastScannedTag.visibility = View.VISIBLE
                binding.tvLastScannedTag.text = "✗ Tag no autorizado: $tagId"
                binding.tvLastScannedTag.setTextColor(ContextCompat.getColor(this, R.color.pastel_red_primary))
                Toast.makeText(this, getString(R.string.nfc_tag_unauthorized_toast), Toast.LENGTH_LONG).show()
                return
            }
        }

        onPhysicalTagScanned(tagId)
    }

    /**
     * Escribe la clave secreta y el ID del paquete en el tag físico y lo vincula a la lista de tarjetas autorizadas.
     */
    private fun writeKeyToTag(tag: Tag, tagId: String) {
        val secretKey = nfcSecurityManager.getSecretKey()
        val packageName = applicationContext.packageName

        val result = NfcTagHelper.writeTagWithSecretAndAar(tag, secretKey, packageName)
        if (result.isSuccess) {
            val pairedCard = nfcSecurityManager.addPairedCard(tagId)
            isProgrammingTagMode = false
            updateNfcKeyManagementUi()

            binding.tvLastScannedTag.visibility = View.VISIBLE
            binding.tvLastScannedTag.text = "✓ Tarjeta vinculada con éxito: ${pairedCard.name} (UID: $tagId)"
            binding.tvLastScannedTag.setTextColor(ContextCompat.getColor(this, R.color.pastel_green_primary))

            AlertDialog.Builder(this)
                .setTitle("¡Tarjeta NFC Vinculada con Éxito!")
                .setMessage("Se ha grabado en la tarjeta:\n\n1. Clave Secreta: $secretKey\n2. Android Application Record: $packageName\n\nUID Vinculado: $tagId\nNombre: ${pairedCard.name}\n\nPuedes vincular tarjetas adicionales en cualquier momento.")
                .setPositiveButton("Entendido", null)
                .show()

            Toast.makeText(this, getString(R.string.nfc_card_added_toast, pairedCard.name), Toast.LENGTH_LONG).show()
        } else {
            val errorMsg = result.exceptionOrNull()?.message ?: "Desconocido"
            Toast.makeText(this, getString(R.string.nfc_program_error_toast, errorMsg), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Acción ejecutada al validar exitosamente una tarjeta/tag NFC autorizada.
     */
    private fun onPhysicalTagScanned(tagId: String) {
        val currentlyLocked = controller.isLocked()

        binding.tvLastScannedTag.visibility = View.VISIBLE
        binding.tvLastScannedTag.text = "✓ Tarjeta autorizada detectada (UID: $tagId)"
        binding.tvLastScannedTag.setTextColor(ContextCompat.getColor(this, R.color.pastel_green_primary))

        if (currentlyLocked) {
            controller.unlock()
            Toast.makeText(this, getString(R.string.nfc_hardware_tag_unlocked), Toast.LENGTH_LONG).show()
        } else {
            // Verificar que haya al menos una aplicación seleccionada para bloquear
            if (controller.getBlockedPackages().isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.error_no_apps_selected_dialog_title)
                    .setMessage(R.string.error_no_apps_selected_dialog_msg)
                    .setPositiveButton(R.string.btn_select_apps) { _, _ ->
                        startActivity(Intent(this, AppListActivity::class.java))
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
                Toast.makeText(this, getString(R.string.error_no_apps_selected_to_lock), Toast.LENGTH_LONG).show()
                return
            }

            // Verificar si los permisos requeridos de Android se han concedido antes de activar el bloqueo/temporizador
            if (!PermissionHelper.hasAllRequiredPermissions(this)) {
                Toast.makeText(this, getString(R.string.error_permissions_required_to_lock), Toast.LENGTH_LONG).show()
                return
            }
            controller.lock()
            Toast.makeText(this, getString(R.string.nfc_hardware_tag_locked), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Configuración del panel de Llave Secreta y vinculación de múltiples tarjetas NFC.
     */
    private fun setupNfcKeyManagement() {
        binding.tvSecretKeyDisplay.text = nfcSecurityManager.getSecretKey()

        binding.btnEditSecretKey.setOnClickListener {
            showEditSecretKeyDialog()
        }

        binding.btnProgramTag.setOnClickListener {
            toggleProgrammingMode()
        }

        binding.btnUnlinkTag.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Desvincular Tarjetas")
                .setMessage("¿Deseas desvincular todas las tarjetas NFC autorizadas?")
                .setPositiveButton("Desvincular Todas") { _, _ ->
                    nfcSecurityManager.clearAllCards()
                    updateNfcKeyManagementUi()
                    Toast.makeText(this, getString(R.string.nfc_tag_unlinked_toast), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        binding.btnAddSimulatedCard.setOnClickListener {
            val existing = nfcSecurityManager.getPairedCards()
            val count = existing.size + 1
            val simulatedId = "04:%02X:%02X:%02X:%02X".format(
                (10..250).random(), (10..250).random(), (10..250).random(), (10..250).random()
            )
            val cardName = "Tarjeta $count"
            val newCard = nfcSecurityManager.addPairedCard(simulatedId, cardName)
            updateNfcKeyManagementUi()
            Toast.makeText(this, getString(R.string.nfc_card_added_toast, newCard.name), Toast.LENGTH_SHORT).show()
        }

        updateNfcKeyManagementUi()
    }

    private fun toggleProgrammingMode() {
        if (isProgrammingTagMode) {
            isProgrammingTagMode = false
        } else {
            val adapter = nfcAdapter
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, "El hardware NFC debe estar activo en el dispositivo para grabar un tag.", Toast.LENGTH_LONG).show()
                return
            }
            isProgrammingTagMode = true
        }
        updateNfcKeyManagementUi()
    }

    private fun updateNfcKeyManagementUi() {
        val currentKey = nfcSecurityManager.getSecretKey()
        binding.tvSecretKeyDisplay.text = currentKey

        val pairedCards = nfcSecurityManager.getPairedCards()
        binding.containerPairedCardsList.removeAllViews()

        if (pairedCards.isEmpty()) {
            binding.tvPairedTagBadge.setText(R.string.nfc_paired_badge_none)
            binding.tvPairedTagBadge.setBackgroundResource(R.drawable.bg_badge_unlocked)
            binding.tvPairedTagBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvPairedTagDetail.setText(R.string.nfc_no_paired_tag_desc)
            binding.btnUnlinkTag.visibility = View.GONE
            binding.containerPairedCardsList.visibility = View.GONE
        } else {
            binding.tvPairedTagBadge.text = getString(R.string.nfc_multi_cards_title, pairedCards.size)
            binding.tvPairedTagBadge.setBackgroundResource(R.drawable.bg_badge_locked)
            binding.tvPairedTagBadge.setTextColor(ContextCompat.getColor(this, R.color.pastel_green_primary))
            binding.tvPairedTagDetail.text = "Cualquiera de las ${pairedCards.size} tarjetas autorizadas puede alternar el bloqueo de tus aplicaciones."
            binding.btnUnlinkTag.visibility = View.VISIBLE
            binding.containerPairedCardsList.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(this)
            for (card in pairedCards) {
                val itemBinding = ItemPairedNfcCardBinding.inflate(inflater, binding.containerPairedCardsList, false)
                itemBinding.tvCardName.text = card.name
                itemBinding.tvCardUid.text = getString(R.string.nfc_tag_id_format, card.uid)
                itemBinding.btnDeleteCard.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Desvincular Tarjeta")
                        .setMessage(getString(R.string.nfc_card_delete_confirm, card.name, card.uid))
                        .setPositiveButton("Desvincular") { _, _ ->
                            nfcSecurityManager.removePairedCard(card.uid)
                            Toast.makeText(this, R.string.nfc_card_deleted_toast, Toast.LENGTH_SHORT).show()
                            updateNfcKeyManagementUi()
                        }
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show()
                }
                binding.containerPairedCardsList.addView(itemBinding.root)
            }
        }

        if (isProgrammingTagMode) {
            binding.containerProgrammingBanner.visibility = View.VISIBLE
            binding.btnProgramTag.setText(R.string.nfc_btn_cancel_program)
            binding.btnProgramTag.setBackgroundColor(ContextCompat.getColor(this, R.color.button_dark))
        } else {
            binding.containerProgrammingBanner.visibility = View.GONE
            val btnText = if (pairedCards.isEmpty()) getString(R.string.nfc_btn_program_tag) else getString(R.string.nfc_btn_add_another_card)
            binding.btnProgramTag.text = btnText
            binding.btnProgramTag.setBackgroundColor(ContextCompat.getColor(this, R.color.nfc_blue_primary))
        }
    }

    private fun showEditSecretKeyDialog() {
        val input = EditText(this).apply {
            setText(nfcSecurityManager.getSecretKey())
            setSelection(text.length)
            isSingleLine = true
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_change_key_title))
            .setMessage(getString(R.string.dialog_change_key_msg))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newKey = input.text.toString().trim()
                if (newKey.isNotEmpty()) {
                    nfcSecurityManager.setSecretKey(newKey)
                    updateNfcKeyManagementUi()
                    Toast.makeText(this, "Clave actualizada. Graba tu tag NFC para aplicarla.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun setupButtons() {
        // Easter Egg: Múltiples toques al nombre de la app para abrir Padlock Runner & Biografía
        binding.tvAppTitle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastAppTitleTapTime > 2500L) {
                appTitleTapCount = 0
            }
            lastAppTitleTapTime = now
            appTitleTapCount++

            val requiredTaps = 5
            val remaining = requiredTaps - appTitleTapCount

            when {
                remaining <= 0 -> {
                    appTitleTapCount = 0
                    Toast.makeText(this, getString(R.string.easter_egg_unlocked_toast), Toast.LENGTH_SHORT).show()
                    EasterEggActivity.start(this)
                }
                remaining in 1..2 -> {
                    Toast.makeText(this, getString(R.string.easter_egg_hint_steps, remaining), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Asistente de Configuración Inicial (Onboarding Setup)
        binding.btnOpenSetupHeader.setOnClickListener {
            InitialSetupActivity.start(this)
        }

        // Acceso directo a Ko-fi
        binding.btnOpenKofiHeader.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://Ko-fi.com/Marbol077")))
            } catch (e: Exception) {
                Toast.makeText(this, "Ko-fi.com/Marbol077", Toast.LENGTH_SHORT).show()
            }
        }

        // Navegación a la pantalla de selección de aplicaciones
        binding.btnManageApps.setOnClickListener {
            val intent = Intent(this, AppListActivity::class.java)
            startActivity(intent)
        }

        // Simulación de toque de tarjeta/tag NFC
        binding.btnSimulateNfc.setOnClickListener {
            val currentLocked = controller.isLocked()
            if (currentLocked) {
                controller.unlock()
                Toast.makeText(this, getString(R.string.nfc_unlocked_toast), Toast.LENGTH_SHORT).show()
            } else {
                // Verificar que haya al menos una aplicación seleccionada para bloquear
                if (controller.getBlockedPackages().isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.error_no_apps_selected_dialog_title)
                        .setMessage(R.string.error_no_apps_selected_dialog_msg)
                        .setPositiveButton(R.string.btn_select_apps) { _, _ ->
                            startActivity(Intent(this, AppListActivity::class.java))
                        }
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show()
                    Toast.makeText(this, getString(R.string.error_no_apps_selected_to_lock), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!PermissionHelper.hasAllRequiredPermissions(this)) {
                    Toast.makeText(this, getString(R.string.error_permissions_required_to_lock), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                controller.lock()
                Toast.makeText(this, getString(R.string.nfc_locked_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Configuración de la tarjeta del Servicio de Accesibilidad (Bloqueo en Tiempo Real).
     */
    private fun setupAccessibilityCard() {
        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        binding.btnTestOverlay.setOnClickListener {
            val samplePackage = controller.getBlockedPackages().firstOrNull() ?: "com.instagram.android"
            if (!controller.isLocked()) {
                if (!PermissionHelper.hasAllRequiredPermissions(this)) {
                    Toast.makeText(this, getString(R.string.error_permissions_required_to_lock), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                controller.lock()
            }
            BlockOverlayActivity.start(this, samplePackage)
        }

        updateAccessibilityCardUi()
    }

    private fun updateAccessibilityCardUi() {
        val isServiceActive = AppBlockerAccessibilityService.isEnabled(this)
        if (isServiceActive) {
            binding.tvAccessibilityStatus.setText(R.string.accessibility_status_active)
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.pastel_green_primary))
            binding.btnEnableAccessibility.visibility = View.GONE
            binding.ivAccessibilityIcon.setColorFilter(ContextCompat.getColor(this, R.color.pastel_green_primary))
        } else {
            binding.tvAccessibilityStatus.setText(R.string.accessibility_status_inactive)
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnEnableAccessibility.visibility = View.VISIBLE
            binding.ivAccessibilityIcon.setColorFilter(ContextCompat.getColor(this, R.color.pastel_red_primary))
        }
    }

    /**
     * Configuración del botón de emergencia con temporizador de presión continua.
     * El usuario debe mantener pulsado durante 30 segundos continuos.
     * Si levanta el dedo antes de tiempo, se reinicia inmediatamente a cero.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupEmergencyButton() {
        binding.progressBarEmergency.max = TOTAL_EMERGENCY_MS.toInt()
        binding.progressBarEmergency.progress = 0
        binding.tvEmergencyTimer.text = "0.0s / 30.0s"

        binding.btnEmergencyHold.setOnTouchListener { _, event ->
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
        if (!controller.isLocked()) {
            Toast.makeText(this, "El bloqueo no está activo (Modo Libre).", Toast.LENGTH_SHORT).show()
            return
        }

        isHoldingEmergency = true
        emergencyElapsedMs = 0L
        binding.progressBarEmergency.progress = 0

        emergencyTimer?.cancel()
        emergencyTimer = object : CountDownTimer(TOTAL_EMERGENCY_MS, TIMER_TICK_MS) {
            override fun onTick(millisUntilFinished: Long) {
                if (!isHoldingEmergency) {
                    cancel()
                    return
                }
                emergencyElapsedMs = TOTAL_EMERGENCY_MS - millisUntilFinished
                binding.progressBarEmergency.progress = emergencyElapsedMs.toInt()

                val elapsedSeconds = emergencyElapsedMs / 1000.0
                binding.tvEmergencyTimer.text =
                    String.format(Locale.getDefault(), "%.1fs / 30.0s", elapsedSeconds)

                val secondsInt = (emergencyElapsedMs / 1000).toInt()
                binding.btnEmergencyHold.text =
                    getString(R.string.emergency_btn_holding, secondsInt)
            }

            override fun onFinish() {
                if (isHoldingEmergency) {
                    binding.progressBarEmergency.progress = TOTAL_EMERGENCY_MS.toInt()
                    binding.tvEmergencyTimer.text = "30.0s / 30.0s"
                    isHoldingEmergency = false

                    // Desbloqueo exitoso tras 30 segundos continuos
                    controller.unlock()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.emergency_unlocked_toast),
                        Toast.LENGTH_LONG
                    ).show()

                    resetEmergencyUi()
                }
            }
        }.start()
    }

    private fun cancelEmergencyHold() {
        if (isHoldingEmergency) {
            isHoldingEmergency = false
            emergencyTimer?.cancel()

            // Reinicio inmediato a cero si se levanta el dedo antes de tiempo
            resetEmergencyUi()

            Toast.makeText(
                this,
                getString(R.string.emergency_cancelled_toast),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun resetEmergencyUi() {
        binding.progressBarEmergency.progress = 0
        binding.tvEmergencyTimer.text = "0.0s / 30.0s"
        binding.btnEmergencyHold.text = getString(R.string.emergency_btn_idle)
    }

    /**
     * Actualiza la UI visual con transición de paleta pastel según estado Libre vs Bloqueado.
     */
    private fun updateStatusUi(isLocked: Boolean) {
        if (isLocked) {
            // Estado Bloqueado: Fondo pastel rojo y advertencia sutil
            binding.cardStatus.setBackgroundResource(R.drawable.bg_status_card_locked)
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_locked)
            binding.tvStatusBadge.setText(R.string.badge_locked)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.pastel_red_dark))

            binding.ivStatusIcon.setImageResource(R.drawable.ic_lock_closed)
            binding.tvStatusTitle.setText(R.string.status_locked_title)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.pastel_red_dark))
            binding.tvStatusSubtitle.setText(R.string.status_locked_subtitle)

            binding.tvNfcButtonTitle.text = "Simular Tag NFC (Liberar bloqueo)"
            binding.tvNfcButtonDesc.text = "Simula acercar la tarjeta NFC para desactivar el bloqueo"

            binding.cardEmergency.alpha = 1.0f
            binding.btnEmergencyHold.isEnabled = true
        } else {
            // Estado Libre: Fondo pastel verde
            binding.cardStatus.setBackgroundResource(R.drawable.bg_status_card_unlocked)
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_unlocked)
            binding.tvStatusBadge.setText(R.string.badge_unlocked)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.pastel_green_dark))

            binding.ivStatusIcon.setImageResource(R.drawable.ic_lock_open)
            binding.tvStatusTitle.setText(R.string.status_unlocked_title)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.pastel_green_dark))
            binding.tvStatusSubtitle.setText(R.string.status_unlocked_subtitle)

            binding.tvNfcButtonTitle.text = "Simular Tag NFC (Activar bloqueo)"
            binding.tvNfcButtonDesc.text = "Simula acercar la tarjeta NFC para activar el modo de enfoque"

            // En modo libre, el botón de emergencia se muestra disponible como referencia
            binding.cardEmergency.alpha = 0.85f
            resetEmergencyUi()
        }

        updateLockModeBadge()
    }

    private fun updateLockModeBadge() {
        val mode = controller.getLockMode()
        when (mode) {
            LockMode.TIMER -> {
                val mins = controller.getConfiguredTimerMinutes()
                binding.tvCurrentLockModeBadge.text = "Modo: Temporizador ($mins min)"
            }
            LockMode.NFC_ONLY -> {
                binding.tvCurrentLockModeBadge.text = "Modo: Solo Tarjeta NFC (Sin tiempo)"
            }
            LockMode.EMERGENCY_ONLY -> {
                binding.tvCurrentLockModeBadge.text = "Modo: Solo Botón de Emergencia (Sin tiempo)"
            }
        }
    }

    private fun updateBlockedPackagesSummary(packages: Set<String>) {
        val count = packages.size
        if (count == 0) {
            binding.tvBlockedSummary.text = getString(R.string.blocked_summary_zero)
        } else {
            binding.tvBlockedSummary.text = getString(R.string.blocked_summary_count, count)
        }
    }

    companion object {
        private const val TOTAL_EMERGENCY_MS = 30_000L // 30 segundos continuos
        private const val TIMER_TICK_MS = 100L        // Actualización fluida cada 100 ms
    }
}
