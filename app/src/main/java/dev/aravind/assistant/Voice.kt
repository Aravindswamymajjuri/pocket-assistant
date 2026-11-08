package dev.aravind.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
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
import java.util.concurrent.atomic.AtomicBoolean

private const val SAMPLE_RATE = 16_000
private const val MIN_WAKE_SAMPLES = SAMPLE_RATE * 2 / 5
private const val MAX_WAKE_SAMPLES = SAMPLE_RATE * 5 / 2

// A wake word only counts if real speech was heard in the last 1.5 seconds (15 frames of 0.1 s).
private const val WAKE_SPEECH_FRAMES = 15

// Speech must be about twice as loud as the room's background noise to wake the assistant.
private const val WAKE_OVER_NOISE = 2.0f
private const val WAKE_MIN_LEVEL = 0.003f

// The detector flips quickly. The pause you must leave before a phrase ends is set separately below.
private const val VAD_SILENCE = 0.2f
private const val END_PAUSE_MS = 650
private const val WAKE_PAUSE_MS = 300

/** Voice models ship in the APK and are unpacked to private storage on first use. */
object VoiceAssets {
    private const val VERSION = "7"

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
        return WakeName.keywords(tokenizer, Features.wakeName(context), sensitivity)
            ?: requireNotNull(WakeName.keywords(tokenizer, WakeName.DEFAULT, sensitivity))
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

    private val moonshine by lazy {
        OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        preprocessor = "$root/moonshine/preprocess.onnx",
                        encoder = "$root/moonshine/encode.int8.onnx",
                        uncachedDecoder = "$root/moonshine/uncached_decode.int8.onnx",
                        cachedDecoder = "$root/moonshine/cached_decode.int8.onnx",
                    ),
                    tokens = "$root/moonshine/tokens.txt",
                    numThreads = 4,
                ),
            ),
        )
    }

    private val moonshineLock = Any()

    private fun moonshineText(samples: FloatArray): String = synchronized(moonshineLock) {
        val stream = moonshine.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            moonshine.decode(stream)
            return moonshine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** For the speech test: each engine on its own, with no fallback. */
    fun moonshineOnly(samples: FloatArray): String = moonshineText(Audio.normalized(samples))

    /** Turns speech into text with Moonshine, on the phone. */
    fun transcribe(samples: FloatArray): String {
        AssistantRuntime.lastEngine.value = "Moonshine"
        return moonshineText(Audio.normalized(samples))
    }

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

    fun rms(frame: FloatArray): Float =
        if (frame.isEmpty()) 0f else kotlin.math.sqrt(frame.fold(0f) { sum, s -> sum + s * s } / frame.size)
}

object SpeechEngine {
    const val AUTO = "auto"
    const val GOOGLE = "google"
    const val MOONSHINE = "moonshine"
}

/**
 * Android's built-in recognizer. The on-device version never uses the internet.
 * The standard version prefers offline, but may go online if the offline pack is missing.
 */
object GoogleStt {
    sealed interface Result {
        data class Text(val text: String) : Result
        data class Unavailable(val reason: String) : Result
        data object Nothing : Result
    }

    /** Set when Google fails for a reason that won't fix itself, so it isn't retried until restart. */
    @Volatile
    var brokenThisSession = false

