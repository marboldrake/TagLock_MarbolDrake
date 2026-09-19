package com.example.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Implementación simulada (Mock/Dummy) de [AppBlockerController] para la Fase 1.
 *
 * Permite que toda la UI y navegación funcionen al 100%, guardando el estado
 * y las apps bloqueadas en SharedPreferences. En la Fase 2, esta clase puede
 * ser extendida o reemplazada por un AccessibilityAppBlockerController sin
 * necesidad de reescribir la capa visual.
 */
class MockAppBlockerController private constructor(context: Context) : AppBlockerController {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lockListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val packageListeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    override fun lock() {
        lockWithCurrentConfig()
    }

    override fun lockWithCurrentConfig() {
        val mode = getLockMode()
        if (mode == LockMode.TIMER) {
            val minutes = getConfiguredTimerMinutes()
            lockWithDuration(minutes)
        } else {
            // Modos sin tiempo (NFC_ONLY o EMERGENCY_ONLY)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putBoolean(KEY_IS_LOCKED, true)
                .putLong(KEY_LOCK_START_TIME, now)
                .putLong(KEY_LOCK_DURATION_MS, -1L) // -1L representa duración infinita / sin tiempo
                .apply()
            notifyLockState(true)
        }
    }

    override fun getLockMode(): LockMode {
        val modeName = prefs.getString(KEY_LOCK_MODE, LockMode.TIMER.name)
        return try {
            LockMode.valueOf(modeName ?: LockMode.TIMER.name)
        } catch (_: Exception) {
            LockMode.TIMER
        }
    }

    override fun setLockMode(mode: LockMode) {
        prefs.edit().putString(KEY_LOCK_MODE, mode.name).apply()
    }

    override fun getConfiguredTimerMinutes(): Int {
        return prefs.getInt(KEY_CONFIGURED_TIMER_MINUTES, DEFAULT_DURATION_MINUTES)
    }

    override fun setConfiguredTimerMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_CONFIGURED_TIMER_MINUTES, minutes.coerceAtLeast(1)).apply()
    }

    override fun lockWithDuration(durationMinutes: Int) {
        val now = System.currentTimeMillis()
        val durationMs = durationMinutes * 60 * 1000L
        prefs.edit()
            .putBoolean(KEY_IS_LOCKED, true)
            .putLong(KEY_LOCK_START_TIME, now)
            .putLong(KEY_LOCK_DURATION_MS, durationMs)
            .apply()
        notifyLockState(true)
    }

    override fun unlock() {
        prefs.edit()
            .putBoolean(KEY_IS_LOCKED, false)
            .putLong(KEY_LOCK_START_TIME, 0L)
            .apply()
        notifyLockState(false)
    }

    override fun isLocked(): Boolean {
        val locked = prefs.getBoolean(KEY_IS_LOCKED, false)
        if (!locked) return false

        val duration = getSessionDurationMillis()
        if (duration < 0) {
            // Modo sin tiempo (indefinido hasta tarjeta NFC o botón emergencia)
            return true
        }

        // Verificar si la sesión ya expiró para el modo con temporizador
        val remaining = getSessionRemainingMillis()
        if (remaining <= 0) {
            unlock()
            return false
        }
        return true
    }

    override fun isPackageBlocked(packageName: String): Boolean {
        if (!isLocked()) return false
        return getBlockedPackages().contains(packageName)
    }

    override fun getSessionStartTime(): Long {
        return prefs.getLong(KEY_LOCK_START_TIME, 0L)
    }

    override fun getSessionDurationMillis(): Long {
        return prefs.getLong(KEY_LOCK_DURATION_MS, DEFAULT_DURATION_MINUTES * 60 * 1000L)
    }

    override fun getSessionRemainingMillis(): Long {
        if (!prefs.getBoolean(KEY_IS_LOCKED, false)) return 0L
        val duration = getSessionDurationMillis()
        if (duration < 0) {
            // Sin tiempo
            return -1L
        }
        val start = getSessionStartTime()
        val elapsed = System.currentTimeMillis() - start
        val remaining = duration - elapsed
        return if (remaining > 0) remaining else 0L
    }

    override fun extendSessionMinutes(extraMinutes: Int): Boolean {
        if (!isLocked()) return false
        val currentDuration = getSessionDurationMillis()
        if (currentDuration < 0) return false // No aplica para sesiones sin tiempo
        if (extraMinutes <= 0) return false // Solo permitir aumentar tiempo, nunca reducir

        val addedMillis = extraMinutes * 60 * 1000L
        val newDuration = currentDuration + addedMillis

        prefs.edit().putLong(KEY_LOCK_DURATION_MS, newDuration).apply()
        notifyLockState(true)
        return true
    }

    override fun getBlockedPackages(): Set<String> {
        return prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    override fun setBlockedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, HashSet(packages)).apply()
        notifyBlockedPackages(packages)
    }

    override fun addBlockedPackage(packageName: String) {
        val current = getBlockedPackages().toMutableSet()
        if (current.add(packageName)) {
            setBlockedPackages(current)
        }
    }

    override fun removeBlockedPackage(packageName: String) {
        val current = getBlockedPackages().toMutableSet()
        if (current.remove(packageName)) {
            setBlockedPackages(current)
        }
    }

    override fun addLockStateListener(listener: (Boolean) -> Unit) {
        if (!lockListeners.contains(listener)) {
            lockListeners.add(listener)
            // Emitir estado actual inmediatamente al registrarse
            listener(isLocked())
        }
    }

    override fun removeLockStateListener(listener: (Boolean) -> Unit) {
        lockListeners.remove(listener)
    }

    override fun addBlockedPackagesListener(listener: (Set<String>) -> Unit) {
        if (!packageListeners.contains(listener)) {
            packageListeners.add(listener)
            // Emitir conjunto actual inmediatamente al registrarse
            listener(getBlockedPackages())
        }
    }

    override fun removeBlockedPackagesListener(listener: (Set<String>) -> Unit) {
        packageListeners.remove(listener)
    }

    private fun notifyLockState(isLocked: Boolean) {
        for (listener in lockListeners) {
            listener(isLocked)
        }
    }

    private fun notifyBlockedPackages(packages: Set<String>) {
        for (listener in packageListeners) {
            listener(packages)
        }
    }

    companion object {
        private const val PREFS_NAME = "focusblock_mock_prefs"
        private const val KEY_IS_LOCKED = "pref_is_locked"
        private const val KEY_BLOCKED_PACKAGES = "pref_blocked_packages"
        private const val KEY_LOCK_START_TIME = "pref_lock_start_time"
        private const val KEY_LOCK_DURATION_MS = "pref_lock_duration_ms"
        private const val KEY_LOCK_MODE = "pref_lock_mode"
        private const val KEY_CONFIGURED_TIMER_MINUTES = "pref_configured_timer_minutes"
        const val DEFAULT_DURATION_MINUTES = 30

        @Volatile
        private var instance: MockAppBlockerController? = null

        fun getInstance(context: Context): MockAppBlockerController {
            return instance ?: synchronized(this) {
                instance ?: MockAppBlockerController(context).also { instance = it }
            }
        }
    }
}
