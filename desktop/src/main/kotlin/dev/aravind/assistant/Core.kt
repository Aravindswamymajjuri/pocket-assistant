package dev.aravind.assistant

import com.sun.jna.platform.win32.Crypt32Util
import dev.aravind.assistant.policy.AuditChain
import dev.aravind.assistant.policy.Category
import dev.aravind.assistant.policy.ConfirmationAuthority
import dev.aravind.assistant.policy.OutboundRateLimiter
import dev.aravind.assistant.policy.WakeName
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object AppPaths {
    val data: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "PocketAssistant").also { it.mkdirs() }

    /** The folder next to the packaged EXE that holds the models and native library. */
    val install: File = System.getProperty("jpackage.app-path")?.let { File(it).parentFile.resolve("app") }
        ?: File(System.getenv("POCKET_ASSISTANT_HOME") ?: ".")

    val models: File get() = install.resolve("models")
}

/** Settings are stored in your Windows profile. The assistant itself never auto-starts. */
object Prefs {
    private val file = File(AppPaths.data, "settings.properties")
    private val values = Properties().also { p -> if (file.exists()) file.inputStream().use { input -> p.load(input) } }

    @Synchronized
    fun get(key: String): String? = values.getProperty(key)

    @Synchronized
    fun put(key: String, value: String) {
        values.setProperty(key, value)
        file.outputStream().use { values.store(it, null) }
    }

    @Synchronized
    fun clear() {
        values.clear()
        file.delete()
    }

    fun flag(key: String, default: Boolean = false) = get(key)?.toBoolean() ?: default
}

object Features {
    fun wakeWord() = Prefs.flag("wake")
    fun setWakeWord(value: Boolean) = Prefs.put("wake", value.toString())
    fun wakeName(): String = Prefs.get("name") ?: WakeName.DEFAULT
    fun setWakeName(value: String) = Prefs.put("name", value.trim())
    fun language(): String = Prefs.get("language") ?: "en"
    fun setLanguage(value: String) = Prefs.put("language", value)
    fun gate(category: Category) = Prefs.flag("gate_${category.name}")
    fun setGate(category: Category, value: Boolean) = Prefs.put("gate_${category.name}", value.toString())
    fun markUsed(category: Category) = Prefs.put("used_${category.name}", System.currentTimeMillis().toString())
    fun lastUsed(category: Category): Long = Prefs.get("used_${category.name}")?.toLongOrNull() ?: 0L
}

/** Secrets are wrapped by Windows DPAPI, so they only open for your Windows account on this laptop. */
object Keys {
    private fun file(name: String) = File(AppPaths.data, "$name.key")

    fun secret(name: String): ByteArray {
        val f = file(name)
        if (f.exists()) return Crypt32Util.cryptUnprotectData(f.readBytes())
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        f.writeBytes(Crypt32Util.cryptProtectData(secret))
        return secret
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret("data"), "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret("data"), "AES"), GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}

/** Metadata only: never message text, contact names, or audio. Each line is encrypted and chained. */
object Audit {
    private val lock = Any()
    private val file = File(AppPaths.data, "audit.log")

    private fun read(): List<Pair<String, String>> {
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map { line ->
            val text = String(Keys.decrypt(Base64.getDecoder().decode(line)), StandardCharsets.UTF_8)
            text.substringBefore('\n') to text.substringAfter('\n')
        }
    }

    fun log(event: String) {
        synchronized(lock) {
            runCatching {
                val previous = read().lastOrNull()?.second ?: AuditChain.GENESIS
                val entry = "${System.currentTimeMillis()} $event"
                val mac = AuditChain.link(Keys.secret("audit"), previous, entry)
                val blob = Keys.encrypt("$entry\n$mac".toByteArray(StandardCharsets.UTF_8))
                file.appendText(Base64.getEncoder().encodeToString(blob) + "\n")
            }
        }
    }

    fun view(): Pair<List<String>, Boolean> = synchronized(lock) {
        runCatching {
            val rows = read()
            rows.map { it.first }.takeLast(100).reversed() to AuditChain.verify(Keys.secret("audit"), rows)
        }.getOrDefault(emptyList<String>() to false)
    }
}

/** Contact numbers you enter, stored encrypted. Windows has no contact list the app can read. */
object Contacts {
    private val file = File(AppPaths.data, "contacts.enc")

    fun text(): String = if (file.exists()) {
        runCatching { String(Keys.decrypt(file.readBytes()), StandardCharsets.UTF_8) }.getOrDefault("")
    } else {
        ""
    }

    fun save(text: String) = file.writeBytes(Keys.encrypt(text.toByteArray(StandardCharsets.UTF_8)))

    /** Finds one exact name from lines like: Ravi=+919876543210 */
    fun find(name: String): String? = text().lineSequence()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
        .firstOrNull { it[0].trim().equals(name.trim(), ignoreCase = true) }?.get(1)?.trim()
}

/** A local PIN, used when Windows Hello isn't available. Only a salted PBKDF2 hash is stored. */
object Pin {
    private const val ITERATIONS = 310_000
    private var failures = 0
    private var lockedUntil = 0L

    fun isSet() = Prefs.get("pin_hash") != null

    private fun hash(pin: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin, salt, ITERATIONS, 256)).encoded

    fun set(pin: CharArray) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        Prefs.put("pin_salt", Base64.getEncoder().encodeToString(salt))
        Prefs.put("pin_hash", Base64.getEncoder().encodeToString(hash(pin, salt)))
    }

