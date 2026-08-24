package com.zillit.zillitapp.core.attachment

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.zillit.zillitapp.core.logging.ZillitLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where a recording has got to. */
sealed interface RecordingState {
    data object Idle : RecordingState

    /**
     * @param amplitude 0f..1f, sampled from the mic so the UI can show a live level. A
     *   recorder with no visible response looks broken, and people speak into a dead mic
     *   for a full take before noticing.
     */
    data class Recording(val elapsedMs: Long, val amplitude: Float) : RecordingState

    data class Finished(val file: File, val durationMs: Long) : RecordingState

    data class Failed(val reason: String) : RecordingState
}

/**
 * Voice notes.
 *
 * Records to **M4A/AAC** rather than the 3GP/AMR that `MediaRecorder`'s defaults produce:
 * AMR is narrowband and sounds like a phone call from 2005, and iOS and the web player
 * both handle M4A natively.
 *
 * The output goes into the same staging directory as picked files, so the upload path
 * afterwards is identical to any other attachment — a voice note is not a special case
 * once it exists.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0

    private val stagingDir: File
        get() = File(context.cacheDir, "attachments").apply { mkdirs() }

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (isRecording) return false

        val target = File(stagingDir, "voice_${System.currentTimeMillis()}.m4a")

        return runCatching {
            val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            instance.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // Voice, not music: 44.1kHz mono at 64kbps is transparent for speech and
                // keeps a five-minute note under 2.5MB on set wifi.
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioChannels(1)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }

            recorder = instance
            outputFile = target
            startedAt = System.currentTimeMillis()
            _state.value = RecordingState.Recording(0, 0f)
            true
        }.onFailure {
            ZillitLog.w(TAG, "Could not start recording: ${it.message}")
            release()
            _state.value = RecordingState.Failed(it.localizedMessage ?: "Recording failed")
        }.getOrDefault(false)
    }

    /** Call on a timer while recording, to drive the level meter and the elapsed counter. */
    fun sample() {
        val active = recorder ?: return
        val amplitude = runCatching { active.maxAmplitude }.getOrDefault(0)
        _state.value = RecordingState.Recording(
            elapsedMs = System.currentTimeMillis() - startedAt,
            // MAX_AMPLITUDE is the theoretical ceiling; normal speech sits far below it,
            // so the meter is scaled to a realistic range or it never visibly moves.
            amplitude = (amplitude / MEANINGFUL_AMPLITUDE.toFloat()).coerceIn(0f, 1f),
        )
    }

    /**
     * Stops and returns the file.
     *
     * @return null when the take was too short to be a message. Under a second is almost
     *   always a mis-tap, and sending it wastes everyone's attention.
     */
    fun stop(): RecordingState.Finished? {
        val active = recorder ?: return null
        val file = outputFile
        val duration = System.currentTimeMillis() - startedAt

        runCatching { active.stop() }.onFailure {
            // stop() throws when nothing was captured at all; the partial file is useless.
            ZillitLog.w(TAG, "Recorder stop failed: ${it.message}")
            file?.delete()
            release()
            _state.value = RecordingState.Idle
            return null
        }

        release()

        if (file == null || !file.exists() || duration < MIN_DURATION_MS) {
            file?.delete()
            _state.value = RecordingState.Idle
            return null
        }

        val finished = RecordingState.Finished(file, duration)
        _state.value = finished
        return finished
    }

    /** Abandons the take and deletes the file. */
    fun cancel() {
        runCatching { recorder?.stop() }
        outputFile?.delete()
        release()
        _state.value = RecordingState.Idle
    }

    fun reset() {
        _state.value = RecordingState.Idle
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
        outputFile = null
    }

    private companion object {
        const val TAG = "AudioRecorder"

        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 64_000

        /** Anything shorter is a mis-tap, not a message. */
        const val MIN_DURATION_MS = 1_000L

        /** Speech peaks well below MediaRecorder's 32767 ceiling. */
        const val MEANINGFUL_AMPLITUDE = 12_000
    }
}
