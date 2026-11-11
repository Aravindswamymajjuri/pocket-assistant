package dev.aravind.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.aravind.assistant.policy.BpeTokenizer
import dev.aravind.assistant.policy.VoiceMatch
import dev.aravind.assistant.policy.WakeName
import dev.aravind.assistant.policy.WakeSensitivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

private const val SAMPLE_RATE = 16_000

// Phrases the name check looks at: long enough for "hey" plus a name, short enough to be quick.
private const val NAME_MIN_SAMPLES = SAMPLE_RATE * 2 / 5
private const val NAME_MAX_SAMPLES = SAMPLE_RATE * 4

// A wake word only counts if real speech was heard in the last 1.5 seconds (15 frames of 0.1 s).
private const val WAKE_SPEECH_FRAMES = 15

// Speech must be a little louder than the room's background noise to wake the assistant.
private const val WAKE_OVER_NOISE = 1.3f
private const val WAKE_MIN_LEVEL = 0.003f

// The detector flips quickly. The pause you must leave before a phrase ends is set separately below.
private const val VAD_SILENCE = 0.2f
private const val END_PAUSE_MS = 650
private const val WAKE_PAUSE_MS = 300

/** Voice models ship in the APK and are unpacked to private storage on first use. */
object VoiceAssets {
    private const val VERSION = "10"

    fun dir(context: Context) = File(context.noBackupFilesDir, "voice")

    fun ensure(context: Context) {
        val root = dir(context)
        val marker = File(root, ".version")
        if (marker.exists() && marker.readText() == VERSION) return
        root.deleteRecursively()
        copy(context, "models", root)
        marker.writeText(VERSION)
    }

    private fun copy(context: Context, asset: String, target: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copy(context, "$asset/$it", File(target, it)) }
        }
    }
}

/** Offline engines, loaded only when needed and released when the assistant stops. */
class VoiceEngines(context: Context) {
    private val root = VoiceAssets.dir(context).absolutePath

    val spotter by lazy {
        KeywordSpotter(
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$root/kws/encoder.onnx",
                        decoder = "$root/kws/decoder.onnx",
                        joiner = "$root/kws/joiner.onnx",
                    ),
                    tokens = "$root/kws/tokens.txt",
                    modelType = "zipformer2",
                    numThreads = 1,
                ),
                keywordsFile = "$root/kws/keywords.txt",
                numTrailingBlanks = 1,
                maxActivePaths = 8,
            ),
        )
    }

    /** Wake checks end a phrase after a short pause. Commands wait longer, so they aren't cut off. */
    fun newVad(minSilence: Float = 0.7f): Vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$root/vad/silero_vad.onnx",
                minSilenceDuration = minSilence,
                minSpeechDuration = 0.25f,
                maxSpeechDuration = 14f,
            ),
            sampleRate = SAMPLE_RATE,
        ),
    )

    /** Builds the wake phrase from the name saved in Control Center. */
    fun wakeKeywords(context: Context): String {
        val vocab = File(root, "kws/bpe.vocab")
        val tokenizer = BpeTokenizer.parse(vocab.readLines().asSequence())
        val sensitivity = Features.wakeSensitivity(context)
        return WakeName.keywords(tokenizer, Features.wakeName(context), sensitivity, Features.learnedWakeWords(context))
            ?: requireNotNull(WakeName.keywords(tokenizer, WakeName.DEFAULT, sensitivity))
    }

    /**
     * A small speech model (NeMo Conformer CTC small, 45 MB) used only to check for your assistant's name when
     * the fast wake detector misses it. It can't make up words, so noise gives empty text.
     */
    private val nameModel by lazy {
        OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = "$root/namecheck/model.int8.onnx"),
                    tokens = "$root/namecheck/tokens.txt",
                    numThreads = 2,
                ),
            ),
        )
    }

    private val nameLock = Any()

    fun nameText(samples: FloatArray): String = synchronized(nameLock) {
        val stream = nameModel.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            nameModel.decode(stream)
            return nameModel.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** For teaching the wake word: whether the fast detector catches this recording. */
    fun spotterCatches(context: Context, samples: FloatArray): Boolean {
        val stream = spotter.createStream(wakeKeywords(context))
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
            while (spotter.isReady(stream)) {
                spotter.decode(stream)
                if (spotter.getResult(stream).keyword.isNotBlank()) return true
            }
            return false
        } finally {
            stream.release()
        }
    }

    private val extractor by lazy {
        SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = "$root/speaker/model.onnx", numThreads = 2))
    }

    private fun piper(folder: String) = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$root/$folder/model.onnx",
                    tokens = "$root/$folder/tokens.txt",
                    dataDir = "$root/piper/espeak-ng-data",
                ),
                numThreads = 2,
            ),
        ),
    )

    private val english by lazy { piper("piper") }


    fun embedding(samples: FloatArray): FloatArray? {
        val stream: OnlineStream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    fun synthesize(text: String) = english.generate(text, sid = 0, speed = 1.0f)
}

