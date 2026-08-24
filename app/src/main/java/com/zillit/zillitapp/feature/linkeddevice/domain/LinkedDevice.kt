package com.zillit.zillitapp.feature.linkeddevice.domain

/** A web or tablet session linked to this account. */
data class LinkedDevice(
    val id: String,
    val deviceName: String,
    val deviceType: String,
    val osVersion: String?,
    val lastActivity: Long,
    val isPrimary: Boolean,
) {
    /** v2's row title: `Android (Pixel 8)`. Kept identical so the list reads the same. */
    val displayName: String
        get() = when {
            deviceType.isBlank() && deviceName.isBlank() -> ""
            deviceName.isBlank() -> deviceType
            deviceType.isBlank() -> deviceName
            else -> "$deviceType ($deviceName)"
        }

    /** Phones get the mobile icon; anything else — web, desktop — gets the laptop one. */
    val isMobile: Boolean
        get() = deviceType.equals("Android", ignoreCase = true) ||
            deviceType.equals("iOS", ignoreCase = true)
}
