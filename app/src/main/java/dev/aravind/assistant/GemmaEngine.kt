package dev.aravind.assistant

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.aravind.assistant.policy.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object ModelStore {
    fun file(context: Context) = File(context.filesDir, "models/gemma.litertlm")

    fun hash(context: Context): String? =
        context.getSharedPreferences("model", Context.MODE_PRIVATE).getString("sha256", null)

    fun isReady(context: Context): Boolean = file(context).exists() && hash(context) != null

    /** Copies a model you picked into private storage and records its SHA-256. */
    suspend fun import(context: Context, uri: Uri, onProgress: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "gemma.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the file" }
            temp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        check(temp.renameTo(target)) { "Could not save the model" }
        context.getSharedPreferences("model", Context.MODE_PRIVATE).edit().putString("sha256", sha).apply()
        sha
    }
}

/**
 * Gemma only proposes actions. It sees your live words and nothing from messages,
 * notifications, or screens, and everything it returns goes through the policy engine.
 */
object GemmaEngine {
    private val mutex = Mutex()
    private var engine: Engine? = null

    @Volatile
    private var unloadWhenIdle = false

    private fun systemPrompt(): String {
        val tools = Tool.entries.joinToString("\n") { tool ->
            "- ${tool.id}(${tool.params.keys.joinToString(", ")}): ${tool.description}"
        }
        return """
            You are a helpful voice assistant on the user's phone. Reply with one JSON object and no other text.
            To answer in words, use {"reply":"short spoken answer"}.
            To use a phone tool, use {"tool":"tool_id","args":{"name":"value"}}.
            Use only the tools below. Never invent a tool. Use whole numbers for numbers.
            $tools
        """.trimIndent()
    }

    /** Returns the raw model text, or null if no verified model has been imported. */
    suspend fun ask(context: Context, text: String): String? = withContext(Dispatchers.Default) {
        if (!ModelStore.isReady(context)) return@withContext null
        mutex.withLock {
            val current = engine ?: Engine(
                EngineConfig(modelPath = ModelStore.file(context).absolutePath, backend = Backend.CPU()),
            ).also {
                it.initialize()
                engine = it
            }
            // A new conversation each time: earlier turns can't influence the next action.
            try {
                current.createConversation().use { conversation ->
                    conversation.sendMessage("${systemPrompt()}\n\nUser said: $text").toString()
                }
            } finally {
                if (unloadWhenIdle) closeEngine()
            }
        }
    }

    /** If a reply is still running, the engine closes as soon as that reply ends. */
    fun unload() {
        if (!mutex.tryLock()) {
            unloadWhenIdle = true
            return
        }
        try {
            closeEngine()
        } finally {
            mutex.unlock()
        }
    }

    private fun closeEngine() {
        unloadWhenIdle = false
        val current = engine ?: return
        engine = null
        runCatching { current.close() }
    }
}
