package com.example.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.R
import com.example.data.MockAppBlockerController

/**
 * Receptor de eventos desde las notificaciones de TagLock.
 * Maneja la acción de "+5 minutos de enfoque" (intervalo de +5 mins, nunca bajar).
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_ADD_5_MINUTES) {
            val controller = MockAppBlockerController.getInstance(context)
            if (controller.isLocked()) {
                val extended = controller.extendSessionMinutes(5)
                if (extended) {
                    val notifManager = TagLockNotificationManager.getInstance(context)
                    notifManager.updateRemainingTimeNotification(controller.getSessionRemainingMillis())
                    Toast.makeText(context, R.string.notification_extended_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val ACTION_ADD_5_MINUTES = "com.example.notification.ACTION_ADD_5_MINUTES"
    }
}
