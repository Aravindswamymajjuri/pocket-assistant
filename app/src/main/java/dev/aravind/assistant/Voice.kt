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
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.aravind.assistant.policy.VoiceMatch
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

/** Voice models ship in the APK and are unpacked to private storage on first use. */
object VoiceAssets {
    private const val VERSION = "1"

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
            ),
        )
    }

    fun newVad(): Vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$root/vad/silero_vad.onnx",
                minSilenceDuration = 0.7f,
                minSpeechDuration = 0.25f,
                maxSpeechDuration = 14f,
            ),
            sampleRate = SAMPLE_RATE,
        ),
    )

    private val recognizer by lazy {
        OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "$root/whisper/encoder.onnx",
                        decoder = "$root/whisper/decoder.onnx",
                    ),
                    tokens = "$root/whisper/tokens.txt",
                    modelType = "whisper",
                    numThreads = 2,
                ),
            ),
        )
    }

    private val extractor by lazy {
        SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = "$root/speaker/model.onnx", numThreads = 2))
    }

    private val tts by lazy {
        OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$root/piper/model.onnx",
                        tokens = "$root/piper/tokens.txt",
                        dataDir = "$root/piper/espeak-ng-data",
                    ),
                    numThreads = 2,
                ),
            ),
        )
    }

    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
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

    fun synthesize(text: String) = tts.generate(text, sid = 0, speed = 1.0f)
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
        try {
            while (currentCoroutineContext().isActive) {
                val wantCapture = captureRequest != null
                val wantListen = listenRequested.get()
                val wantWake = !busy && wakeAllowed()
                if (!wantCapture && !wantListen && !wantWake && collector == null) {
                    close()
                    wakeStream?.release()
                    wakeStream = null
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
                    val stream = wakeStream ?: engines.spotter.createStream().also { wakeStream = it }
                    stream.acceptWaveform(frame, SAMPLE_RATE)
                    while (engines.spotter.isReady(stream)) {
                        engines.spotter.decode(stream)
                        if (engines.spotter.getResult(stream).keyword.isNotBlank()) {
                            engines.spotter.reset(stream)
                            beep()
                            listenRequested.set(true)
                            break
                        }
                    }
                }
            }
        } finally {
            collector?.close()
            wakeStream?.release()
            close()
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
                if (heard.text.isNotBlank()) onCommand(heard)
            } finally {
                busy = false
                if (AssistantRuntime.armed.value) AssistantRuntime.status.value = "Ready"
            }
        }
    }

    /** Collects one phrase, ending after a pause in speech. */
    private class Collector(private val vad: Vad) {
        private val started = System.currentTimeMillis()
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
