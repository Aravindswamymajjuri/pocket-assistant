package dev.aravind.assistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.aravind.assistant.policy.AuditChain
import dev.aravind.assistant.policy.Category
import dev.aravind.assistant.policy.ConfirmationAuthority
import dev.aravind.assistant.policy.OutboundRateLimiter
import dev.aravind.assistant.policy.WakeName
import dev.aravind.assistant.policy.WakeSensitivity
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

    /** Holds the end of a pause. Long.MAX_VALUE means paused until you resume. */
    val pausedUntil = MutableStateFlow(0L)

    fun pause(context: Context, minutes: Long?) {
        pausedUntil.value = if (minutes == null) Long.MAX_VALUE else System.currentTimeMillis() + minutes * 60_000
        Audit.log(context, "paused")
    }

    fun resume(context: Context) {
        pausedUntil.value = 0L
        Audit.log(context, "resumed")
    }

    /** True only when the assistant is on and not paused. */
    fun isActive(): Boolean = armed.value && System.currentTimeMillis() >= pausedUntil.value

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
        pausedUntil.value = 0L
        armed.value = true
        status.value = "Starting"
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

/** A separate software switch for each tool category. All are on until turned off. */
object Gates {
    private fun prefs(context: Context) = context.getSharedPreferences("gates", Context.MODE_PRIVATE)
    fun enabled(context: Context, category: Category) = prefs(context).getBoolean(category.name, true)
    fun set(context: Context, category: Category, value: Boolean) =
        prefs(context).edit().putBoolean(category.name, value).apply()

    fun markUsed(context: Context, category: Category) =
        prefs(context).edit().putLong("used_${category.name}", System.currentTimeMillis()).apply()

    fun lastUsed(context: Context, category: Category): Long = prefs(context).getLong("used_${category.name}", 0L)
}

/** Feature switches. The wake word is on by default, and AI calling stays off until you turn it on. */
object Features {
    private fun prefs(context: Context) = context.getSharedPreferences("features", Context.MODE_PRIVATE)
    fun wakeWord(context: Context) = prefs(context).getBoolean("wake", true)
    fun setWakeWord(context: Context, value: Boolean) = prefs(context).edit().putBoolean("wake", value).apply()
    fun aiCalls(context: Context) = prefs(context).getBoolean("calls", false)
    fun setAiCalls(context: Context, value: Boolean) = prefs(context).edit().putBoolean("calls", value).apply()
    fun wakeSensitivity(context: Context): WakeSensitivity =
        runCatching { WakeSensitivity.valueOf(prefs(context).getString("sensitivity", null) ?: "HIGH") }.getOrDefault(WakeSensitivity.HIGH)
    fun setWakeSensitivity(context: Context, value: WakeSensitivity) = prefs(context).edit().putString("sensitivity", value.name).apply()

    fun wakeName(context: Context): String = prefs(context).getString("name", null) ?: WakeName.DEFAULT
    fun setWakeName(context: Context, value: String) = prefs(context).edit().putString("name", value.trim()).apply()

    /** The Whisper language code: en, hi, te, or auto. */
    fun language(context: Context): String = prefs(context).getString("language", null) ?: "en"
    fun setLanguage(context: Context, value: String) = prefs(context).edit().putString("language", value).apply()

    fun autoPause(context: Context) = prefs(context).getBoolean("auto_pause", true)
    fun setAutoPause(context: Context, value: Boolean) = prefs(context).edit().putBoolean("auto_pause", value).apply()
}

/** Stops wake-word listening when the battery is low or the phone is too hot. */
object PowerGuard {
    private const val LOW_BATTERY_PERCENT = 15
    private var checkedAt = 0L
    private var result = false

    fun shouldPause(context: Context): Boolean {
        if (!Features.autoPause(context)) return false
        val now = System.currentTimeMillis()
        if (now - checkedAt < 30_000) return result
        checkedAt = now
        val battery = context.getSystemService(BatteryManager::class.java)
        val low = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0 until LOW_BATTERY_PERCENT && !battery.isCharging
        val hot = context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        result = low || hot
        return result
    }
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

/** Metadata only: never message text, contact names, or audio. Entries are chained to reveal edits. */
object Audit {
    private val lock = Any()

    fun log(context: Context, event: String) {
        synchronized(lock) {
            runCatching {
                val key = Keys.derivedSecret(context, "audit")
                val dao = Db.get(context).audit()
                val previous = dao.last()?.mac ?: AuditChain.GENESIS
                val entry = "${System.currentTimeMillis()} $event"
                dao.insert(AuditRow(entry = entry, mac = AuditChain.link(key, previous, entry)))
            }
        }
    }

    /** Returns the newest entries and whether the chain is intact. */
    fun view(context: Context): Pair<List<String>, Boolean> = synchronized(lock) {
        runCatching {
            val rows = Db.get(context).audit().all()
            val intact = AuditChain.verify(Keys.derivedSecret(context, "audit"), rows.map { it.entry to it.mac })
            rows.map { it.entry }.takeLast(100).reversed() to intact
        }.getOrDefault(emptyList<String>() to false)
    }
}

object DataWipe {
    fun deleteEverything(context: Context) {
        AssistantRuntime.stopAll(context, "wipe")
        GemmaEngine.unload()
        Db.delete(context)
        context.filesDir.deleteRecursively()
        context.noBackupFilesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
        context.getSharedPreferences("gates", Context.MODE_PRIVATE).edit().clear().apply()
        listOf("model", "features", "calls").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply()
        }
        GemmaEngine.clearHistory()
        Keys.destroy()
        AssistantRuntime.transcript.value = emptyList()
    }
}
