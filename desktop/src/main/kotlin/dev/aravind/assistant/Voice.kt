package dev.aravind.assistant

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
import dev.aravind.assistant.policy.ModelPath
import dev.aravind.assistant.policy.Script
import dev.aravind.assistant.policy.ScriptDetect
import dev.aravind.assistant.policy.SpeechPrompt
import dev.aravind.assistant.policy.Tool
import dev.aravind.assistant.policy.VoiceMatch
import dev.aravind.assistant.policy.WakeName
import dev.aravind.assistant.policy.WakeSensitivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

const val SAMPLE_RATE = 16_000

/** whisper.cpp through the JNI code shared with the phone app. */
object WhisperCpp {
    private var handle = 0L

    init {
        System.load(AppPaths.install.resolve("native/whisperjni.dll").absolutePath)
    }

    private external fun nativeInit(model: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, prompt: String, threads: Int): String
    private external fun nativeFree(handle: Long)

    @Synchronized
    fun transcribe(samples: FloatArray, language: String): String {
        if (handle == 0L) handle = nativeInit(AppPaths.models.resolve("whisper/ggml-base-q5_1.bin").absolutePath)
        check(handle != 0L) { "The speech model failed to load" }
        return nativeTranscribe(handle, samples, language, SpeechPrompt.text(Features.wakeName()), java.lang.Runtime.getRuntime().availableProcessors().coerceIn(2, 8)).trim()
    }

    @Synchronized
    fun release() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }
}

class VoiceEngines {
    private val root = AppPaths.models.absolutePath

    val spotter by lazy {
        KeywordSpotter(
            KeywordSpotterConfig.builder()
                .setFeatureConfig(FeatureConfig.builder().setSampleRate(SAMPLE_RATE).setFeatureDim(80).build())
                .setOnlineModelConfig(
                    OnlineModelConfig.builder()
                        .setTransducer(
                            OnlineTransducerModelConfig.builder()
                                .setEncoder("$root/kws/encoder.onnx")
                                .setDecoder("$root/kws/decoder.onnx")
                                .setJoiner("$root/kws/joiner.onnx")
                                .build(),
                        )
                        .setTokens("$root/kws/tokens.txt")
                        .setModelType("zipformer2")
                        .setNumThreads(1)
                        .build(),
                )
                .setKeywordsFile("$root/kws/keywords.txt")
                .setNumTrailingBlanks(1)
                .build(),
        )
    }

    fun wakeKeywords(): String {
        val tokenizer = BpeTokenizer.parse(File(root, "kws/bpe.vocab").readLines().asSequence())
        return wakeKeywords(Features.wakeName(), WakeSensitivity.HIGH)
    }

    fun wakeKeywords(name: String, sensitivity: WakeSensitivity): String {
        val tokenizer = BpeTokenizer.parse(File(root, "kws/bpe.vocab").readLines().asSequence())
        return WakeName.keywords(tokenizer, name, sensitivity)
            ?: requireNotNull(WakeName.keywords(tokenizer, WakeName.DEFAULT, sensitivity))
    }

    fun newVad(): Vad = Vad(
        VadModelConfig.builder()
            .setSileroVadModelConfig(
                SileroVadModelConfig.builder()
                    .setModel("$root/vad/silero_vad.onnx")
                    .setMinSilenceDuration(0.7f)
                    .setMinSpeechDuration(0.25f)
                    .setMaxSpeechDuration(14f)
                    .build(),
            )
            .setSampleRate(SAMPLE_RATE)
            .build(),
    )

    private val extractor by lazy {
        SpeakerEmbeddingExtractor(
            SpeakerEmbeddingExtractorConfig.builder().setModel("$root/speaker/model.onnx").setNumThreads(2).build(),
        )
    }

    private fun piper(folder: String) = OfflineTts(
        OfflineTtsConfig.builder()
            .setModel(
                OfflineTtsModelConfig.builder()
                    .setVits(
                        OfflineTtsVitsModelConfig.builder()
                            .setModel("$root/$folder/model.onnx")
                            .setTokens("$root/$folder/tokens.txt")
                            .setDataDir("$root/piper/espeak-ng-data")
                            .build(),
                    )
                    .setNumThreads(2)
                    .build(),
            )
            .build(),
    )

