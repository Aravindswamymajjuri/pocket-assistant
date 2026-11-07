package dev.aravind.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
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
import dev.aravind.assistant.policy.SpeechPrompt
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
private const val MAX_WAKE_SAMPLES = SAMPLE_RATE * 3

/** Voice models ship in the APK and are unpacked to private storage on first use. */
object VoiceAssets {
    private const val VERSION = "4"

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
    private val hindi by lazy { piper("piper-hi") }

    fun transcribe(samples: FloatArray, language: String, prompt: String): String {
        val model = if (language == "en") "ggml-base.en-q5_1.bin" else "ggml-base-q5_1.bin"
        return WhisperCpp.transcribe("$root/whisper/$model", Audio.normalized(samples), language, prompt)
    }

    /** The small English model, several times faster, used only to check for the wake word. */
    fun quickTranscribe(samples: FloatArray, prompt: String): String =
        WhisperCpp.transcribe("$root/whisper/ggml-tiny.en-q5_1.bin", Audio.normalized(samples), "en", prompt)

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

    fun synthesize(text: String, useHindi: Boolean) = (if (useHindi) hindi else english).generate(text, sid = 0, speed = 1.0f)
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

    fun rms(frame: FloatArray): Float =
        if (frame.isEmpty()) 0f else kotlin.math.sqrt(frame.fold(0f) { sum, s -> sum + s * s } / frame.size)
}

/** whisper.cpp through a small JNI layer. English uses a more accurate English-only model. */
object WhisperCpp {
    // Each model stays loaded, so switching between the wake check and commands costs nothing.
    private val handles = HashMap<String, Long>()

    init {
        System.loadLibrary("whisperjni")
    }

    private external fun nativeInit(model: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, prompt: String, threads: Int): String
    private external fun nativeFree(handle: Long)

    @Synchronized
    fun transcribe(model: String, samples: FloatArray, language: String, prompt: String): String {
        val handle = handles.getOrPut(model) { nativeInit(model) }
        if (handle == 0L) {
            handles.remove(model)
            error("The speech model failed to load")
        }
        return nativeTranscribe(handle, samples, language, prompt, 4).trim()
    }

    @Synchronized
    fun release() {
        handles.values.filter { it != 0L }.forEach { nativeFree(it) }
        handles.clear()
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

    private fun prompt() = SpeechPrompt.text(Features.wakeName(context))

    /** Language is a Whisper code. Leave it null to use the language chosen in Control Center. */
    fun understand(samples: FloatArray, language: String? = null): Heard {
        val text = engines.transcribe(samples, language ?: Features.language(context), prompt())
        val verified = VoiceId.matches(context, engines.embedding(samples))
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
        val session = created.audioSessionId
        if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(session)?.enabled = true }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(session)?.enabled = true }
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

                if (collector == null && (wantCapture || wantListen)) {
                    listenRequested.set(false)
                    collector = Collector(engines.newVad())
                    AssistantRuntime.status.value = "Listening"
                }
                val active = collector
                if (active != null) {
                    val samples = active.feed(frame)
                    if (samples != null || active.expired()) {
                        active.close()
                        collector = null
                        deliver(samples)
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
                    // Keeps speech near a steady level, however far away you are.
                    val level = Audio.rms(frame)
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
                    if (spotted) {
                        wakeUp()
                        listenRequested.set(true)
                        watcher?.restart()
                        continue
                    }
                    // A second detector: Whisper checks short phrases, which handles names and accents better.
                    val check = watcher ?: Collector(engines.newVad(0.3f)).also { watcher = it }
                    val phrase = check.feed(frame)
                    if (phrase != null || check.expired()) check.restart()
                    if (phrase != null && phrase.size in MIN_WAKE_SAMPLES..MAX_WAKE_SAMPLES && !checking) checkForName(phrase)
                }
            }
        } finally {
            collector?.close()
            watcher?.close()
            wakeStream?.release()
            close()
        }
    }

    private fun wakeUp() {
        beep()
        WakeUi.open(context)
    }

    private fun checkForName(samples: FloatArray) {
        checking = true
        scope.launch(Dispatchers.Default) {
            try {
                val name = Features.wakeName(context)
                val text = engines.quickTranscribe(samples, "Hey $name.")
                val rest = WakeName.heardIn(text, name) ?: return@launch
                wakeUp()
                if (rest.contains(' ')) {
                    busy = true
                    try {
                        // The quick model only checks for the name. The command is heard again with the accurate one.
                        val heard = understand(samples)
                        val command = WakeName.heardIn(heard.text, name)?.takeIf { it.contains(' ') } ?: rest
                        onCommand(heard.copy(text = command))
                    } finally {
                        busy = false
                        if (AssistantRuntime.armed.value) AssistantRuntime.status.value = "Ready"
                    }
                } else {
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

    /** Collects one phrase, ending after a pause in speech. */
    private class Collector(private val vad: Vad) {
        private var started = System.currentTimeMillis()
        private val pending = ArrayList<Float>()
        private val speech = ArrayList<FloatArray>()

        fun feed(frame: FloatArray): FloatArray? {
            frame.forEach { pending.add(it) }
            while (pending.size >= WINDOW) {
                val window = FloatArray(WINDOW) { pending[it] }
                pending.subList(0, WINDOW).clear()
                vad.acceptWaveform(window)
            }
            while (!vad.empty()) {
                speech += vad.front().samples
                vad.pop()
            }
            if (speech.isNotEmpty() && !vad.isSpeechDetected()) return merged()
            return null
        }

        fun expired(): Boolean {
            val elapsed = System.currentTimeMillis() - started
            return (speech.isEmpty() && !vad.isSpeechDetected() && elapsed > 7_000) || elapsed > 20_000
        }

        private fun merged(): FloatArray {
            val out = FloatArray(speech.sumOf { it.size })
            var offset = 0
            speech.forEach {
                it.copyInto(out, offset)
                offset += it.size
            }
            return out
        }

        fun restart() {
            pending.clear()
            speech.clear()
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
    suspend fun say(text: String, useHindi: Boolean): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            loop()?.speaking = true
            try {
                val audio = engines.synthesize(text, useHindi)
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
