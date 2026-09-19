package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.service.AppBlockerAccessibilityService

/**
 * Utilidad centralizada para verificar si los permisos esenciales de Android
 * (Notificaciones y Servicio de Accesibilidad) están plenamente concedidos.
 */
object PermissionHelper {

    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun isAccessibilityPermissionGranted(context: Context): Boolean {
        return AppBlockerAccessibilityService.isEnabled(context)
    }

    /**
     * Retorna verdadero únicamente si AMBOS permisos requeridos por Android
     * han sido concedidos por el usuario.
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return isNotificationPermissionGranted(context) && isAccessibilityPermissionGranted(context)
    }
}
