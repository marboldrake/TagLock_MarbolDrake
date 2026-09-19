package com.example.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.LockMode
import com.example.data.MockAppBlockerController
import com.example.ui.overlay.BlockOverlayActivity
import java.util.Locale

/**
 * Gestor central de Notificaciones de TagLock.
 * Publica y actualiza:
 * 1. Notificación de Bloqueo Activado (con intent para abrir la pantalla de overlay/tiempo restante y acción +5 min si aplica).
 * 2. Notificación en curso con el tiempo restante formateado (MM:SS) y acción de +5 min (si modo TIMER).
 * 3. Notificación de Bloqueo Desactivado.
 */
class TagLockNotificationManager(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_name)
            val descriptionText = context.getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun canSendNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            notificationManager.areNotificationsEnabled()
        }
    }

    /**
     * Notificación cuando el bloqueo se ACTIVA.
     * Al hacer click lleva a la pantalla de tiempo restante (BlockOverlayActivity).
     * Incluye botón de acción para +5 min de enfoque si el modo es con temporizador.
     */
    fun showLockActivatedNotification() {
        if (!canSendNotifications()) return

        val controller = MockAppBlockerController.getInstance(context)
        val mode = controller.getLockMode()

        val title: String
        val msg: String
        when (mode) {
            LockMode.TIMER -> {
                title = context.getString(R.string.notification_locked_title)
                msg = context.getString(R.string.notification_locked_msg)
            }
            LockMode.NFC_ONLY -> {
                title = context.getString(R.string.notification_locked_nfc_only_title)
                msg = context.getString(R.string.notification_locked_nfc_only_msg)
            }
            LockMode.EMERGENCY_ONLY -> {
                title = context.getString(R.string.notification_locked_emergency_only_title)
                msg = context.getString(R.string.notification_locked_emergency_only_msg)
            }
        }

        val overlayPendingIntent = createOpenOverlayPendingIntent()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_closed)
            .setContentTitle(title)
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(overlayPendingIntent)
            .setColor(ContextCompat.getColor(context, R.color.nfc_blue_primary))

        if (mode == LockMode.TIMER) {
            val addTimePendingIntent = createAdd5MinPendingIntent()
            builder.addAction(
                R.drawable.ic_add,
                context.getString(R.string.notification_action_add_5min),
                addTimePendingIntent
            )
        }

        try {
            notificationManager.notify(NOTIFICATION_ID_LOCK_STATUS, builder.build())
        } catch (_: SecurityException) {}
    }

    /**
     * Notificación cuando el bloqueo se DESACTIVA.
     * Al hacer click abre la app principal.
     */
    fun showLockDeactivatedNotification() {
        if (!canSendNotifications()) return

        // Cancelar notificación de tiempo restante activo
        notificationManager.cancel(NOTIFICATION_ID_SESSION_PROGRESS)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val mainPendingIntent = PendingIntent.getActivity(context, 201, mainIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_open)
            .setContentTitle(context.getString(R.string.notification_unlocked_title))
            .setContentText(context.getString(R.string.notification_unlocked_msg))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent)
            .setColor(ContextCompat.getColor(context, R.color.pastel_green_primary))
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID_LOCK_STATUS, notification)
        } catch (_: SecurityException) {}
    }

    /**
     * Notificación de progreso del tiempo restante en curso (MM:SS).
     * Al tocarla abre la pantalla de tiempo restante / overlay.
     * Incluye botón para aumentar +5 minutos sin bajar nunca.
     */
    fun updateRemainingTimeNotification(remainingMillis: Long) {
        if (!canSendNotifications() || remainingMillis <= 0) {
            cancelRemainingTimeNotification()
            return
        }

        val totalSeconds = (remainingMillis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        val overlayPendingIntent = createOpenOverlayPendingIntent()
        val addTimePendingIntent = createAdd5MinPendingIntent()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.notification_timer_title, formattedTime))
            .setContentText(context.getString(R.string.notification_timer_subtitle))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(overlayPendingIntent)
            .setColor(ContextCompat.getColor(context, R.color.nfc_blue_primary))
            .addAction(
                R.drawable.ic_add,
                context.getString(R.string.notification_action_add_5min),
                addTimePendingIntent
            )
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID_SESSION_PROGRESS, notification)
        } catch (_: SecurityException) {}
    }

    fun cancelRemainingTimeNotification() {
        notificationManager.cancel(NOTIFICATION_ID_SESSION_PROGRESS)
    }

    private fun createOpenOverlayPendingIntent(): PendingIntent {
        val intent = Intent(context, BlockOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, 101, intent, flags)
    }

    private fun createAdd5MinPendingIntent(): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ADD_5_MINUTES
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 102, intent, flags)
    }

    companion object {
        const val CHANNEL_ID = "taglock_focus_notifications"
        const val NOTIFICATION_ID_LOCK_STATUS = 1001
        const val NOTIFICATION_ID_SESSION_PROGRESS = 1002

        @Volatile
        private var instance: TagLockNotificationManager? = null

        fun getInstance(context: Context): TagLockNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: TagLockNotificationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
