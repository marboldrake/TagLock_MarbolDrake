package com.example.ui.setup

import android.Manifest
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.MainActivity
import com.example.R
import com.example.data.AppPreferences
import com.example.data.NfcSecurityManager
import com.example.databinding.ActivityInitialSetupBinding
import com.example.databinding.ItemPairedNfcCardBinding
import com.example.databinding.ItemSetupPageLanguageBinding
import com.example.databinding.ItemSetupPageNfcBinding
import com.example.databinding.ItemSetupPagePermissionsBinding
import com.example.databinding.ItemSetupPageSupportBinding
import com.example.databinding.ItemSetupPageThemeBinding
import com.example.databinding.ItemSetupPageWelcomeBinding
import com.example.service.AppBlockerAccessibilityService
import com.example.util.NfcTagHelper
import com.example.util.PermissionHelper

/**
 * Pantalla de Configuración Inicial (Onboarding Setup Wizard) de TagLock.
 * 6 Páginas independientes:
 * 1. Página de Bienvenida y presentación.
 * 2. Permisos de Android (Notificaciones Push y Accesibilidad).
 * 3. Elección de Tema (Claro / Oscuro / Sistema).
 * 4. Selección de Idioma (Español / Inglés / Sistema).
 * 5. Escaneo de Tag NFC Inicial (físico o simulación de prueba).
 * 6. Fuentes y Soporte con ID Ko-fi (Ko-fi.com/Marbol077) + Easter Egg de Barista.
 */
class InitialSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInitialSetupBinding
    private lateinit var appPreferences: AppPreferences
    private lateinit var nfcSecurityManager: NfcSecurityManager

    // NFC Foreground Dispatch para el paso 5
    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null
    private var nfcIntentFilters: Array<IntentFilter>? = null

    // Callback para actualizar la UI del paso 5 si se escanea un tag NFC
    private var nfcScanListener: ((String) -> Unit)? = null

    // Callback para actualizar la UI de permisos si cambian
    private var permissionsUpdateListener: (() -> Unit)? = null

    // Contador para el Easter Egg en la página de soporte (7 toques al café)
    private var easterEggCoffeeClicks: Int = 0

    // Launcher de permisos de notificaciones (Android 13+)
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            permissionsUpdateListener?.invoke()
            if (isGranted) {
                Toast.makeText(this, "Permiso de notificaciones concedido", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityInitialSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.setupRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        appPreferences = AppPreferences.getInstance(this)
        nfcSecurityManager = NfcSecurityManager.getInstance(this)

        initNfcAdapter()
        setupViewPager()
        setupNavigationControls()

        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
        permissionsUpdateListener?.invoke()
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun setupViewPager() {
        val adapter = SetupPagerAdapter()
        binding.viewPagerSetup.adapter = adapter
        binding.viewPagerSetup.isUserInputEnabled = false // Controlar el avance por validación de permisos

        binding.viewPagerSetup.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateProgressUi(position)
            }
        })
    }

    private fun updateProgressUi(position: Int) {
        val pageNumber = position + 1
        binding.tvStepIndicator.text = "Paso $pageNumber de 6"
        binding.stepProgressBar.progress = pageNumber

        // Control de botón "Anterior"
        if (position == 0) {
            binding.btnPrevStep.visibility = View.GONE
        } else {
            binding.btnPrevStep.visibility = View.VISIBLE
        }

        // Control de botón "Siguiente" o "Finalizar"
        if (position == 5) {
            binding.btnNextStep.setText(R.string.setup_btn_finish)
            binding.btnSkipSetup.visibility = View.GONE
        } else {
            binding.btnNextStep.setText(R.string.setup_btn_next)
            // En el paso de permisos no se permite omitir sin conceder
            binding.btnSkipSetup.visibility = if (position == 1) View.GONE else View.VISIBLE
        }
    }

    private fun setupNavigationControls() {
        binding.btnHeaderBack.setOnClickListener {
            val current = binding.viewPagerSetup.currentItem
            if (current > 0) {
                binding.viewPagerSetup.currentItem = current - 1
            } else {
                finishSetup()
            }
        }

        binding.btnPrevStep.setOnClickListener {
            val current = binding.viewPagerSetup.currentItem
            if (current > 0) {
                binding.viewPagerSetup.currentItem = current - 1
            }
        }

        binding.btnNextStep.setOnClickListener {
            val current = binding.viewPagerSetup.currentItem
            // Si estamos en la página 1 (Permisos de Android), no dejar avanzar hasta que estén concedidos
            if (current == 1) {
                if (!PermissionHelper.hasAllRequiredPermissions(this)) {
                    Toast.makeText(this, getString(R.string.setup_perms_required_toast), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            if (current < 5) {
                binding.viewPagerSetup.currentItem = current + 1
            } else {
                finishSetup()
            }
        }

        binding.btnSkipSetup.setOnClickListener {
            val current = binding.viewPagerSetup.currentItem
            if (current == 1 && !PermissionHelper.hasAllRequiredPermissions(this)) {
                Toast.makeText(this, getString(R.string.setup_perms_required_toast), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            finishSetup()
        }
    }

    private fun finishSetup() {
        appPreferences.setSetupCompleted(true)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    // --- Adaptador de las 6 páginas ---
    private inner class SetupPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = 6

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> WelcomeViewHolder(ItemSetupPageWelcomeBinding.inflate(inflater, parent, false))
                1 -> PermissionsViewHolder(ItemSetupPagePermissionsBinding.inflate(inflater, parent, false))
                2 -> ThemeViewHolder(ItemSetupPageThemeBinding.inflate(inflater, parent, false))
                3 -> LanguageViewHolder(ItemSetupPageLanguageBinding.inflate(inflater, parent, false))
                4 -> NfcScanViewHolder(ItemSetupPageNfcBinding.inflate(inflater, parent, false))
                else -> SupportViewHolder(ItemSetupPageSupportBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is PermissionsViewHolder -> holder.bind()
                is ThemeViewHolder -> holder.bind()
                is LanguageViewHolder -> holder.bind()
                is NfcScanViewHolder -> holder.bind()
                is SupportViewHolder -> holder.bind()
            }
        }
    }

    // --- ViewHolder 1: Bienvenida ---
    private class WelcomeViewHolder(binding: ItemSetupPageWelcomeBinding) :
        RecyclerView.ViewHolder(binding.root)

    // --- ViewHolder 2: Permisos de Android (Notificaciones y Accesibilidad) ---
    private inner class PermissionsViewHolder(private val b: ItemSetupPagePermissionsBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind() {
            updatePermissionStates()

            permissionsUpdateListener = {
                updatePermissionStates()
            }

            b.btnGrantNotificationPerm.setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }

            b.btnGrantAccessibilityPerm.setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        private fun updatePermissionStates() {
            // Notificaciones
            val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this@InitialSetupActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                androidx.core.app.NotificationManagerCompat.from(this@InitialSetupActivity).areNotificationsEnabled()
            }

            if (notifGranted) {
                b.btnGrantNotificationPerm.text = getString(R.string.setup_perm_granted)
                b.btnGrantNotificationPerm.isEnabled = false
                b.ivPermNotifIcon.setColorFilter(ContextCompat.getColor(this@InitialSetupActivity, R.color.pastel_green_primary))
            } else {
                b.btnGrantNotificationPerm.text = getString(R.string.setup_perm_btn_grant)
                b.btnGrantNotificationPerm.isEnabled = true
                b.ivPermNotifIcon.setColorFilter(ContextCompat.getColor(this@InitialSetupActivity, R.color.nfc_blue_primary))
            }

            // Accesibilidad
            val accessGranted = AppBlockerAccessibilityService.isEnabled(this@InitialSetupActivity)
            if (accessGranted) {
                b.btnGrantAccessibilityPerm.text = getString(R.string.setup_perm_granted)
                b.btnGrantAccessibilityPerm.isEnabled = false
                b.ivPermAccessIcon.setColorFilter(ContextCompat.getColor(this@InitialSetupActivity, R.color.pastel_green_primary))
            } else {
                b.btnGrantAccessibilityPerm.text = getString(R.string.setup_perm_btn_grant)
                b.btnGrantAccessibilityPerm.isEnabled = true
                b.ivPermAccessIcon.setColorFilter(ContextCompat.getColor(this@InitialSetupActivity, R.color.pastel_red_primary))
            }
        }
    }

    // --- ViewHolder 3: Tema (Claro / Oscuro / Sistema) ---
    private inner class ThemeViewHolder(private val b: ItemSetupPageThemeBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind() {
            updateThemeCards(appPreferences.getThemeMode())

            b.cardThemeLight.setOnClickListener {
                appPreferences.setThemeMode(AppPreferences.THEME_LIGHT)
                updateThemeCards(AppPreferences.THEME_LIGHT)
            }

            b.cardThemeDark.setOnClickListener {
                appPreferences.setThemeMode(AppPreferences.THEME_DARK)
                updateThemeCards(AppPreferences.THEME_DARK)
            }

            b.cardThemeSystem.setOnClickListener {
                appPreferences.setThemeMode(AppPreferences.THEME_SYSTEM)
                updateThemeCards(AppPreferences.THEME_SYSTEM)
            }
        }

        private fun updateThemeCards(currentMode: Int) {
            setOptionSelected(b.cardThemeLight, b.ivThemeLightCheck, currentMode == AppPreferences.THEME_LIGHT)
            setOptionSelected(b.cardThemeDark, b.ivThemeDarkCheck, currentMode == AppPreferences.THEME_DARK)
            setOptionSelected(b.cardThemeSystem, b.ivThemeSystemCheck, currentMode == AppPreferences.THEME_SYSTEM)
        }
    }

    // --- ViewHolder 4: Idioma (Español / Inglés / Sistema) ---
    private inner class LanguageViewHolder(private val b: ItemSetupPageLanguageBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind() {
            updateLanguageCards(appPreferences.getLanguageCode())

            b.cardLangEs.setOnClickListener {
                appPreferences.setLanguageCode(AppPreferences.LANG_ES)
                updateLanguageCards(AppPreferences.LANG_ES)
            }

            b.cardLangEn.setOnClickListener {
                appPreferences.setLanguageCode(AppPreferences.LANG_EN)
                updateLanguageCards(AppPreferences.LANG_EN)
            }

            b.cardLangSystem.setOnClickListener {
                appPreferences.setLanguageCode(AppPreferences.LANG_SYSTEM)
                updateLanguageCards(AppPreferences.LANG_SYSTEM)
            }
        }

        private fun updateLanguageCards(currentLang: String) {
            setOptionSelected(b.cardLangEs, b.ivLangEsCheck, currentLang == AppPreferences.LANG_ES)
            setOptionSelected(b.cardLangEn, b.ivLangEnCheck, currentLang == AppPreferences.LANG_EN)
            setOptionSelected(b.cardLangSystem, b.ivLangSystemCheck, currentLang == AppPreferences.LANG_SYSTEM)
        }
    }

    // --- ViewHolder 5: Escaneo de Tag NFC Inicial (Multi-tarjetas) ---
    private inner class NfcScanViewHolder(private val b: ItemSetupPageNfcBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind() {
            updateCardsDisplay()

            nfcScanListener = {
                updateCardsDisplay()
            }

            b.btnSimulateInitialTag.setOnClickListener {
                val existing = nfcSecurityManager.getPairedCards()
                val tagId = "04:5F:8A:2B:C9"
                val cardName = if (existing.isEmpty()) "Tarjeta 1 (Principal)" else "Tarjeta ${existing.size + 1}"
                nfcSecurityManager.addPairedCard(tagId, cardName)
                updateCardsDisplay()
                Toast.makeText(this@InitialSetupActivity, getString(R.string.nfc_card_added_toast, cardName), Toast.LENGTH_SHORT).show()
            }

            b.btnAddAnotherSimulatedTag.setOnClickListener {
                val count = nfcSecurityManager.getPairedCards().size + 1
                val simulatedId = "04:%02X:%02X:%02X:%02X".format(
                    (10..250).random(), (10..250).random(), (10..250).random(), (10..250).random()
                )
                val cardName = "Tarjeta $count"
                nfcSecurityManager.addPairedCard(simulatedId, cardName)
                updateCardsDisplay()
                Toast.makeText(this@InitialSetupActivity, getString(R.string.nfc_card_added_toast, cardName), Toast.LENGTH_SHORT).show()
            }
        }

        private fun updateCardsDisplay() {
            val cards = nfcSecurityManager.getPairedCards()
            b.containerSetupCardsList.removeAllViews()

            if (cards.isNotEmpty()) {
                b.tvNfcScanStatus.text = getString(R.string.nfc_multi_cards_title, cards.size)
                b.tvNfcScanStatus.setTextColor(ContextCompat.getColor(this@InitialSetupActivity, R.color.pastel_green_primary))
                b.ivNfcScanIcon.setColorFilter(ContextCompat.getColor(this@InitialSetupActivity, R.color.pastel_green_primary))
                b.tvNfcScanTagId.visibility = View.GONE
                b.containerSetupCardsList.visibility = View.VISIBLE
                b.btnAddAnotherSimulatedTag.visibility = View.VISIBLE

                val inflater = LayoutInflater.from(this@InitialSetupActivity)
                for (card in cards) {
                    val itemBinding = ItemPairedNfcCardBinding.inflate(inflater, b.containerSetupCardsList, false)
                    itemBinding.tvCardName.text = card.name
                    itemBinding.tvCardUid.text = getString(R.string.nfc_tag_id_format, card.uid)
                    itemBinding.btnDeleteCard.setOnClickListener {
                        nfcSecurityManager.removePairedCard(card.uid)
                        Toast.makeText(this@InitialSetupActivity, R.string.nfc_card_deleted_toast, Toast.LENGTH_SHORT).show()
                        updateCardsDisplay()
                    }
                    b.containerSetupCardsList.addView(itemBinding.root)
                }
            } else {
                b.tvNfcScanStatus.setText(R.string.setup_nfc_status_waiting)
                b.tvNfcScanStatus.setTextColor(ContextCompat.getColor(this@InitialSetupActivity, R.color.nfc_blue_primary))
                b.ivNfcScanIcon.setColorFilter(ContextCompat.getColor(this@InitialSetupActivity, R.color.nfc_blue_primary))
                b.tvNfcScanTagId.visibility = View.GONE
                b.containerSetupCardsList.visibility = View.GONE
                b.btnAddAnotherSimulatedTag.visibility = View.GONE
            }
        }
    }

    // --- ViewHolder 6: Fuentes y Soporte Ko-fi + Easter Egg ---
    private inner class SupportViewHolder(private val b: ItemSetupPageSupportBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind() {
            val kofiUrl = "https://Ko-fi.com/Marbol077"

            val openKofiAction = {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(kofiUrl))
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(this@InitialSetupActivity, "No se encontró navegador web", Toast.LENGTH_SHORT).show()
                }
            }

            b.btnOpenKofi.setOnClickListener { openKofiAction() }
            b.containerDirectKofiLink.setOnClickListener { openKofiAction() }
            b.tvDirectKofiUrl.setOnClickListener { openKofiAction() }

            b.btnCopyKofi.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Ko-fi URL", kofiUrl)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@InitialSetupActivity, R.string.setup_support_copied_toast, Toast.LENGTH_SHORT).show()
            }

            // Easter Egg: Tocar 7 veces el icono de la taza de café
            b.ivSupportCoffeeIcon.setOnClickListener {
                easterEggCoffeeClicks++
                if (easterEggCoffeeClicks == 7) {
                    easterEggCoffeeClicks = 0
                    showEasterEggDialog()
                } else if (easterEggCoffeeClicks >= 4) {
                    val remainingClicks = 7 - easterEggCoffeeClicks
                    Toast.makeText(this@InitialSetupActivity, "☕ ¿Buscas café secreto? $remainingClicks...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showEasterEggDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.easter_egg_activated_title))
            .setMessage(getString(R.string.easter_egg_activated_msg))
            .setIcon(R.drawable.ic_coffee)
            .setPositiveButton("¡Increíble!") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun setOptionSelected(card: View, checkIcon: ImageView, isSelected: Boolean) {
        if (isSelected) {
            card.setBackgroundResource(R.drawable.bg_option_card_selected)
            checkIcon.visibility = View.VISIBLE
        } else {
            card.setBackgroundResource(R.drawable.bg_option_card_unselected)
            checkIcon.visibility = View.GONE
        }
    }

    // --- NFC Handling para registrar tag en el paso 5 ---
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
            val secretKey = nfcSecurityManager.getSecretKey()
            val packageName = applicationContext.packageName

            // Intentar escribir la clave secreta y el AAR al tag
            val writeResult = NfcTagHelper.writeTagWithSecretAndAar(tag, secretKey, packageName)

            // Registrar el UID agregándolo como tarjeta autorizada
            val card = nfcSecurityManager.addPairedCard(tagId)

            nfcScanListener?.invoke(tagId)

            if (writeResult.isSuccess) {
                Toast.makeText(this, getString(R.string.setup_nfc_paired_and_written), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.setup_nfc_paired_uid_only, tagId), Toast.LENGTH_LONG).show()
            }

            // Llevar al usuario a la página 5 de NFC si no está en ella
            binding.viewPagerSetup.currentItem = 4
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, InitialSetupActivity::class.java)
            context.startActivity(intent)
        }
    }
}
