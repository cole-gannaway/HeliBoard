// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceinput

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

enum class VoiceInputState {
    IDLE, RECORDING, TRANSCRIBING,
}

/**
 * Records microphone audio to a local WAV file and uploads it to a user-configured
 * OpenAI-compatible transcription endpoint via a single (non-streaming) multipart POST.
 */
class VoiceInputManager private constructor() {
    private lateinit var appContext: Context

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // written from a background dispatcher (see stopRecordingAndTranscribe) and read from the main thread
    @Volatile
    var state: VoiceInputState = VoiceInputState.IDLE
        private set

    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false
    private var audioFile: File? = null

    private fun initInternal(context: Context) {
        appContext = context.applicationContext
    }

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    // permission is checked explicitly above via hasRecordAudioPermission(), lint can't see across the function call
    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (state != VoiceInputState.IDLE || !hasRecordAudioPermission()) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) return
        val bufferSize = minBufferSize * 2

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return
        }

        val file = File(appContext.cacheDir, "voice_input_${System.currentTimeMillis()}.wav")
        audioFile = file
        val writer = WavFileWriter(file, SAMPLE_RATE, CHANNEL_COUNT)

        isRecording = true
        state = VoiceInputState.RECORDING
        audioRecord.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    writer.write(buffer, read)
                }
            }
            audioRecord.stop()
            audioRecord.release()
            writer.close()
        }.apply { start() }
    }

    /**
     * Stops the active recording and uploads it for transcription. [onSuccess]/[onFailure] are
     * always invoked on the main thread so callers can safely commit text to the editor from them.
     */
    fun stopRecordingAndTranscribe(onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit) {
        if (state != VoiceInputState.RECORDING) return
        isRecording = false
        state = VoiceInputState.TRANSCRIBING
        val file = audioFile
        audioFile = null
        val threadToJoin = recordingThread
        recordingThread = null

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                threadToJoin?.join()
                if (file != null) transcribe(file) else Result.failure(IllegalStateException("No recording found"))
            }
            state = VoiceInputState.IDLE
            withContext(Dispatchers.Main) {
                result.fold(onSuccess, onFailure)
            }
        }
    }

    fun cancelRecording() {
        if (state != VoiceInputState.RECORDING) return
        isRecording = false
        recordingThread?.join()
        recordingThread = null
        audioFile?.delete()
        audioFile = null
        state = VoiceInputState.IDLE
    }

    private fun transcribe(file: File): Result<String> {
        return try {
            val endpointUrl = appContext.prefs().getString(
                Settings.PREF_VOICE_INPUT_ENDPOINT_URL, Defaults.PREF_VOICE_INPUT_ENDPOINT_URL,
            )
            if (endpointUrl.isNullOrBlank()) {
                return Result.failure(IllegalStateException("No transcription endpoint configured"))
            }
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder()
                .url(endpointUrl)
                .post(requestBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("Server returned HTTP ${response.code}"))
                }
                val body = response.body.string()
                val transcription = Json.decodeFromString<TranscriptionResponse>(body)
                Result.success(transcription.text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Voice input transcription request failed", e)
            Result.failure(e)
        } finally {
            file.delete()
        }
    }

    @Serializable
    private data class TranscriptionResponse(val text: String)

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_COUNT = 1
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private val instance = VoiceInputManager()

        @JvmStatic
        fun getInstance(): VoiceInputManager = instance

        @JvmStatic
        fun init(context: Context) {
            instance.initInternal(context)
        }
    }
}
