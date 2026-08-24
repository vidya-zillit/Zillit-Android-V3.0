package com.zillit.zillitapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.core.labels.LocalIdentifiers
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.LocalMessages
import com.zillit.zillitapp.core.ui.components.ConnectivityBanner
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.window.LocalWindowSize
import com.zillit.zillitapp.core.ui.window.toWindowSize
import com.zillit.zillitapp.navigation.ZillitNavHost
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's only Activity.
 *
 * v2 had 408 of them, each with its own lifecycle, back handling and theme wiring. Here
 * screens are composables behind a nav graph, so back behaviour and theming are defined
 * once.
 *
 * Note there is no `android:configChanges="orientation|screenSize"` in the manifest. That
 * flag is the usual shortcut for surviving rotation, and it is deliberately not used: it
 * hides the symptom while leaving state genuinely unsaved when the process is killed in
 * the background. Instead the Activity recreates normally and state is held by ViewModels
 * and `rememberSaveable`, which survives both rotation *and* process death.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() to hand off from the system splash.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val chatFontSize by viewModel.chatFontSize.collectAsStateWithLifecycle()

            // A locale change recreates this activity, so the current configuration is
            // the trigger — no listener to register or leak.
            val locales = androidx.compose.ui.platform.LocalConfiguration.current.locales
            androidx.compose.runtime.LaunchedEffect(locales) {
                viewModel.onDeviceLocaleChanged()
            }
            val labels by viewModel.labels.collectAsStateWithLifecycle()
            val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
            val messages by viewModel.messages.collectAsStateWithLifecycle()
            val identifiers by viewModel.identifiers.collectAsStateWithLifecycle()

            // Recalculated on every configuration change, so a rotate or a split-screen
            // resize re-lays-out the whole tree from one source of truth.
            val windowSize = calculateWindowSizeClass(this).toWindowSize()

            RequestNotificationPermission()

            CompositionLocalProvider(
                LocalWindowSize provides windowSize,
                LocalLabels provides labels,
                LocalMessages provides messages,
                LocalIdentifiers provides identifiers,
            ) {
                ZillitTheme(themeMode = themeMode, fontSize = chatFontSize) {
                    // Banner sits above the nav host, so it spans every screen in every
                    // module and no feature has to remember to add it.
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Inset only at the top: the banner sits under the status bar,
                        // and the nav host below handles its own insets.
                        ConnectivityBanner(
                            isOnline = isOnline,
                            modifier = Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                            ),
                        )
                        ZillitNavHost()
                    }
                }
            }
        }
    }
}

/**
 * Asks for POST_NOTIFICATIONS on Android 13+.
 *
 * Only the tray notification depends on this — badges and stored notifications work
 * without it, because a data push is delivered regardless of the permission. Asked once
 * at launch rather than gated behind a rationale screen, since the app is useless without
 * activity alerts and a Zillit user has already opted into the product.
 */
@androidx.compose.runtime.Composable
private fun RequestNotificationPermission() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial is non-fatal: badges continue to work. */ }

    LaunchedEffect(Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}