    private val english by lazy { piper("piper") }
    private val hindi by lazy { piper("piper-hi") }

    fun synthesize(text: String, useHindi: Boolean) = (if (useHindi) hindi else english).generate(text, 0, 1.0f)

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
}

/** Your voiceprint is stored encrypted. Raw audio is never written to disk. */
object VoiceId {
    private val file = File(AppPaths.data, "voiceprint.enc")

    fun isEnrolled() = file.exists()

    fun delete() {
        file.delete()
    }

    fun save(prints: List<FloatArray>) {
        val size = prints.first().size
        val mean = FloatArray(size) { i -> prints.sumOf { it[i].toDouble() }.toFloat() / prints.size }
        val buffer = ByteBuffer.allocate(size * 4)
        mean.forEach { buffer.putFloat(it) }
        file.writeBytes(Keys.encrypt(buffer.array()))
    }

    fun matches(sample: FloatArray?): Boolean {
        if (sample == null || !isEnrolled()) return false
        val stored = runCatching { Keys.decrypt(file.readBytes()) }.getOrNull() ?: return false
        val buffer = ByteBuffer.wrap(stored)
        return VoiceMatch.isMatch(FloatArray(stored.size / 4) { buffer.getFloat() }, sample)
    }
}

data class Heard(val text: String, val verified: Boolean)

/** The only code that opens the microphone. It stays closed unless it's needed. */
class VoiceLoop(private val engines: VoiceEngines, private val onCommand: (Heard) -> Unit) {
    private val listenRequested = AtomicBoolean(false)
    private val running = AtomicBoolean(true)
    private var thread: Thread? = null

    @Volatile
    var speaking = false

    @Volatile
    private var busy = false

    @Volatile
    private var wakeChanged = false

    @Volatile
    private var captureResult: java.util.concurrent.CompletableFuture<FloatArray?>? = null

    fun requestListen() = listenRequested.set(true)

    fun reloadWakeName() {
        wakeChanged = true
    }

    fun capture(timeoutSeconds: Long): FloatArray? {
        val request = java.util.concurrent.CompletableFuture<FloatArray?>()
        captureResult = request
        val result = runCatching { request.get(timeoutSeconds, TimeUnit.SECONDS) }.getOrNull()
        captureResult = null
        return result
    }

    fun understand(samples: FloatArray, language: String? = null): Heard {
        val text = WhisperCpp.transcribe(samples, language ?: Features.language())
        return Heard(text, VoiceId.matches(engines.embedding(samples)))
    }

    fun start() {
        thread = Thread({ loop() }, "voice-loop").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.join(1500)
    }