    fun onDeviceAvailable(context: Context) = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun anyAvailable(context: Context) = onDeviceAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)

    fun inUse(context: Context): Boolean {
        if (brokenThisSession) return false
        return when (Features.speechEngine(context)) {
            SpeechEngine.GOOGLE -> anyAvailable(context)
            SpeechEngine.AUTO -> onDeviceAvailable(context)
            else -> false
        }
    }

    private fun tag(language: String) = when (language) {
        else -> "en-IN"
    }

    suspend fun listen(context: Context, language: String): Result = withContext(Dispatchers.Main) {
        val onDevice = onDeviceAvailable(context)
        val recognizer = if (onDevice) {
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
                        else -> {
                            brokenThisSession = true
                            Result.Unavailable("error $error")
                        }
                    },
                )
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
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
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag(language))
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        try {
            recognizer.startListening(intent)
            withTimeoutOrNull(15_000) { done.await() } ?: Result.Nothing
        } catch (e: Exception) {
            brokenThisSession = true
            Result.Unavailable(e.javaClass.simpleName)
        } finally {
            recognizer.destroy()
        }
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

    // Recent microphone audio, so speech said right after the wake word isn't lost.
    private val recent = ArrayDeque<Pair<Long, FloatArray>>()
    private var frameIndex = 0L

    @Volatile
    private var prerollFrom = -1L

    @Volatile
    private var partialBusy = false
    private var partialFrames = 0

    // The room's background noise level, and the last moment real speech was heard.
    private var noiseFloor = 0.01f
    private var lastSpeechFrame = -1000L

    /** Applies a new wake name without restarting the assistant. */
    fun reloadWakeName() {
        wakeChanged = true
    }

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

    fun understand(samples: FloatArray): Heard {
        val text = engines.transcribe(samples)
        // Voice ID adds a delay, so it only runs once a voice has been saved.
        val verified = VoiceId.isEnrolled(context) && VoiceId.matches(context, engines.embedding(samples))
        return Heard(text, verified, samples)
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        record?.let { return it }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val created = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, SAMPLE_RATE),
        )
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return null
        }
        // The voice recognition source is tuned for speech models. Extra phone filters can distort words, so none are added.
        created.startRecording()
        record = created
        return created
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
        try {
            while (currentCoroutineContext().isActive) {
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
                if (count <= 0) continue
                if (speaking) continue
                for (i in 0 until count) floats[i] = shorts[i] / 32768f
                val frame = floats.copyOf(count)
                frameIndex++
                recent.addLast(frameIndex to frame)
                while (recent.size > 50) recent.removeFirst()

                if (collector == null && wantListen && !wantCapture && GoogleStt.inUse(context)) {
                    listenRequested.set(false)
                    busy = true
                    close()
                    scope.launch(Dispatchers.Default) { listenWithGoogle() }
                    continue
                }
                if (collector == null && (wantCapture || wantListen)) {
                    listenRequested.set(false)
                    val from = prerollFrom
                    prerollFrom = -1L
                    // After a wake word found by the name check, start from where the wake phrase ended.
                    val preroll = if (from >= 0) recent.filter { it.first > from && it.first < frameIndex }.map { it.second } else emptyList()
                    collector = Collector(engines.newVad(VAD_SILENCE), END_PAUSE_MS, preroll)
                    AssistantRuntime.partial.value = ""
                    partialFrames = 0
                    watcher?.restart()
                    AssistantRuntime.status.value = "Listening"
                }
                val active = collector
                if (active != null) {
                    var samples = active.feed(frame)
                    // While you speak, the words so far are shown about once a second.
                    if (samples == null && captureRequest == null) {
                        partialFrames++
                        if (partialFrames >= 8 && !partialBusy) {
                            active.snapshot()?.let { snap ->
                                partialFrames = 0
                                partialBusy = true
                                scope.launch(Dispatchers.Default) {
                                    try {
                                        val text = runCatching { engines.moonshineOnly(snap) }.getOrDefault("")
                                        if (text.isNotBlank()) AssistantRuntime.partial.value = text
                                    } finally {
                                        partialBusy = false
                                    }
                                }
                            }
                        }
                    }
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
                    if (phrase != null || check.expired()) check.restart()

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
                    if (spotted && frameIndex - lastSpeechFrame <= WAKE_SPEECH_FRAMES && isOwnerVoice(recentAudio(WAKE_SPEECH_FRAMES))) {
                        wakeUp()
                        listenRequested.set(true)
                        check.restart()
                        continue
                    }
                    // A second, slower check with Moonshine catches the name when the fast detector misses it.
                    if (phrase != null && phrase.size in MIN_WAKE_SAMPLES..MAX_WAKE_SAMPLES && !checking &&
                        frameIndex - lastSpeechFrame <= WAKE_SPEECH_FRAMES
                    ) {
                        checkForName(phrase, frameIndex)
                    }
                }
            }
        } finally {
            collector?.close()
            watcher?.close()
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
                    AssistantRuntime.lastEngine.value = if (GoogleStt.onDeviceAvailable(context)) "Google on-device" else "Google"
                    AssistantRuntime.status.value = "Working"
                    val name = Features.wakeName(context)
                    val rest = WakeName.heardIn(result.text, name)
                    val command = if (rest.isNullOrBlank()) result.text else rest
                    if (rest != "" && command.isNotBlank()) onCommand(Heard(command, false, FloatArray(0)))
                }
                is GoogleStt.Result.Unavailable -> {
                    AssistantRuntime.say("Google speech isn't available (${result.reason}), so I'm using Moonshine")
                    listenRequested.set(true)
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

    private fun checkForName(samples: FloatArray, endedAt: Long) {
        checking = true
        scope.launch(Dispatchers.Default) {
            try {
                val name = Features.wakeName(context)
                val text = engines.moonshineOnly(samples)
                // Stricter than the command check: a greeting and the name, or the name said exactly.
                val rest = WakeName.heardInStrict(text, name) ?: return@launch
                if (!isOwnerVoice(samples)) return@launch
                wakeUp()
                if (rest.contains(' ')) {
                    busy = true
                    try {
                        val heard = understand(samples)
                        onCommand(heard.copy(text = rest))
                    } finally {
                        busy = false
                        if (AssistantRuntime.armed.value) AssistantRuntime.status.value = "Ready"
                    }
                } else {
                    prerollFrom = endedAt
                    listenRequested.set(true)
                }
            } catch (e: Exception) {
                Audit.log(context, "wake_check_failed:${e.javaClass.simpleName}")
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
        if (samples == null) {
            AssistantRuntime.status.value = "Ready"
            return
        }
        busy = true
        AssistantRuntime.status.value = "Working"
        scope.launch(Dispatchers.Default) {
            try {
                val heard = understand(samples)
                // "Hey Nova, call Daddy" and "call Daddy" should do the same thing.
                val rest = WakeName.heardIn(heard.text, Features.wakeName(context))
                val command = if (rest.isNullOrBlank()) heard else heard.copy(text = rest)
                if (rest != "" && command.text.isNotBlank()) onCommand(command)
            } finally {
                busy = false
                if (AssistantRuntime.armed.value) AssistantRuntime.status.value = "Ready"
            }
        }
    }

    /**
     * Collects one phrase. The speech detector only decides when you've finished. The audio itself is
     * kept whole, from just before your first word, so short pauses and first syllables aren't lost.
     */
    private class Collector(private val vad: Vad, private val endPauseMs: Int, preroll: List<FloatArray> = emptyList()) {
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

        /** Everything from just before your first word up to now, for showing words as you speak. */
        fun snapshot(): FloatArray? = if (speechStart >= 0) slice(total) else null

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

        fun close() = vad.release()

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
