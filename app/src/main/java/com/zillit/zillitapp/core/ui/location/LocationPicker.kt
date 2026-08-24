package com.zillit.zillitapp.core.ui.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Where something happens: what people read, and where a maps link should send them. */
data class PickedLocation(
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasPoint: Boolean get() = latitude != null && longitude != null
    val isEmpty: Boolean get() = address.isBlank() && !hasPoint
}

/**
 * The app's one location picker.
 *
 * Every tool that stores a place — an event, a recce stop, a shoot location — picks it the
 * same way, so this lives in `core` and takes only a starting point and a callback. There
 * is no calendar-specific copy of it, and there should never be one.
 *
 * The interaction is v2's: the map moves under a **fixed centre pin** rather than dropping
 * a marker where you tap. Panning to a spot is more forgiving than hitting it with a
 * fingertip, and the centre of the screen is never under the user's hand. Whatever the
 * camera settles on is reverse-geocoded into the address shown at the bottom; searching a
 * place flies the camera there instead.
 */
@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun LocationPickerScreen(
    initial: PickedLocation,
    onConfirm: (PickedLocation) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationPickerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var address by rememberSaveable { mutableStateOf(initial.address) }
    // A picked point survives rotation: finding a spot on a map again is far more work than
    // retyping a line of text.
    var picked by rememberSaveable(saver = latLngSaver) {
        mutableStateOf(initial.takeIf { it.hasPoint }?.let { LatLng(it.latitude!!, it.longitude!!) })
    }
    var message by rememberSaveable { mutableStateOf<Int?>(null) }
    // Nothing is picked until the user does something. Opening the map over a default view
    // and reading its centre back as an address would put a place nobody chose — a point in
    // West Africa, on a world view — into the field.
    var touched by rememberSaveable { mutableStateOf(initial.hasPoint) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            picked ?: DEFAULT_CENTER,
            if (picked != null) PLACE_ZOOM else WORLD_ZOOM,
        )
    }

    var locationGranted by rememberSaveable { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        locationGranted = granted
        // Denied is not a dead end — the map still pans, so say so rather than blocking.
        message = if (granted) null else R.string.map_picker_permission_denied
    }

    val searchAvailable = viewModel.ensurePlaces(context)
    val searchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val place = Autocomplete.getPlaceFromIntent(data)
                val point = place.location
                if (point != null) {
                    touched = true
                    picked = point
                    address = place.formattedAddress?.takeIf { it.isNotBlank() }
                        ?: place.displayName.orEmpty()
                    scope.launch {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngZoom(point, PLACE_ZOOM),
                        )
                    }
                }
            }
            // A cancelled search is the normal way out of the widget; only a real error
            // is worth telling anyone about.
            android.app.Activity.RESULT_CANCELED -> Unit
            else -> ZillitLog.w(
                TAG,
                "Place search failed: ${Autocomplete.getStatusFromIntent(data).statusMessage}",
            )
        }
    }

    // Geocoding follows the camera, but only once it has settled — a lookup per frame while
    // someone is still dragging is wasted work and a flickering address.
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.isMoving to cameraPositionState.position.target }
            .debounce(SETTLE_MS)
            .distinctUntilChanged()
            .collect { (moving, target) ->
                if (moving || !touched) return@collect
                picked = target
                address = context.addressAt(target) ?: target.asCoordinates()
            }
    }

    // A drag counts as choosing: from the first gesture the centre pin means something, and
    // everything the camera settles on after it is a pick.
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.cameraMoveStartedReason }
            .collect { reason ->
                if (reason == CameraMoveStartedReason.GESTURE) touched = true
            }
    }

    Scaffold(
        modifier = modifier,
        containerColor = ZillitTheme.colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.map_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ZillitTheme.colors.surface,
                    titleContentColor = ZillitTheme.colors.textPrimary,
                    navigationIconContentColor = ZillitTheme.colors.textPrimary,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchRow(
                enabled = searchAvailable,
                onClick = {
                    searchLauncher.launch(
                        Autocomplete.IntentBuilder(
                            AutocompleteActivityMode.OVERLAY,
                            listOf(Place.Field.LOCATION, Place.Field.FORMATTED_ADDRESS, Place.Field.DISPLAY_NAME),
                        ).build(context),
                    )
                },
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = locationGranted),
                    // The built-in button is replaced below so it can ask for permission
                    // rather than simply not being there.
                    uiSettings = MapUiSettings(
                        myLocationButtonEnabled = false,
                        zoomControlsEnabled = false,
                        mapToolbarEnabled = false,
                    ),
                )

                // The pin sits over the centre of the map, lifted by half its height so its
                // tip — not its middle — marks the point the camera is on.
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = stringResource(R.string.map_picker_pin_desc),
                    tint = ZillitTheme.colors.brand,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = -(PIN_SIZE / 2))
                        .size(PIN_SIZE),
                )

                FloatingActionButton(
                    onClick = {
                        if (locationGranted) {
                            touched = true
                            scope.launch {
                                val moved = context.moveToCurrentLocation(cameraPositionState)
                                message = if (moved) null else R.string.map_picker_no_fix
                            }
                        } else {
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    containerColor = ZillitTheme.colors.surface,
                    contentColor = ZillitTheme.colors.brand,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(ZillitTheme.spacing.lg),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MyLocation,
                        contentDescription = stringResource(R.string.map_picker_my_location),
                    )
                }
            }

            HorizontalDivider(color = ZillitTheme.colors.divider)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = address.ifBlank { stringResource(R.string.map_picker_default_hint) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (address.isBlank()) {
                            ZillitTheme.colors.textTertiary
                        } else {
                            ZillitTheme.colors.textPrimary
                        },
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    if (address.isNotBlank() || picked != null) {
                        TextButton(onClick = { onConfirm(PickedLocation()) }) {
                            Text(
                                text = stringResource(R.string.map_picker_clear),
                                style = MaterialTheme.typography.labelLarge,
                                color = ZillitTheme.colors.brand,
                            )
                        }
                    }
                }

                message?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }

                PrimaryButton(
                    text = stringResource(R.string.map_picker_confirm),
                    onClick = {
                        onConfirm(
                            PickedLocation(
                                address = address.trim(),
                                latitude = picked?.latitude,
                                longitude = picked?.longitude,
                            ),
                        )
                    },
                    enabled = picked != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The search entry.
 *
 * Not a text field: the Places widget owns its own input, and a field that silently does
 * nothing when the key is missing would be worse than one that says so.
 */
@Composable
private fun SearchRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target; a search icon is not something to aim at.
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(ZillitTheme.spacing.lg),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
        )
        Text(
            text = stringResource(
                if (enabled) R.string.map_picker_search_hint else R.string.map_picker_search_unavailable,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textTertiary,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = ZillitTheme.colors.divider)
}

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The address for a point, or null when the device cannot say.
 *
 * Off the main thread: Geocoder does network I/O, and the blocking overload is the only one
 * available below API 33.
 */