/** Quiet phone microphones are a common cause of bad recognition, so levels are corrected here. */
object Audio {
    /** Raises a whole phrase to a consistent volume. */
    fun normalized(samples: FloatArray): FloatArray {
        val peak = samples.maxOfOrNull { kotlin.math.abs(it) } ?: return samples
        if (peak < 0.002f) return samples
        val gain = (0.9f / peak).coerceAtMost(30f)
        return FloatArray(samples.size) { samples[it] * gain }
    }

    /** Raises a short frame for the wake word, keeping loud sounds from clipping. */
    fun boosted(frame: FloatArray, gain: Float): FloatArray =
        FloatArray(frame.size) { (frame[it] * gain).coerceIn(-1f, 1f) }

    /** Plays a recording back, so you can hear what the app heard. */
    fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            Thread.sleep(samples.size * 1000L / rate + 200)
        } finally {
            track.release()
        }
    }

    /**
     * A standard WAV file: 16-bit PCM, mono, little-endian, with a 44-byte header.
     * The app records 16-bit samples and stores them as sample / 32768, so this gives back the exact recorded values.
     */
    fun wav(samples: FloatArray, rate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes).put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (s in samples) out.putShort(kotlin.math.round(s * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        return out.array()
    }

    fun rms(frame: FloatArray): Float =
        if (frame.isEmpty()) 0f else kotlin.math.sqrt(frame.fold(0f) { sum, s -> sum + s * s } / frame.size)
}

/**
 * Saves the last wake word recording to Downloads/PocketAssistant. Commands are heard by Google,
 * which doesn't share its audio with apps, so only the app's own wake word audio can be saved.
 * No storage permission is needed for this on Android 10 and newer.
 */
object Recordings {
    /** Returns the saved file names. Throws with a readable message if saving fails. */
    fun saveLast(context: Context): List<String> {
        val original = AssistantRuntime.lastAudio ?: error("Nothing recorded yet. Say the wake word first")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        return listOf(save(context, "wake-$stamp.wav", Audio.wav(original, 16_000)))
    }

    private fun save(context: Context, name: String, bytes: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/PocketAssistant")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Couldn't open the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return name
    }
}

/**
 * Google speech recognition. The on-device recognizer never uses the internet. If this phone doesn't
 * have it, the regular Google recognizer is asked to prefer its offline English pack.
 * This app has no internet permission, so audio never leaves the phone through this app.
 */
object GoogleStt {
    sealed interface Result {
        data class Text(val text: String) : Result
        data class Unavailable(val reason: String) : Result
        data object Nothing : Result
    }

