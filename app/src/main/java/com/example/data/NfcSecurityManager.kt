package com.example.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Modelo de una tarjeta o tag NFC vinculada/autorizada en FocusBlock.
 */
data class PairedNfcCard(
    val uid: String,
    val name: String,
    val pairedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Gestor para la clave secreta y la vinculación de múltiples tarjetas NFC.
 * Soporta configuración y almacenamiento de más de una tarjeta autorizada
 * (ej. Tarjeta 1, Tarjeta de Oficina, Llavero de Respaldo).
 */
class NfcSecurityManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyTagIfNeeded()
    }

    private fun migrateLegacyTagIfNeeded() {
        val legacyTagId = prefs.getString(KEY_PAIRED_TAG_ID, null)
        val cardsJson = prefs.getString(KEY_PAIRED_CARDS_JSON, null)
        if (!legacyTagId.isNullOrBlank() && cardsJson.isNullOrBlank()) {
            val list = listOf(PairedNfcCard(uid = legacyTagId.trim(), name = "Tarjeta Principal"))
            saveCards(list)
            prefs.edit().remove(KEY_PAIRED_TAG_ID).apply()
        }
    }

    /**
     * Retorna la clave secreta guardada para FocusBlock, o genera una por defecto si está vacía.
     */
    fun getSecretKey(): String {
        var key = prefs.getString(KEY_SECRET_KEY, null)
        if (key.isNullOrBlank()) {
            key = generateDefaultKey()
            setSecretKey(key)
        }
        return key
    }

    /**
     * Actualiza la clave secreta personalizada.
     */
    fun setSecretKey(key: String) {
        prefs.edit().putString(KEY_SECRET_KEY, key.trim()).apply()
    }

    /**
     * Obtiene la lista completa de tarjetas NFC vinculadas.
     */
    fun getPairedCards(): List<PairedNfcCard> {
        val jsonString = prefs.getString(KEY_PAIRED_CARDS_JSON, null) ?: return emptyList()
        val result = mutableListOf<PairedNfcCard>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val uid = obj.optString("uid", "")
                val name = obj.optString("name", "Tarjeta ${i + 1}")
                val pairedAt = obj.optLong("pairedAt", System.currentTimeMillis())
                if (uid.isNotBlank()) {
                    result.add(PairedNfcCard(uid = uid, name = name, pairedAtMillis = pairedAt))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    /**
     * Guarda la lista de tarjetas vinculadas.
     */
    private fun saveCards(cards: List<PairedNfcCard>) {
        val jsonArray = JSONArray()
        for (card in cards) {
            val obj = JSONObject().apply {
                put("uid", card.uid)
                put("name", card.name)
                put("pairedAt", card.pairedAtMillis)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_PAIRED_CARDS_JSON, jsonArray.toString()).apply()
    }

    /**
     * Vincula una nueva tarjeta o actualiza una existente con el UID proporcionado.
     */
    fun addPairedCard(uid: String, customName: String? = null): PairedNfcCard {
        val cleanUid = uid.trim().uppercase()
        val currentCards = getPairedCards().toMutableList()

        val existingIndex = currentCards.indexOfFirst { it.uid.equals(cleanUid, ignoreCase = true) }
        val cardName = customName?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (existingIndex >= 0) currentCards[existingIndex].name else "Tarjeta ${currentCards.size + 1}"

        val newCard = PairedNfcCard(uid = cleanUid, name = cardName, pairedAtMillis = System.currentTimeMillis())

        if (existingIndex >= 0) {
            currentCards[existingIndex] = newCard
        } else {
            currentCards.add(newCard)
        }

        saveCards(currentCards)
        return newCard
    }

    /**
     * Elimina una tarjeta vinculada por su UID.
     */
    fun removePairedCard(uid: String): Boolean {
        val currentCards = getPairedCards().toMutableList()
        val removed = currentCards.removeAll { it.uid.equals(uid.trim(), ignoreCase = true) }
        if (removed) {
            saveCards(currentCards)
        }
        return removed
    }

    /**
     * Elimina todas las tarjetas vinculadas.
     */
    fun clearAllCards() {
        prefs.edit().remove(KEY_PAIRED_CARDS_JSON).apply()
    }

    /**
     * Verifica si existe al menos una tarjeta vinculada.
     */
    fun hasPairedCards(): Boolean {
        return getPairedCards().isNotEmpty()
    }

    /**
     * Alias compatible con versiones previas.
     */
    fun hasPairedTag(): Boolean {
        return hasPairedCards()
    }

    /**
     * Retorna el UID de la primera tarjeta vinculada o null.
     */
    fun getPairedTagId(): String? {
        return getPairedCards().firstOrNull()?.uid
    }

    /**
     * Vincula o desvincula (si es null) una tarjeta.
     */
    fun setPairedTagId(tagId: String?) {
        if (tagId == null) {
            clearAllCards()
        } else {
            addPairedCard(tagId)
        }
    }

    /**
     * Valida si un Tag escaneado coincide con CUALQUIERA de las tarjetas autorizadas.
     */
    fun isTagAuthorized(scannedTagId: String, scannedSecret: String? = null): Boolean {
        val cards = getPairedCards()
        if (cards.isEmpty()) {
            return false
        }

        val cleanScanned = scannedTagId.trim().uppercase()
        val cleanScannedNoColon = cleanScanned.replace(":", "")

        val matchesAnyCard = cards.any { card ->
            val cardUid = card.uid.trim().uppercase()
            cardUid == cleanScanned || cardUid.replace(":", "") == cleanScannedNoColon
        }

        if (!matchesAnyCard) return false

        // Si se leyó un secret del tag NDEF, verificarlo también si está presente
        if (scannedSecret != null) {
            val expectedKey = getSecretKey()
            return scannedSecret == expectedKey
        }

        return true
    }

    private fun generateDefaultKey(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..10)
            .map { chars.random() }
            .joinToString("")
    }

    companion object {
        private const val PREFS_NAME = "focusblock_nfc_security"
        private const val KEY_SECRET_KEY = "pref_nfc_secret_key"
        private const val KEY_PAIRED_TAG_ID = "pref_paired_tag_id"
        private const val KEY_PAIRED_CARDS_JSON = "pref_paired_cards_json"

        @Volatile
        private var instance: NfcSecurityManager? = null

        fun getInstance(context: Context): NfcSecurityManager {
            return instance ?: synchronized(this) {
                instance ?: NfcSecurityManager(context).also { instance = it }
            }
        }
    }
}