private suspend fun Context.addressAt(point: LatLng): String? = withContext(Dispatchers.IO) {
    runCatching {
        @Suppress("DEPRECATION")
        Geocoder(this@addressAt)
            .getFromLocation(point.latitude, point.longitude, 1)
            ?.firstOrNull()
            ?.getAddressLine(0)
            ?.takeIf { it.isNotBlank() }
    }.onFailure { ZillitLog.w(TAG, "Reverse geocode failed: ${it.message}") }.getOrNull()
}

/** What to show when nothing can name the spot — still precise, still copyable. */
private fun LatLng.asCoordinates(): String =
    String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

/**
 * Flies the camera to the device's own position.
 *
 * Reads the map's location layer rather than pulling in a second location client: the layer
 * is already enabled to draw the blue dot, and one source of truth avoids the two disagreeing.
 */
private suspend fun Context.moveToCurrentLocation(
    cameraPositionState: com.google.maps.android.compose.CameraPositionState,
): Boolean {
    val fix = runCatching {
        val manager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        @Suppress("MissingPermission")
        manager.getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    }.getOrNull() ?: return false

    cameraPositionState.animate(
        CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), PLACE_ZOOM),
    )
    return true
}

private val latLngSaver = listSaver<MutableState<LatLng?>, Double>(
    save = { it.value?.let { p -> listOf(p.latitude, p.longitude) } ?: emptyList() },
    restore = { mutableStateOf(it.takeIf { v -> v.size == 2 }?.let { v -> LatLng(v[0], v[1]) }) },
)

private const val TAG = "LocationPicker"

/** A wide view of the world; anywhere specific would be a guess about the user. */
private val DEFAULT_CENTER = LatLng(20.0, 0.0)
private const val WORLD_ZOOM = 2f
private const val PLACE_ZOOM = 15f
private const val SETTLE_MS = 400L
private val PIN_SIZE = 48.dp
