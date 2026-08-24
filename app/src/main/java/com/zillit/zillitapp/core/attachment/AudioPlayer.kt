package com.zillit.zillitapp.core.attachment

import android.media.MediaPlayer
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which clip is playing and where it has got to.
 *
 * @param id the message id, so a bubble can ask "is it me?" without comparing file paths.
 */
data class PlaybackState(
    val id: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    fun progressFor(messageId: String): Float =
        if (id != messageId || durationMs <= 0) 0f
        else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun isPlaying(messageId: String): Boolean = id == messageId && isPlaying
}

/**
 * Plays voice notes, one at a time.
 *
 * A single shared player rather than one per bubble: two voice notes talking over each
 * other is never what anyone wants, and a `MediaPlayer` per row leaks native resources as
 * the thread scrolls. Starting a new clip stops the previous one, which is also what makes
 * the play/pause icons across the thread consistent without any of them coordinating.
 *
 * Plays from a **local file only** — the caller downloads through `AttachmentDownloader`
 * first, so playback never stalls on a network read mid-sentence.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** Starts [file], or pauses it if the same clip is already playing. */
    fun toggle(id: String, file: File) {
        if (!file.exists() || file.length() == 0L) {
            ZillitLog.w(TAG, "Cannot play missing file for $id")
            return
        }

        if (_state.value.id == id && player != null) {
            if (_state.value.isPlaying) pause() else resume()
            return
        }

        play(id, file)
    }

    private fun play(id: String, file: File) {
        release()

        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    // Reset to the start rather than leaving it parked at the end, so the
                    // next tap replays instead of doing nothing.
                    _state.value = PlaybackState(
                        id = id,
                        isPlaying = false,
                        positionMs = 0,
                        durationMs = duration.toLong(),
                    )
                    stopTicker()
                }
            }

            _state.value = PlaybackState(
                id = id,
                isPlaying = true,
                positionMs = 0,
                durationMs = player?.duration?.toLong() ?: 0,
            )
            startTicker()
        }.onFailure {
            ZillitLog.w(TAG, "Playback failed for $id: ${it.message}")
            release()
            _state.value = PlaybackState()
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        _state.value = _state.value.copy(isPlaying = false)
        stopTicker()
    }

    private fun resume() {
        runCatching { player?.start() }
        _state.value = _state.value.copy(isPlaying = true)
        startTicker()
    }

    fun seekTo(fraction: Float) {
        val active = player ?: return
        val target = (active.duration * fraction.coerceIn(0f, 1f)).toInt()
        runCatching { active.seekTo(target) }
        _state.value = _state.value.copy(positionMs = target.toLong())
    }

    fun stop() {
        release()
        _state.value = PlaybackState()
    }

    /** Polls position; MediaPlayer has no progress callback of its own. */
    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (player?.isPlaying == true) {
                _state.value = _state.value.copy(
                    positionMs = player?.currentPosition?.toLong() ?: 0,
                )
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun release() {
        stopTicker()
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

    private companion object {
        const val TAG = "AudioPlayer"

        /** Smooth enough for a progress bar without waking the CPU pointlessly. */
        const val TICK_MS = 100L
    }
}
