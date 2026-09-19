package com.example.util

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.nio.charset.StandardCharsets

/**
 * Utilidades para lectura y escritura de mensajes NDEF en Tags NFC.
 * Escribe:
 * 1. Clave secreta (External Record o Text Record de FocusBlock)
 * 2. Android Application Record (AAR) con el applicationId de FocusBlock
 *    para que el sistema operativo abra directamente FocusBlock al escanearlo.
 */
object NfcTagHelper {

    const val MIME_FOCUSBLOCK_SECRET = "application/vnd.com.example.focusblock.key"
    const val DOMAIN_TYPE_FOCUSBLOCK = "com.example:focusblock_key"

    /**
     * Extrae el UID del tag en formato hexadecimal (ej. "04:3A:F1:C2:59").
     */
    fun getTagUid(tag: Tag): String {
        val id = tag.id ?: return "DESCONOCIDO"
        return id.joinToString(separator = ":") { "%02X".format(it) }
    }

    /**
     * Intenta leer el payload o clave secreta guardada en un Tag NDEF.
     */
    fun readSecretFromTag(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        try {
            ndef.connect()
            val ndefMessage = ndef.cachedNdefMessage ?: ndef.ndefMessage ?: return null
            for (record in ndefMessage.records) {
                // Verificar si es nuestro MIME type o external type
                val typeStr = String(record.type, StandardCharsets.US_ASCII)
                if (typeStr.contains("focusblock", ignoreCase = true)) {
                    return String(record.payload, StandardCharsets.UTF_8)
                }
            }
        } catch (_: Exception) {
            // Error de lectura
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Escribe la clave secreta y el AAR (Android Application Record) en la tarjeta NFC.
     * Al incluir createApplicationRecord(appPackageName), cualquier escaneo del tag en Android
     * abrirá automáticamente FocusBlock de inmediato.
     */
    fun writeTagWithSecretAndAar(
        tag: Tag,
        secretKey: String,
        appPackageName: String
    ): Result<Unit> {
        val secretRecord = NdefRecord.createMime(
            MIME_FOCUSBLOCK_SECRET,
            secretKey.toByteArray(StandardCharsets.UTF_8)
        )
        val aarRecord = NdefRecord.createApplicationRecord(appPackageName)

        val message = NdefMessage(arrayOf(secretRecord, aarRecord))
        val messageSize = message.toByteArray().size

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) {
                    return Result.failure(IllegalStateException("El tag NFC es de solo lectura y no se puede reescribir."))
                }
                if (ndef.maxSize < messageSize) {
                    return Result.failure(IllegalStateException("El tag NFC no tiene suficiente memoria (${ndef.maxSize} bytes disponibles, requiere $messageSize)."))
                }
                ndef.writeNdefMessage(message)
                return Result.success(Unit)
            } catch (e: Exception) {
                return Result.failure(e)
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        } else {
            // Intentar formatear si el tag es NdefFormatable
            val formatable = NdefFormatable.get(tag)
            if (formatable != null) {
                try {
                    formatable.connect()
                    formatable.format(message)
                    return Result.success(Unit)
                } catch (e: Exception) {
                    return Result.failure(e)
                } finally {
                    try { formatable.close() } catch (_: Exception) {}
                }
            } else {
                return Result.failure(IllegalStateException("Este tag NFC no es compatible con el formato NDEF."))
            }
        }
    }
}
