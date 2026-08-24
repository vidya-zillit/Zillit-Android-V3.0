package com.zillit.zillitapp.core.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Reusable camera scanner.
 *
 * This is a **component, not a screen**, because QR scanning appears in more than one
 * place — the project list and Settings' linked-devices flow both need it, and in v2 that
 * meant `BarCodeScanner` was an Activity that each caller launched and then parsed a
 * result from. Here any screen can embed the viewfinder and receive codes directly, and
 * [QrScannerScreen] is just one ready-made host built on top of it.
 *
 * Stack matches v2: CameraX preview + `ImageAnalysis`, decoded by ML Kit barcode scanning.
 *
 * @param onCodeScanned invoked once per detected code. The component de-duplicates
 *        internally — ML Kit reports the same code on every frame it remains visible, so
 *        without this a single QR would fire dozens of callbacks and, in v2's flow, send
 *        dozens of link-device requests.
 */
@Composable
fun QrScannerView(
    onCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Formats to accept. Defaults to QR only. */
    barcodeFormats: Int = Barcode.FORMAT_QR_CODE,
    isPaused: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCodeScanned by rememberUpdatedState(onCodeScanned)

    // Survives recomposition so a code stays de-duplicated while it is on screen.
    val lastScanned = remember { mutableStateOf<String?>(null) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, isPaused) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            provider = providerFuture.get()

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                // Dropping stale frames keeps the scanner responsive; decoding a backlog
                // of frames from a second ago is wasted work.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(analysisExecutor) { imageProxy ->
                        processFrame(
                            imageProxy = imageProxy,
                            formats = barcodeFormats,
                            isPaused = isPaused,
                            lastScanned = lastScanned,
                            onCodeScanned = { code -> currentOnCodeScanned(code) },
                        )
                    }
                }

            runCatching {
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose { provider?.unbindAll() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun processFrame(
    imageProxy: ImageProxy,
    formats: Int,
    isPaused: Boolean,
    lastScanned: androidx.compose.runtime.MutableState<String?>,
    onCodeScanned: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null || isPaused) {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    BarcodeScanning.getClient()
        .process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { it.format and formats != 0 }
                ?.rawValue
                ?.takeIf { it.isNotBlank() && it != lastScanned.value }
                ?.let { code ->
                    lastScanned.value = code
                    onCodeScanned(code)
                }
        }
        // Always close, or the analyser stalls after a handful of frames.
        .addOnCompleteListener { imageProxy.close() }
}

/**
 * Asks for camera permission and reports the outcome.
 *
 * Kept separate from [QrScannerView] so a host screen can render its own explanation
 * while permission is pending, rather than every caller re-implementing the request.
 */
@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var requested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        requested = true
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    return CameraPermissionState(
        isGranted = granted,
        // Only "denied" once the user has actually answered — before that it is pending,
        // and showing a denial message while the dialog is up is wrong.
        isDenied = requested && !granted,
        request = { launcher.launch(Manifest.permission.CAMERA) },
    )
}

data class CameraPermissionState(
    val isGranted: Boolean,
    val isDenied: Boolean,
    val request: () -> Unit,
)

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
