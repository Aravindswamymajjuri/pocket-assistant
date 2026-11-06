package dev.aravind.assistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.aravind.assistant.policy.AuditChain
import dev.aravind.assistant.policy.Category
import dev.aravind.assistant.policy.ConfirmationAuthority
import dev.aravind.assistant.policy.OutboundRateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AssistantApp : Application()

/**
 * This state lives in memory only. After a reboot or crash the assistant is off,
 * and it stays off until Aravind opens the app and authenticates.
 */
object AssistantRuntime {
    val armed = MutableStateFlow(false)
    val status = MutableStateFlow("Off")
    val transcript = MutableStateFlow<List<String>>(emptyList())
    val rateLimiter = OutboundRateLimiter(System::currentTimeMillis)

    @Volatile
    var authority: ConfirmationAuthority? = null

    @Volatile
    var pendingApproval: CompletableDeferred<Boolean>? = null

    fun say(line: String) {
        transcript.value = (transcript.value + line).takeLast(40)
    }

    fun inPhoneCall(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        return audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    fun arm(context: Context) {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        authority = ConfirmationAuthority(key, System::currentTimeMillis)
        armed.value = true
        status.value = "Ready"
        Audit.log(context, "assistant_on")
        context.startForegroundService(Intent(context, AssistantService::class.java))
    }

    /** Stopping must always work, so it needs no authentication and no voice check. */
    fun stopAll(context: Context, reason: String) {
        armed.value = false
        status.value = "Off"
        authority?.revokeAll()
        authority = null
        pendingApproval?.complete(false)
        pendingApproval = null
        Audit.log(context, "assistant_off:$reason")
        context.stopService(Intent(context, AssistantService::class.java))
    }
}

/** A separate software switch for each tool category. All are off until turned on. */
object Gates {
    private fun prefs(context: Context) = context.getSharedPreferences("gates", Context.MODE_PRIVATE)
    fun enabled(context: Context, category: Category) = prefs(context).getBoolean(category.name, false)
    fun set(context: Context, category: Category, value: Boolean) =
        prefs(context).edit().putBoolean(category.name, value).apply()
}

/** Keys are held in Android Keystore, so app files alone can't be decrypted elsewhere. */
object Keys {
    private const val ALIAS = "assistant_master"

    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    /** Returns a stable 32-byte secret, stored encrypted under the Keystore key. */
    fun derivedSecret(context: Context, name: String): ByteArray {
        val file = File(context.noBackupFilesDir, "$name.key")
        if (file.exists()) return decrypt(file.readBytes())
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeBytes(encrypt(secret))
        return secret
    }

    fun destroy() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }
}

/** Metadata only: never message text, contact names, or audio. Each line is encrypted and chained. */
object Audit {
    private val lock = Any()

    private fun file(context: Context) = File(context.noBackupFilesDir, "audit.log")

    fun log(context: Context, event: String) {
        synchronized(lock) {
            runCatching {
                val key = Keys.derivedSecret(context, "audit")
                val entries = read(context)
                val previous = entries.lastOrNull()?.second ?: AuditChain.GENESIS
                val entry = "${System.currentTimeMillis()} $event"
                val mac = AuditChain.link(key, previous, entry)
                val blob = Keys.encrypt("$entry\n$mac".toByteArray())
                file(context).appendText(Base64.encodeToString(blob, Base64.NO_WRAP) + "\n")
            }
        }
    }

    private fun read(context: Context): List<Pair<String, String>> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.map { line ->
            val text = String(Keys.decrypt(Base64.decode(line, Base64.NO_WRAP)))
            text.substringBefore('\n') to text.substringAfter('\n')
        }
    }

    /** Returns the newest entries and whether the chain is intact. */
    fun view(context: Context): Pair<List<String>, Boolean> = synchronized(lock) {
        runCatching {
            val entries = read(context)
            val intact = AuditChain.verify(Keys.derivedSecret(context, "audit"), entries)
            entries.map { it.first }.takeLast(100).reversed() to intact
        }.getOrDefault(emptyList<String>() to false)
    }
}

object DataWipe {
    fun deleteEverything(context: Context) {
        AssistantRuntime.stopAll(context, "wipe")
        GemmaEngine.unload()
        context.filesDir.deleteRecursively()
        context.noBackupFilesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
        context.getSharedPreferences("gates", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("model", Context.MODE_PRIVATE).edit().clear().apply()
        Keys.destroy()
        AssistantRuntime.transcript.value = emptyList()
    }
}
