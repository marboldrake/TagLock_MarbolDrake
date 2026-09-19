package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import com.example.data.MockAppBlockerController
import com.example.ui.overlay.BlockOverlayActivity

/**
 * Servicio de Accesibilidad para interceptar en tiempo real la apertura de aplicaciones bloqueadas.
 * Cuando detecta que el usuario abre un paquete bloqueado durante el modo de enfoque activo,
 * despliega instantáneamente la pantalla de bloqueo y temporizador [BlockOverlayActivity].
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    private lateinit var controller: MockAppBlockerController
    private var lastBlockedLaunchTime: Long = 0L
    private var lastBlockedPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        controller = MockAppBlockerController.getInstance(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val targetPackage = event.packageName?.toString() ?: return

        // Ignorar nuestra propia aplicación para evitar bucles infinitos
        if (targetPackage == packageName) {
            return
        }

        // Si el bloqueo está activo y la app actual está en la lista negra
        if (controller.isPackageBlocked(targetPackage)) {
            val now = System.currentTimeMillis()
            // Evitar re-lanzamientos repetidos en ráfaga para el mismo paquete en menos de 1000ms
            if (targetPackage == lastBlockedPackage && (now - lastBlockedLaunchTime) < 1000) {
                return
            }

            lastBlockedLaunchTime = now
            lastBlockedPackage = targetPackage

            // Lanzar el overlay de bloqueo de inmediato
            BlockOverlayActivity.start(this, targetPackage)
        }
    }

    override fun onInterrupt() {
        // Limpieza de estado si el servicio es interrumpido por el sistema
    }

    companion object {
        /**
         * Verifica si el servicio de accesibilidad de TagLock está habilitado en los ajustes del sistema.
         */
        fun isEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, AppBlockerAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }
}
