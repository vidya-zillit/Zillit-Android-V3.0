package com.zillit.zillitapp.core.ui.location

import android.content.Context
import androidx.lifecycle.ViewModel
import com.google.android.libraries.places.api.Places
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.storage.StorageCredentialsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Booting place search.
 *
 * The Places key is not compiled in — it arrives AES-encrypted from `GET configuration`
 * alongside the S3 and Maps credentials, so it can be rotated server-side. That means
 * search is available only once configuration has been fetched, and the picker has to cope
 * with it being missing rather than assume it is there.
 */
@HiltViewModel
class LocationPickerViewModel @Inject constructor(
    private val storage: StorageCredentialsStore,
) : ViewModel() {

    /**
     * Initialises the Places SDK if it can, and reports whether search is usable.
     *
     * Safe to call on every recomposition: the SDK keeps its own initialised flag and this
     * returns it once set.
     */
    fun ensurePlaces(context: Context): Boolean {
        if (Places.isInitialized()) return true

        val key = storage.configuration.value.googlePlacesSecret
        if (key.isBlank()) return false

        return runCatching {
            Places.initialize(context.applicationContext, key)
            Places.isInitialized()
        }.onFailure { ZillitLog.w(TAG, "Places init failed: ${it.message}") }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "LocationPicker"
    }
}
