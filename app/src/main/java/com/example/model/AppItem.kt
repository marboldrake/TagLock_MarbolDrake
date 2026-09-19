package com.example.model

import android.graphics.drawable.Drawable

/**
 * Representa una aplicación instalada en el dispositivo para la lista de selección.
 */
data class AppItem(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    var isBlocked: Boolean = false
)
