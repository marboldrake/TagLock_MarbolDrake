package com.example

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.adapter.AppListAdapter
import com.example.data.LockMode
import com.example.data.MockAppBlockerController
import com.example.databinding.ActivityAppListBinding
import com.example.databinding.DialogLockModeSelectorBinding
import com.example.model.AppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla para la selección y filtrado de aplicaciones a bloquear.
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private lateinit var controller: MockAppBlockerController
    private lateinit var adapter: AppListAdapter

    private val allApps = mutableListOf<AppItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appListRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        controller = MockAppBlockerController.getInstance(this)

        setupRecyclerView()
        setupListeners()
        loadInstalledApps()
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter { appItem, isBlocked ->
            if (isBlocked) {
                controller.addBlockedPackage(appItem.packageName)
            } else {
                controller.removeBlockedPackage(appItem.packageName)
            }
            updateSummaryText()
        }

        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnDone.setOnClickListener {
            showLockModeDialog()
        }

        binding.etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                adapter.filter(query)
                checkEmptyState()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun checkEmptyState() {
        val hasResults = adapter.itemCount > 0
        binding.tvEmptyState.visibility = if (hasResults) View.GONE else View.VISIBLE
    }

    private fun updateSummaryText() {
        val selected = adapter.getSelectedCount()
        val total = adapter.getTotalCount()
        binding.tvSelectedSummary.text = getString(R.string.selected_apps_count, selected, total)
    }

    private fun loadInstalledApps() {
        binding.progressBarLoading.visibility = View.VISIBLE
        binding.rvApps.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Default) {
            val blockedPackages = controller.getBlockedPackages()
            val pm = packageManager
            val items = mutableListOf<AppItem>()
            val seenPackages = mutableSetOf<String>()

            // 1. Consultar aplicaciones con actividad de Launcher
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName
                // Excluir la propia aplicación de la lista de bloqueo
                if (pkg != packageName && seenPackages.add(pkg)) {
                    val appName = info.loadLabel(pm).toString()
                    val icon = info.loadIcon(pm)
                    val isBlocked = blockedPackages.contains(pkg)
                    items.add(AppItem(pkg, appName, icon, isBlocked))
                }
            }

            // 2. Si hay pocas apps (por ejemplo en entorno de pruebas o emulador básico),
            // agregar apps populares de muestra para que el usuario pueda probar de inmediato
            if (items.size < 6) {
                val mockCatalog = listOf(
                    "com.instagram.android" to "Instagram",
                    "com.zhiliaoapp.musically" to "TikTok",
                    "com.google.android.youtube" to "YouTube",
                    "com.whatsapp" to "WhatsApp",
                    "com.twitter.android" to "X (Twitter)",
                    "com.reddit.frontpage" to "Reddit",
                    "com.android.chrome" to "Google Chrome",
                    "com.netflix.mediaclient" to "Netflix",
                    "com.facebook.katana" to "Facebook"
                )

                for ((mockPkg, mockName) in mockCatalog) {
                    if (seenPackages.add(mockPkg)) {
                        // Intentar obtener icono del sistema o usar default
                        val icon = try {
                            pm.getApplicationIcon(mockPkg)
                        } catch (_: Exception) {
                            ContextCompat.getDrawable(this@AppListActivity, R.drawable.ic_apps)
                        }
                        val isBlocked = blockedPackages.contains(mockPkg)
                        items.add(AppItem(mockPkg, mockName, icon, isBlocked))
                    }
                }
            }

            // Ordenar alfabéticamente
            items.sortBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(items)
                adapter.submitList(items)
                binding.progressBarLoading.visibility = View.GONE
                binding.rvApps.visibility = View.VISIBLE
                updateSummaryText()
                checkEmptyState()
            }
        }
    }

    /**
     * Muestra el diálogo para elegir el tipo de bloqueo antes de guardar.
     * Permite:
     * - Bloqueo por tiempo (presets 15m, 30m, 60m o personalizado)
     * - Sin tiempo (Solo Tarjeta NFC)
     * - Solo Botón de Emergencia (30s)
     */
    private fun showLockModeDialog() {
        val dialogBinding = DialogLockModeSelectorBinding.inflate(layoutInflater)
        val currentMode = controller.getLockMode()
        val currentMinutes = controller.getConfiguredTimerMinutes()

        var selectedMode = currentMode
        var selectedMinutes = currentMinutes

        fun updateDialogState() {
            // Estado de radio buttons
            dialogBinding.rbModeTimer.isChecked = (selectedMode == LockMode.TIMER)
            dialogBinding.rbModeNfcOnly.isChecked = (selectedMode == LockMode.NFC_ONLY)
            dialogBinding.rbModeEmergencyOnly.isChecked = (selectedMode == LockMode.EMERGENCY_ONLY)

            // Bordes de selección de tarjetas
            val blueColor = ContextCompat.getColor(this, R.color.nfc_blue_primary)
            val dividerColor = ContextCompat.getColor(this, R.color.divider)

            dialogBinding.cardModeTimer.strokeColor = if (selectedMode == LockMode.TIMER) blueColor else dividerColor
            dialogBinding.cardModeTimer.strokeWidth = if (selectedMode == LockMode.TIMER) 4 else 2

            dialogBinding.cardModeNfcOnly.strokeColor = if (selectedMode == LockMode.NFC_ONLY) blueColor else dividerColor
            dialogBinding.cardModeNfcOnly.strokeWidth = if (selectedMode == LockMode.NFC_ONLY) 4 else 2

            dialogBinding.cardModeEmergencyOnly.strokeColor = if (selectedMode == LockMode.EMERGENCY_ONLY) blueColor else dividerColor
            dialogBinding.cardModeEmergencyOnly.strokeWidth = if (selectedMode == LockMode.EMERGENCY_ONLY) 4 else 2

            dialogBinding.layoutTimerPresets.visibility = if (selectedMode == LockMode.TIMER) View.VISIBLE else View.GONE
        }

        // Configuración inicial de presets de tiempo
        when (currentMinutes) {
            15 -> dialogBinding.chipPreset15.isChecked = true
            30 -> dialogBinding.chipPreset30.isChecked = true
            60 -> dialogBinding.chipPreset60.isChecked = true
            else -> {
                dialogBinding.chipPresetCustom.isChecked = true
                dialogBinding.tilCustomMinutes.visibility = View.VISIBLE
                dialogBinding.etCustomMinutes.setText(currentMinutes.toString())
            }
        }

        dialogBinding.cardModeTimer.setOnClickListener {
            selectedMode = LockMode.TIMER
            updateDialogState()
        }
        dialogBinding.rbModeTimer.setOnClickListener {
            selectedMode = LockMode.TIMER
            updateDialogState()
        }

        dialogBinding.cardModeNfcOnly.setOnClickListener {
            selectedMode = LockMode.NFC_ONLY
            updateDialogState()
        }
        dialogBinding.rbModeNfcOnly.setOnClickListener {
            selectedMode = LockMode.NFC_ONLY
            updateDialogState()
        }

        dialogBinding.cardModeEmergencyOnly.setOnClickListener {
            selectedMode = LockMode.EMERGENCY_ONLY
            updateDialogState()
        }
        dialogBinding.rbModeEmergencyOnly.setOnClickListener {
            selectedMode = LockMode.EMERGENCY_ONLY
            updateDialogState()
        }

        dialogBinding.chipGroupTimerPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.contains(R.id.chipPreset15)) {
                selectedMinutes = 15
                dialogBinding.tilCustomMinutes.visibility = View.GONE
            } else if (checkedIds.contains(R.id.chipPreset30)) {
                selectedMinutes = 30
                dialogBinding.tilCustomMinutes.visibility = View.GONE
            } else if (checkedIds.contains(R.id.chipPreset60)) {
                selectedMinutes = 60
                dialogBinding.tilCustomMinutes.visibility = View.GONE
            } else if (checkedIds.contains(R.id.chipPresetCustom)) {
                dialogBinding.tilCustomMinutes.visibility = View.VISIBLE
            }
        }

        updateDialogState()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.lock_mode_btn_confirm) { _, _ ->
                if (selectedMode == LockMode.TIMER) {
                    if (dialogBinding.chipPresetCustom.isChecked) {
                        val input = dialogBinding.etCustomMinutes.text?.toString()?.toIntOrNull()
                        if (input != null && input > 0) {
                            selectedMinutes = input
                        }
                    }
                    controller.setConfiguredTimerMinutes(selectedMinutes)
                }
                controller.setLockMode(selectedMode)

                Toast.makeText(this, getString(R.string.apps_saved_toast), Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.show()
    }
}