    fun check(pin: CharArray): Boolean {
        if (System.currentTimeMillis() < lockedUntil) return false
        val salt = Base64.getDecoder().decode(Prefs.get("pin_salt") ?: return false)
        val expected = Base64.getDecoder().decode(Prefs.get("pin_hash") ?: return false)
        val ok = java.security.MessageDigest.isEqual(expected, hash(pin, salt))
        if (ok) {
            failures = 0
        } else if (++failures >= 5) {
            failures = 0
            lockedUntil = System.currentTimeMillis() + 300_000
        }
        return ok
    }
}

/**
 * Runs fixed PowerShell scripts. Values are only passed as environment variables,
 * so spoken text can never become part of a command.
 */
object Ps {
    private val exe = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\WindowsPowerShell\\v1.0\\powershell.exe").path

    fun run(script: String, env: Map<String, String> = emptyMap(), timeoutSeconds: Long = 40): String {
        // The app has no console window, so setting the console encoding can fail. That must not stop the script.
        val prelude = "try { [Console]::OutputEncoding=[Text.Encoding]::UTF8 } catch {}\n\$ErrorActionPreference='Stop'\n"
        val encoded = Base64.getEncoder().encodeToString((prelude + script).toByteArray(StandardCharsets.UTF_16LE))
        val process = ProcessBuilder(exe, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
        return output.trim()
    }

    const val WINRT_AWAIT = """
Add-Type -AssemblyName System.Runtime.WindowsRuntime
${'$'}asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await(${'$'}op, ${'$'}type) { ${'$'}t = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}op)); ${'$'}t.Wait(-1) | Out-Null; ${'$'}t.Result }
"""
}

object WindowsHello {
    /** Returns true or false, or null if Hello can't be used on this laptop. */
    fun verify(reason: String): Boolean? {
        val script = Ps.WINRT_AWAIT + """
[Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime] | Out-Null
${'$'}check = Await ([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync()) ([Windows.Security.Credentials.UI.UserConsentVerifierAvailability])
if (${'$'}check -ne 'Available') { 'UNAVAILABLE'; exit }
Await ([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync(${'$'}env:PA_TEXT)) ([Windows.Security.Credentials.UI.UserConsentVerificationResult])
"""
        val result = runCatching { Ps.run(script, mapOf("PA_TEXT" to reason), 90) }.getOrDefault("UNAVAILABLE")
        return when {
            result.endsWith("Verified") -> true
            result.endsWith("Canceled") || result.endsWith("RetriesExhausted") -> false
            else -> null
        }
    }
}

object Runtime {
    @Volatile
    var armed = false

    @Volatile
    var status = "Off"

    @Volatile
    var pausedUntil = 0L

    @Volatile
    var authority: ConfirmationAuthority? = null

    val rateLimiter = OutboundRateLimiter(System::currentTimeMillis)
    val transcript = java.util.concurrent.CopyOnWriteArrayList<String>()
    var onChange: () -> Unit = {}
    var service: AssistantCore? = null

    fun isActive() = armed && System.currentTimeMillis() >= pausedUntil

    fun say(line: String) {
        transcript.add(line)
        while (transcript.size > 40) transcript.removeAt(0)
        onChange()
    }

    fun updateStatus(value: String) {
        status = value
        onChange()
    }

    fun arm() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        authority = ConfirmationAuthority(key, System::currentTimeMillis)
        pausedUntil = 0
        armed = true
        Audit.log("assistant_on")
        service = AssistantCore().also { it.start() }
        onChange()
    }

    /** Stopping must always work, so it needs no authentication and no voice check. */
    fun stopAll(reason: String) {
        armed = false
        status = "Off"
        authority?.revokeAll()
        authority = null
        Audit.log("assistant_off:$reason")
        service?.stop()
        service = null
        onChange()
    }

    fun deleteEverything() {
        stopAll("wipe")
        Prefs.clear()
        AppPaths.data.deleteRecursively()
        AppPaths.data.mkdirs()
        transcript.clear()
        onChange()
    }
}

fun sha256(file: File, onProgress: (Long) -> Unit = {}): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20)
    var total = 0L
    Files.newInputStream(Paths.get(file.path)).use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
            onProgress(total)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
