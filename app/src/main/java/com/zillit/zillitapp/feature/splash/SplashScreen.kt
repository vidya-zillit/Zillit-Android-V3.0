package com.zillit.zillitapp.feature.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashRoute(
    onReady: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    LaunchedEffect(destination) {
        if (destination is SplashDestination.ProjectList) {
            // Let the entrance animation land before navigating away. Startup work often
            // finishes in well under this, and cutting mid-animation reads as a glitch
            // rather than as speed.
            delay(MIN_VISIBLE_MILLIS)
            onReady()
        }
    }

    SplashScreen()
}

/**
 * Branded splash shown while startup work finishes.
 *
 * The entrance is staggered rather than everything appearing at once: the mark springs in
 * first, then the wordmark rises and fades beneath it. A slow sheen sweeps across the mark
 * on a loop so the screen still reads as "working" if startup takes a moment on a cold
 * start or a slow network, without a spinner.
 *
 * All of it is driven by `graphicsLayer`/`Canvas` properties — alpha, scale, translation —
 * so each frame is a GPU-side transform and nothing re-lays-out during the animation.
 */
@Composable
fun SplashScreen() {
    val markScale = remember { Animatable(INITIAL_MARK_SCALE) }
    val markAlpha = remember { Animatable(0f) }
    val wordmarkAlpha = remember { Animatable(0f) }
    val wordmarkOffset = remember { Animatable(WORDMARK_RISE_PX) }

    LaunchedEffect(Unit) {
        launch {
            markAlpha.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        }
        launch {
            // A low-stiffness spring gives a slight overshoot, so the mark settles rather
            // than snapping to its final size.
            markScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }
        launch {
            delay(WORDMARK_DELAY_MILLIS)
            launch { wordmarkAlpha.animateTo(1f, tween(durationMillis = 320)) }
            wordmarkOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
            )
        }
    }

    // Looping sheen — the "still working" cue.
    val infiniteTransition = rememberInfiniteTransition(label = "splash-sheen")
    val sheenProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "splash-sheen-progress",
    )

    // Read outside the Canvas: its draw lambda is not a composable scope, so theme
    // lookups have to be hoisted.
    val sheenColor = ZillitTheme.colors.textOnBrand

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Box(
                modifier = Modifier
                    .size(MARK_SIZE)
                    .scale(markScale.value)
                    .alpha(markAlpha.value)
                    .background(
                        color = ZillitTheme.colors.brand,
                        shape = RoundedCornerShape(ZillitTheme.shapes.large),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.splash_logo_mark),
                    style = MaterialTheme.typography.displaySmall,
                    color = ZillitTheme.colors.textOnBrand,
                )

                // The sheen: a soft diagonal highlight travelling left to right, clipped
                // to the mark by the parent's shape.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val sweepWidth = size.width * SHEEN_WIDTH_FRACTION
                    val travel = size.width + sweepWidth
                    val start = -sweepWidth + travel * sheenProgress

                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                sheenColor.copy(alpha = 0f),
                                sheenColor.copy(alpha = SHEEN_PEAK_ALPHA),
                                sheenColor.copy(alpha = 0f),
                            ),
                            start = Offset(start, 0f),
                            end = Offset(start + sweepWidth, size.height),
                        ),
                    )
                }
            }

            Text(
                text = stringResource(R.string.splash_wordmark),
                style = MaterialTheme.typography.headlineMedium,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.graphicsLayer {
                    alpha = wordmarkAlpha.value
                    translationY = wordmarkOffset.value
                },
            )
        }
    }
}

private val MARK_SIZE = 96.dp

/** Starts slightly small so the spring reads as growing into place. */
private const val INITIAL_MARK_SCALE = 0.6f

/** How far the wordmark rises, in px. */
private const val WORDMARK_RISE_PX = 40f

private const val WORDMARK_DELAY_MILLIS = 160L

/** Entrance runs ~700ms; hold slightly past it so navigation never cuts it short. */
private const val MIN_VISIBLE_MILLIS = 900L

private const val SHEEN_WIDTH_FRACTION = 0.45f
private const val SHEEN_PEAK_ALPHA = 0.28f