    fun onDeviceAvailable(context: Context) = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun anyAvailable(context: Context) = onDeviceAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)

    /** Shown on the listening card and in the speech test. */
    fun modeName(context: Context) = if (onDeviceAvailable(context)) "Google on-device (offline)" else "Google, offline pack preferred"

    // Indian English first. If that offline pack isn't on the phone, US English is tried.
    private val tags = listOf("en-IN", "en-US")

    @Volatile
    private var tagIndex = 0

    private const val DOWNLOAD_HINT = "Download English in Settings > Speech recognition > Download offline speech"

    private fun reason(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English offline speech isn't downloaded yet. $DOWNLOAD_HINT"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Google's offline speech doesn't support this English yet. $DOWNLOAD_HINT"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> "Google tried to use the internet, so its offline English pack is probably missing. $DOWNLOAD_HINT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Google speech needs the microphone permission"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Google speech is busy. Try again in a moment"
        SpeechRecognizer.ERROR_AUDIO -> "The microphone couldn't be used. Another app may be using it"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests in a row. Wait a few seconds"
        else -> "Google speech failed (error $error)"
    }

    /** Listens once. Retries with US English if the Indian English offline pack is missing. */
    suspend fun listen(context: Context, @Suppress("UNUSED_PARAMETER") language: String = "en"): Result {
        val first = listenOnce(context, tags[tagIndex])
        val missing = first is Result.Unavailable && first.reason.startsWith("English offline") ||
            first is Result.Unavailable && first.reason.startsWith("Google's offline speech doesn't")
        if (missing && tagIndex < tags.size - 1) {
            tagIndex++
            return listenOnce(context, tags[tagIndex])
        }
        return first
    }

    private suspend fun listenOnce(context: Context, tag: String): Result = withContext(Dispatchers.Main) {
        if (!anyAvailable(context)) {
            return@withContext Result.Unavailable("Google speech isn't installed. Install or update Speech Services by Google from the Play Store")
        }
        val recognizer = if (onDeviceAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        val done = CompletableDeferred<Result>()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                done.complete(if (text.isBlank()) Result.Nothing else Result.Text(text))
            }

            override fun onError(error: Int) {
                done.complete(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Result.Nothing
                        else -> Result.Unavailable(reason(error))
                    },
                )
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                AssistantRuntime.micLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                AssistantRuntime.status.value = "Working"
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { AssistantRuntime.partial.value = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        try {
            recognizer.startListening(intent)
            withTimeoutOrNull(15_000) { done.await() } ?: Result.Nothing
        } catch (e: Exception) {
            Result.Unavailable("Google speech couldn't start (${e.javaClass.simpleName})")
        } finally {
            recognizer.destroy()
        }
    }

    /**
     * Opens Google's screen for downloading offline speech languages. Phones differ, so several screens are
     * tried in order, ending with the phone's voice input settings. Returns false if none could be opened.
     */
    fun openOfflineDownload(context: Context): Boolean {
        val attempts = listOf(
            Intent().setClassName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.greco3.languagepack.InstallActivity"),
            Intent("com.google.android.speech.embedded.MANAGE_LANGUAGES"),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")),
        )
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return false
    }
}

/** Your voiceprint is an encrypted list of numbers. Raw enrollment audio isn't kept. */
object VoiceId {
    private fun file(context: Context) = File(context.noBackupFilesDir, "voiceprint.bin")

    fun isEnrolled(context: Context) = file(context).exists()

    fun save(context: Context, embeddings: List<FloatArray>) {
        val size = embeddings.first().size
        val mean = FloatArray(size) { i -> embeddings.sumOf { it[i].toDouble() }.toFloat() / embeddings.size }
        val buffer = ByteBuffer.allocate(size * 4)
        mean.forEach { buffer.putFloat(it) }
        file(context).writeBytes(Keys.encrypt(buffer.array()))
    }

    fun delete(context: Context) {
        file(context).delete()
    }

