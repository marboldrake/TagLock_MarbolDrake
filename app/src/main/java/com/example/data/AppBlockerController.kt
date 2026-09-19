package com.example.data

/**
 * Modos de bloqueo soportados por TagLock:
 * - [TIMER]: Bloqueo con cuenta atrás preestablecida o personalizada (15m, 30m, 60m, custom).
 * - [NFC_ONLY]: Sin tiempo. Únicamente se puede desbloquear acercando la tarjeta/tag NFC autorizada.
 * - [EMERGENCY_ONLY]: Sin tiempo. Únicamente se puede desbloquear con el botón de emergencia de 30 segundos continuos.
 */
enum class LockMode {
    TIMER,
    NFC_ONLY,
    EMERGENCY_ONLY
}

/**
 * Interfaz central para el control del bloqueo de aplicaciones.

 * En la Fase 1, esta interfaz está respaldada por [MockAppBlockerController]
 * permitiendo simular visual y lógicamente el bloqueo, desbloqueo y gestión
 * de paquetes antes de conectar el AccessibilityService y hardware NFC en la Fase 2.
 */
interface AppBlockerController {
    /**
     * Activa el estado de bloqueo enfocado.
     */
    fun lock()

    /**
     * Desactiva el estado de bloqueo y pasa a modo libre.
     */
    fun unlock()

    /**
     * Retorna verdadero si el bloqueo está actualmente activo.
     */
    fun isLocked(): Boolean

    /**
     * Verifica si un paquete específico está en la lista de apps bloqueadas y el bloqueo está activo.
     */
    fun isPackageBlocked(packageName: String): Boolean

    /**
     * Activa el estado de bloqueo especificando una duración en minutos (por defecto 30 minutos).
     */
    fun lockWithDuration(durationMinutes: Int = 30)

    /**
     * Retorna el modo de bloqueo configurado actualmente.
     */
    fun getLockMode(): LockMode

    /**
     * Establece el modo de bloqueo a utilizar (TIMER, NFC_ONLY, EMERGENCY_ONLY).
     */
    fun setLockMode(mode: LockMode)

    /**
     * Retorna la duración configurada en minutos para el modo de temporizador.
     */
    fun getConfiguredTimerMinutes(): Int

    /**
     * Guarda la duración configurada en minutos para el modo temporizador.
     */
    fun setConfiguredTimerMinutes(minutes: Int)

    /**
     * Activa el bloqueo aplicando el modo configurado y duración guardada.
     */
    fun lockWithCurrentConfig()

    /**
     * Retorna el timestamp en ms de cuándo inició la sesión de bloqueo actual.
     */
    fun getSessionStartTime(): Long

    /**
     * Retorna la duración total en milisegundos de la sesión de bloqueo actual.
     */
    fun getSessionDurationMillis(): Long

    /**
     * Retorna los milisegundos restantes de la sesión actual (o 0 si ya terminó o no está bloqueado).
     */
    fun getSessionRemainingMillis(): Long

    /**
     * Incrementa la duración de la sesión actual en minutos (mínimo +1, típicamente +5).
     * Solo permite extender el tiempo, nunca reducirlo.
     */
    fun extendSessionMinutes(extraMinutes: Int = 5): Boolean

    /**
     * Retorna el conjunto inmutable de nombres de paquetes configurados para bloquear.
     */
    fun getBlockedPackages(): Set<String>

    /**
     * Actualiza el conjunto de nombres de paquetes bloqueados.
     */
    fun setBlockedPackages(packages: Set<String>)

    /**
     * Añade un paquete específico a la lista de bloqueo.
     */
    fun addBlockedPackage(packageName: String)

    /**
     * Remueve un paquete específico de la lista de bloqueo.
     */
    fun removeBlockedPackage(packageName: String)

    /**
     * Suscribe un listener que recibe actualizaciones cada vez que cambia el estado (isLocked).
     */
    fun addLockStateListener(listener: (Boolean) -> Unit)

    /**
     * Desuscribe un listener de estado.
     */
    fun removeLockStateListener(listener: (Boolean) -> Unit)

    /**
     * Suscribe un listener que recibe cambios en la lista de paquetes bloqueados.
     */
    fun addBlockedPackagesListener(listener: (Set<String>) -> Unit)

    /**
     * Desuscribe un listener de paquetes bloqueados.
     */
    fun removeBlockedPackagesListener(listener: (Set<String>) -> Unit)
}