    private fun open(): TargetDataLine? = runCatching {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        (AudioSystem.getLine(javax.sound.sampled.DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine).also {
            it.open(format, 8000)
            it.start()
        }
    }.getOrNull()

    private fun loop() {
        val bytes = ByteArray(3200)
        var mic: TargetDataLine? = null
        var wakeStream: OnlineStream? = null
        var collector: Collector? = null
        try {
            while (running.get()) {
                val wantCapture = captureResult != null
                val wantListen = listenRequested.get()
                val wantWake = !busy && Features.wakeWord() && Runtime.isActive()
                if (!wantCapture && !wantListen && !wantWake && collector == null) {
                    mic?.close()
                    mic = null
                    Thread.sleep(200)
                    continue
                }
                val line = mic ?: open().also { mic = it }
                if (line == null) {
                    Runtime.updateStatus("Microphone unavailable")
                    Thread.sleep(1000)
                    continue
                }
                val count = line.read(bytes, 0, bytes.size)
                if (count <= 0 || speaking) continue
                val shorts = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frame = FloatArray(shorts.remaining()) { shorts.get() / 32768f }

                if (collector == null && (wantCapture || wantListen)) {
                    listenRequested.set(false)
                    collector = Collector(engines.newVad())
                    Runtime.updateStatus("Listening")
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
                    val stream = wakeStream ?: engines.spotter.createStream(engines.wakeKeywords()).also { wakeStream = it }
                    stream.acceptWaveform(frame, SAMPLE_RATE)
                    while (engines.spotter.isReady(stream)) {
                        engines.spotter.decode(stream)
                        if (engines.spotter.getResult(stream).keyword.isNotBlank()) {
                            engines.spotter.reset(stream)
                            java.awt.Toolkit.getDefaultToolkit().beep()
                            listenRequested.set(true)
                            break
                        }
                    }
                }
            }
        } finally {
            collector?.close()
            wakeStream?.release()
            mic?.close()
        }
    }

    private fun deliver(samples: FloatArray?) {
        val request = captureResult
        if (request != null) {
            request.complete(samples)
            return
        }
        if (samples == null) {
            Runtime.updateStatus("Ready")
            return
        }
        busy = true
        Runtime.updateStatus("Working")
        Thread {
            try {
                val heard = understand(samples)
                if (heard.text.isNotBlank()) onCommand(heard)
            } catch (e: Exception) {
                Audit.log("speech_failed:${e.javaClass.simpleName}")
            } finally {
                busy = false
                if (Runtime.armed) Runtime.updateStatus("Ready")
            }
        }.also { it.isDaemon = true }.start()
    }

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
            return if (speech.isNotEmpty() && !vad.isSpeechDetected()) merged() else null
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

/** Piper speaks English and Hindi. Telugu uses a Windows voice if one is installed. */
class VoiceOutput(private val engines: VoiceEngines, private val loop: () -> VoiceLoop?) {
    private val lock = Any()

    fun say(text: String) {
        Runtime.say("Assistant: $text")
        synchronized(lock) {
            loop()?.speaking = true
            try {
                when (ScriptDetect.of(text)) {
                    Script.TELUGU -> windowsVoice(text, "te-IN")
                    Script.DEVANAGARI -> piper(text, true)
                    Script.LATIN -> piper(text, false)
                }
            } catch (e: Exception) {
                Audit.log("speech_output_failed:${e.javaClass.simpleName}")
            } finally {
                Thread.sleep(250)
                loop()?.speaking = false
            }
        }
    }

    private fun piper(text: String, hindi: Boolean) {
        val audio = engines.synthesize(text, hindi)
        val format = AudioFormat(audio.sampleRate.toFloat(), 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format)
        line.start()
        val samples = audio.samples
        val bytes = ByteArray(samples.size * 2)
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { out.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        var offset = 0
        while (offset < bytes.size && Runtime.armed) {
            val chunk = minOf(8192, bytes.size - offset)
            line.write(bytes, offset, chunk)
            offset += chunk
        }
        line.drain()
        line.close()
    }

    private fun windowsVoice(text: String, culture: String) {
        val result = Ps.run(
            """
Add-Type -AssemblyName System.Speech
${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
${'$'}v = ${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.VoiceInfo.Culture.Name -eq ${'$'}env:PA_CULTURE } | Select-Object -First 1
if (${'$'}null -eq ${'$'}v) { 'NO_VOICE'; exit }
${'$'}s.SelectVoice(${'$'}v.VoiceInfo.Name)
${'$'}s.Speak(${'$'}env:PA_TEXT)
            """.trimIndent(),
            mapOf("PA_TEXT" to text, "PA_CULTURE" to culture),
            120,
        )
        if (result.contains("NO_VOICE")) Runtime.say("Add the Telugu voice in Windows speech settings to hear this reply")
    }
}

/** Gemma only proposes actions. It sees your live words and its own earlier replies. */
object Gemma {
    private val lock = Any()
    private var engine: Engine? = null
    private val history = ArrayDeque<String>()

    fun path(): String? = Prefs.get("model_path")

    fun problem(): String? {
        val p = path() ?: return "No model selected"
        val file = File(p)
        if (!file.exists()) return "The model file is missing: $p"
        if (file.length().toString() != Prefs.get("model_size") || file.lastModified().toString() != Prefs.get("model_modified")) {
            return "The model file changed, so select it again"
        }
        return null
    }

    fun use(typed: String, onProgress: (Long) -> Unit): String {
        val path = typed.trim()
        ModelPath.windowsProblem(path)?.let { error(it) }
        val file = File(path)
        check(file.isFile) { "No file at that path" }
        check(file.length() > 100L * 1024 * 1024) { "That file is too small to be a Gemma model" }
        val sha = sha256(file, onProgress)
        Prefs.put("model_path", file.path)
        Prefs.put("model_sha256", sha)
        Prefs.put("model_size", file.length().toString())
        Prefs.put("model_modified", file.lastModified().toString())
        unload()
        return sha
    }

    private fun systemPrompt(): String {
        val tools = Tool.entries.filter { it in WindowsTools.supported }.joinToString("\n") { tool ->
            "- ${tool.id}(${tool.params.keys.joinToString(", ")}): ${tool.description}"
        }
        return """
            Your name is ${Features.wakeName()}. You are a friendly voice assistant on the user's Windows laptop.
            Reply with one JSON object and no other text. Keep the "reply" text in the language the user spoke,
            including Hindi or Telugu. Tool ids and argument names always stay in English.
            To answer in words, use {"reply":"short spoken answer"}.
            To use one tool, use {"tool":"tool_id","args":{"name":"value"}}.
            For several steps in order, use {"plan":[{"tool":"tool_id","args":{}},{"tool":"tool_id","args":{}}]}.
            Use at most five steps. Use only the tools below. Never invent a tool. Use whole numbers for numbers.
            $tools
        """.trimIndent()
    }

    private fun load(): Engine = engine ?: Engine(
        EngineConfig(modelPath = requireNotNull(path()), backend = Backend.CPU(), cacheDir = File(AppPaths.data, "cache").also { it.mkdirs() }.path),
    ).also {
        it.initialize()
        engine = it
    }

    private fun complete(prompt: String): String = load().createConversation().use { it.sendMessage(prompt).toString() }

    fun ask(text: String): String = synchronized(lock) {
        problem()?.let { error(it) }
        val earlier = if (history.isEmpty()) "" else "Earlier in this chat:\n${history.joinToString("\n")}\n\n"
        val answer = complete("${systemPrompt()}\n\n${earlier}User said: $text")
        history.addLast("User: ${text.take(200)}")
        history.addLast("Assistant: ${answer.take(200)}")
        while (history.size > 6) history.removeFirst()
        answer
    }

    fun test(): String = synchronized(lock) {
        problem()?.let { error(it) }
        complete("Reply with one short friendly sentence saying you are ready.")
    }

    fun unload() = synchronized(lock) {
        runCatching { engine?.close() }
        engine = null
        history.clear()
    }
}

/** Runs the voice system while the assistant is on. */
class AssistantCore {
    private val engines = VoiceEngines()
    var loop: VoiceLoop? = null
        private set
    private val output = VoiceOutput(engines) { loop }

    fun start() {
        val agent = Agent(output::say) { hear() }
        loop = VoiceLoop(engines) { agent.handle(it) }.also { it.start() }
        Runtime.updateStatus("Ready")
    }

    private fun hear(): Heard? {
        val active = loop ?: return null
        val samples = active.capture(9) ?: return null
        return active.understand(samples, "en")
    }

    fun enrollVoice() {
        val active = loop ?: return
        Thread {
            val prints = ArrayList<FloatArray>()
            listOf(
                "Say: my voice unlocks this laptop",
                "Say: the quick brown fox jumps over the lazy dog",
                "Say: please open my messages and read them",
                "Say: call my friend and tell him I am late",
                "Say: today is a good day to build something",
            ).forEach { phrase ->
                output.say(phrase)
                active.capture(9)?.let { samples -> engines.embedding(samples)?.let { prints += it } }
            }
            if (prints.size >= 3) {
                VoiceId.save(prints)
                Audit.log("voice_enrolled")
                output.say("Your voice is saved")
            } else {
                output.say("I couldn't hear enough, so please try again")
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stop() {
        loop?.stop()
        loop = null
        Gemma.unload()
        WhisperCpp.release()
    }
}