    fun matches(context: Context, sample: FloatArray?): Boolean {
        if (sample == null || !isEnrolled(context)) return false
        val stored = runCatching { Keys.decrypt(file(context).readBytes()) }.getOrNull() ?: return false
        val buffer = ByteBuffer.wrap(stored)
        return VoiceMatch.isMatch(FloatArray(stored.size / 4) { buffer.getFloat() }, sample)
    }
}

data class Heard(val text: String, val verified: Boolean, val samples: FloatArray)

/**
 * The only code that opens the microphone. The mic stays closed unless the wake word
 * is on or a capture was requested, and audio is never written to disk.
 */
class VoiceLoop(
    private val context: Context,
    private val engines: VoiceEngines,
    private val scope: CoroutineScope,
    private val onCommand: suspend (Heard) -> Unit,
) {
    private val listenRequested = AtomicBoolean(false)

    @Volatile
    var speaking = false

    @Volatile
    private var captureRequest: CompletableDeferred<FloatArray?>? = null

    @Volatile
    private var busy = false

    @Volatile
    private var wakeChanged = false

    @Volatile
    private var checking = false

    // Set while Google listens for a confirmation or the speech test, so the app's microphone is released.
    @Volatile
    private var micPaused = false

    // Recent microphone audio, so speech said right after the wake word isn't lost.
    private val recent = ArrayDeque<Pair<Long, FloatArray>>()
    private var frameIndex = 0L


    // The room's background noise level, and the last moment real speech was heard.
    private var noiseFloor = 0.01f
    private var phrasesHeard = 0
    private var lastSpeechFrame = -1000L

    // Microphone checks: samples read since recording started, samples Android dropped, and the buffer size.
    private var samplesRead = 0L
    private var samplesLost = 0L
    private var bufferFrames = 0
    private val timestamp = AudioTimestamp()

    /** Applies a new wake name without restarting the assistant. */
    fun reloadWakeName() {
        wakeChanged = true
    }

    @Volatile
    private var record: AudioRecord? = null

    fun requestListen() = listenRequested.set(true)

    /** Records one spoken phrase for a challenge, enrollment, or a call turn. */
    suspend fun capture(timeoutMillis: Long): FloatArray? {
        val request = CompletableDeferred<FloatArray?>()
        captureRequest = request
        val result = withTimeoutOrNull(timeoutMillis) { request.await() }
        captureRequest = null
        return result
    }

    /**
     * Lets Google listen once, outside the wake word flow. The app's microphone is released first,
     * because Android lets only one app record at a time.
     */
    suspend fun listenWithGoogleNow(): GoogleStt.Result {
        micPaused = true
        try {
            withTimeoutOrNull(1_500) { while (record != null) delay(20) }
            return GoogleStt.listen(context)
        } finally {
            micPaused = false
        }
    }

    private fun micProblem(message: String) {
        AssistantRuntime.micInfo.value = "Microphone problem: $message"
        Audit.log(context, "mic_problem")
    }

    /**
     * Opens the microphone and checks it really records 16,000 Hz, mono, 16-bit audio, the format
     * the speech models need. Android converts the microphone to this rate itself, so no resampling is done here.
     */
    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        record?.let { return it }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            micProblem("this phone can't record 16 kHz mono 16-bit audio")
            return null
        }
        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // Room for 4 seconds of audio, so nothing is lost while the phone is busy recognizing speech.
                maxOf(minimum, SAMPLE_RATE * 2 * 4),
            )
        } catch (e: Exception) {
            micProblem("it couldn't be opened (${e.javaClass.simpleName})")
            return null
        }
        val wrongFormat = when {
            created.state != AudioRecord.STATE_INITIALIZED -> "it couldn't start. Another app may be using it"
            created.sampleRate != SAMPLE_RATE -> "it records at ${created.sampleRate} Hz instead of 16,000 Hz"
            created.channelCount != 1 -> "it records ${created.channelCount} channels instead of 1"
            created.audioFormat != AudioFormat.ENCODING_PCM_16BIT -> "it doesn't record 16-bit audio"
            else -> null
        }
        if (wrongFormat != null) {
            created.release()
            micProblem(wrongFormat)
            return null
        }
        // The voice recognition source is tuned for speech models. Extra phone filters can distort words, so none are added.
        created.startRecording()
        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            created.release()
            micProblem("recording didn't start. A call or another app may be using it")
            return null
        }
        bufferFrames = created.bufferSizeInFrames
        samplesRead = 0
        samplesLost = 0
        AssistantRuntime.micInfo.value = "Microphone: %,d Hz, mono, 16-bit, %.1f s buffer. No audio lost".format(
            created.sampleRate, bufferFrames / SAMPLE_RATE.toFloat(),
        )
        record = created
        return created
    }

    /**
     * Android drops audio without saying so when the app reads too slowly. The microphone's own
     * frame counter shows how much was recorded, so anything beyond what was read and still buffered was lost.
     */
    private fun checkForLostAudio(mic: AudioRecord) {
        if (mic.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return
        val lost = timestamp.framePosition - samplesRead - bufferFrames
        if (lost > samplesLost + SAMPLE_RATE / 10) {
            samplesLost = lost
            AssistantRuntime.micInfo.value = "Microphone: %,d Hz, mono, 16-bit. Lost %.1f s of audio because the phone was busy".format(
                mic.sampleRate, samplesLost / SAMPLE_RATE.toFloat(),
            )
            Audit.log(context, "mic_audio_lost")
        }
        if (mic.activeRecordingConfiguration?.isClientSilenced == true) {
            AssistantRuntime.micInfo.value = "Microphone problem: another app is using the microphone, so the assistant hears silence"
        }
    }

    private fun close() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    private fun wakeAllowed(): Boolean =
        Features.wakeWord(context) && AssistantRuntime.isActive() && !PowerGuard.shouldPause(context) &&
            !AssistantRuntime.inPhoneCall(context)

    suspend fun run() = withContext(Dispatchers.IO) {
        val shorts = ShortArray(1600)
        val floats = FloatArray(1600)
        var wakeStream: OnlineStream? = null
        var collector: Collector? = null
        var watcher: Collector? = null
        var wakeGain = 1.5f
        // The speech detector for commands is loaded once, before recording, and reset for each command.
        // Loading it while you speak used to pause the microphone.
        val listenVad = engines.newVad(VAD_SILENCE)
        try {
            while (currentCoroutineContext().isActive) {
                if (micPaused) {
                    close()
                    delay(30)
                    continue
                }
                val wantCapture = captureRequest != null
                val wantListen = listenRequested.get()
                val wantWake = !busy && wakeAllowed()
                if (!wantCapture && !wantListen && !wantWake && collector == null) {
                    close()
                    wakeStream?.release()
                    wakeStream = null
                    watcher?.close()
                    watcher = null
                    delay(200)
                    continue
                }
                val mic = open()
                if (mic == null) {
                    delay(1000)
                    continue
                }
                val count = mic.read(shorts, 0, shorts.size)
                if (count < 0) {
                    micProblem("reading failed (error $count). Restarting it")
                    close()
                    delay(500)
                    continue
                }
                if (count == 0) continue
                samplesRead += count
                if (frameIndex % 10 == 0L) checkForLostAudio(mic)
                if (speaking) continue
                for (i in 0 until count) floats[i] = shorts[i] / 32768f
                val frame = floats.copyOf(count)
                frameIndex++
                recent.addLast(frameIndex to frame)
                while (recent.size > 50) recent.removeFirst()

                // Commands are always heard by Google, which shows the words live as you speak.
                if (collector == null && wantListen && !wantCapture) {
                    listenRequested.set(false)
                    busy = true
                    close()
                    scope.launch(Dispatchers.Default) { listenWithGoogle() }
                    continue
                }
                // The app's own recording is used for Voice ID, which needs the raw audio.
                if (collector == null && wantCapture) {
                    collector = Collector(listenVad, END_PAUSE_MS, ownsVad = false)
                    watcher?.restart()
                    AssistantRuntime.status.value = "Listening"
                }
                val active = collector
                if (active != null) {
                    var samples = active.feed(frame)
                    if (samples == null && active.expired()) samples = active.partial() ?: FloatArray(0)
                    if (samples != null) {
                        active.close()
                        collector = null
                        deliver(samples.takeIf { it.isNotEmpty() })
                    }
                    continue
                }
                if (wantWake) {
                    if (wakeChanged) {
                        wakeChanged = false
                        wakeStream?.release()
                        wakeStream = null
                    }
                    val stream = wakeStream ?: engines.spotter.createStream(engines.wakeKeywords(context)).also { wakeStream = it }
                    val level = Audio.rms(frame)
                    // Follows the room's background noise: drops quickly, rises slowly, so speech doesn't count as noise.
                    noiseFloor = if (level < noiseFloor) noiseFloor * 0.8f + level * 0.2f else noiseFloor * 0.995f + level * 0.005f
                    noiseFloor = noiseFloor.coerceIn(0.0005f, 0.05f)
                    val loudEnough = level > maxOf(noiseFloor * WAKE_OVER_NOISE, WAKE_MIN_LEVEL)
                    // The speech detector runs first, so a wake word is only accepted when a voice was really heard.
                    val check = watcher ?: Collector(engines.newVad(VAD_SILENCE), WAKE_PAUSE_MS).also { watcher = it }
                    val phrase = check.feed(frame)
                    if (check.speechInLastFeed && loudEnough) lastSpeechFrame = frameIndex
                    if (phrase != null) {
                        // A phrase ended without waking. Saying so shows the microphone works and how loud you were.
                        phrasesHeard++
                        val peak = (phrase.maxOfOrNull { kotlin.math.abs(it) } ?: 0f) * 100
                        AssistantRuntime.wakeInfo.value = "Heard you speak (%d so far, loudest %.0f%%, room noise %.1f%%), but not \"%s\"".format(
                            phrasesHeard, peak, noiseFloor * 100, WakeName.phrase(Features.wakeName(context)).lowercase(),
                        )
                    }
                    if (phrase != null || check.expired()) check.restart()
                    // The second check: a small speech model listens for the name in each short phrase.
                    if (phrase != null && phrase.size in NAME_MIN_SAMPLES..NAME_MAX_SAMPLES && !checking &&
                        frameIndex - lastSpeechFrame <= WAKE_SPEECH_FRAMES
                    ) {
                        checkName(phrase)
                    }

                    // A gentle boost for quiet speech. Strong boosts distort the voice and hurt detection.
                    if (level > 0.002f) wakeGain = (wakeGain * 0.9f + (0.05f / level).coerceIn(1f, 3f) * 0.1f)
                    stream.acceptWaveform(Audio.boosted(frame, wakeGain), SAMPLE_RATE)
                    var spotted = false
                    while (engines.spotter.isReady(stream)) {
                        engines.spotter.decode(stream)
                        if (engines.spotter.getResult(stream).keyword.isNotBlank()) {
                            engines.spotter.reset(stream)
                            spotted = true
                            break
                        }
                    }
                    if (spotted && frameIndex - lastSpeechFrame > WAKE_SPEECH_FRAMES) {
                        AssistantRuntime.wakeInfo.value = "Ignored a wake word with no clear voice (noise)"
                    } else if (spotted && !isOwnerVoice(recentAudio(WAKE_SPEECH_FRAMES))) {
                        AssistantRuntime.wakeInfo.value = "Ignored a wake word: not your saved voice"
                    } else if (spotted) {
                        AssistantRuntime.wakeInfo.value = "Woke up"
                        // Kept in memory so "Play last recording" and "Save last recording" can check the wake word audio.
                        AssistantRuntime.lastAudio = recentAudio(30)
                        wakeUp()
                        listenRequested.set(true)
                        check.restart()
                        continue
                    }
                }
            }
        } finally {
            collector?.close()
            watcher?.close()
            listenVad.release()
            wakeStream?.release()
            close()
        }
    }

    /** Google listens and returns text as soon as you stop. The app's microphone stays closed meanwhile. */
    private suspend fun listenWithGoogle() {
        try {
            AssistantRuntime.status.value = "Listening"
            AssistantRuntime.partial.value = ""
            when (val result = GoogleStt.listen(context, Features.language(context))) {
                is GoogleStt.Result.Text -> {
                    AssistantRuntime.lastEngine.value = GoogleStt.modeName(context)
                    AssistantRuntime.status.value = "Working"
                    val name = Features.wakeName(context)
                    val rest = WakeName.heardIn(result.text, name)
                    val command = if (rest.isNullOrBlank()) result.text else rest
                    if (rest != "" && command.isNotBlank()) onCommand(Heard(command, false, FloatArray(0)))
                }
                is GoogleStt.Result.Unavailable -> {
                    AssistantRuntime.say(result.reason)
                    Audit.log(context, "google_speech_unavailable")
                }
                GoogleStt.Result.Nothing -> Unit
            }
        } finally {
            busy = false
            if (AssistantRuntime.armed.value) AssistantRuntime.status.value = "Ready"
        }
    }

    private fun wakeUp() {
        beep()
        WakeUi.open(context)
    }

    /** The last few frames of microphone audio, joined together. */
    private fun recentAudio(frames: Int): FloatArray {
        val parts = recent.toList().takeLast(frames).map { it.second }
        val out = FloatArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach {
            it.copyInto(out, offset)
            offset += it.size
        }
        return out
    }

    /** With "Only wake for my voice" on, other voices, like a TV, can't wake the assistant. */
    private fun isOwnerVoice(samples: FloatArray): Boolean {
        if (!Features.wakeOnlyMyVoice(context) || !VoiceId.isEnrolled(context)) return true
        return runCatching { VoiceId.matches(context, engines.embedding(samples)) }.getOrDefault(false)
    }

    private fun checkName(samples: FloatArray) {
        checking = true
        scope.launch(Dispatchers.Default) {
            try {
                val name = Features.wakeName(context)
                val text = engines.nameText(samples)
                val wanted = WakeName.phrase(name).lowercase()
                if (!WakeName.nameHeard(text, name, Features.learnedWakeWords(context))) {
                    if (text.isNotBlank()) AssistantRuntime.wakeInfo.value = "Heard \"$text\", not \"$wanted\""
                    return@launch
                }
                if (!isOwnerVoice(samples)) {
                    AssistantRuntime.wakeInfo.value = "Heard \"$text\", but not your saved voice"
                    return@launch
                }
                if (busy || listenRequested.get() || !wakeAllowed()) return@launch
                AssistantRuntime.wakeInfo.value = "Woke up: heard \"$text\""
                AssistantRuntime.lastAudio = samples
                wakeUp()
                listenRequested.set(true)
            } catch (e: Exception) {
                Audit.log(context, "name_check_failed:${e.javaClass.simpleName}")
            } finally {
                checking = false
            }
        }
    }

    /** Tells you the wake word was heard, without needing to look at the screen. */
    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Thread.sleep(180)
            tone.release()
        }
    }

    private fun deliver(samples: FloatArray?) {
        val request = captureRequest
        if (request != null) {
            request.complete(samples)
            return
        }
        if (AssistantRuntime.armed.value) AssistantRuntime.status.value = "Ready"
    }

    /**
     * Collects one phrase. The speech detector only decides when you've finished. The audio itself is
     * kept whole, from just before your first word, so short pauses and first syllables aren't lost.
     */
    private class Collector(
        private val vad: Vad,
        private val endPauseMs: Int,
        preroll: List<FloatArray> = emptyList(),
        private val ownsVad: Boolean = true,
    ) {
        private var started = System.currentTimeMillis()
        private val pending = ArrayList<Float>()
        private val audio = ArrayList<FloatArray>()
        private var total = 0
        private var vadPosition = 0
        private var speechStart = -1
        private var lastSpeech = -1

        /** Whether the last frame fed in contained speech. */
        var speechInLastFeed = false
            private set

        init {
            if (!ownsVad) {
                vad.clear()
                vad.reset()
            }
            preroll.forEach { add(it) }
        }

        private fun add(frame: FloatArray) {
            speechInLastFeed = false
            audio += frame
            total += frame.size
            frame.forEach { pending.add(it) }
            while (pending.size >= WINDOW) {
                val window = FloatArray(WINDOW) { pending[it] }
                pending.subList(0, WINDOW).clear()
                vad.acceptWaveform(window)
                vadPosition += WINDOW
                if (vad.isSpeechDetected()) {
                    speechInLastFeed = true
                    if (speechStart < 0) speechStart = vadPosition - WINDOW
                    lastSpeech = vadPosition
                }
                while (!vad.empty()) vad.pop()
            }
        }

        fun feed(frame: FloatArray): FloatArray? {
            add(frame)
            if (speechStart >= 0 && vadPosition - lastSpeech >= endPauseMs * SAMPLE_RATE / 1000) return slice()
            return null
        }

        /** Returns what was said so far once time runs out, rather than dropping it. */
        fun expired(): Boolean {
            val elapsed = System.currentTimeMillis() - started
            return (speechStart < 0 && elapsed > 7_000) || elapsed > 15_000
        }

        fun partial(): FloatArray? = if (speechStart >= 0) slice() else null


        private fun slice(end: Int = lastSpeech + SAMPLE_RATE / 4): FloatArray {
            val all = FloatArray(total)
            var offset = 0
            audio.forEach {
                it.copyInto(all, offset)
                offset += it.size
            }
            // Detection lags behind the real start of speech, so earlier audio is kept too.
            val from = (speechStart - SAMPLE_RATE / 2).coerceAtLeast(0)
            val to = end.coerceAtMost(total)
            return all.copyOfRange(from, to)
        }

        fun restart() {
            pending.clear()
            audio.clear()
            total = 0
            vadPosition = 0
            speechStart = -1
            lastSpeech = -1
            started = System.currentTimeMillis()
            vad.reset()
        }

        /** A shared speech detector is cleared and reset for the next command, not released. */
        fun close() {
            if (ownsVad) {
                vad.release()
            } else {
                vad.clear()
                vad.reset()
            }
        }

        private companion object {
            const val WINDOW = 512
        }
    }
}

/** Piper voice output. Falls back to Android's voice if Piper fails to load. */
class VoiceOutput(private val engines: VoiceEngines, private val loop: () -> VoiceLoop?) {
    private val mutex = Mutex()

    /** Returns false if Piper couldn't speak, so the caller can use the fallback voice. */
    suspend fun say(text: String): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            loop()?.speaking = true
            try {
                val audio = engines.synthesize(text)
                play(audio.samples, audio.sampleRate)
                true
            } catch (e: Exception) {
                false
            } catch (e: UnsatisfiedLinkError) {
                false
            } finally {
                delay(250) // Lets the speaker go quiet, so the mic doesn't hear the assistant.
                loop()?.speaking = false
            }
        }
    }

    private fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(rate, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            var offset = 0
            while (offset < samples.size && AssistantRuntime.armed.value) {
                val written = track.write(samples, offset, minOf(4096, samples.size - offset), AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            Thread.sleep(150)
            track.stop()
        } finally {
            track.release()
        }
    }
}
