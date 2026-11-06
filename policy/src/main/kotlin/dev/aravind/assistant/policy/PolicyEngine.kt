package dev.aravind.assistant.policy

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SecretRedactor {
    private val rules = listOf(
        Regex("(?i)\\b(otp|one[- ]time|verification|security|login|auth(?:entication)?)\\b[^\\n]{0,30}?\\b\\d{4,8}\\b"),
        Regex("(?i)\\b\\d{4,8}\\b[^\\n]{0,30}?\\b(otp|one[- ]time|verification code|is your code)\\b"),
        Regex("(?i)\\b(password|passcode|passwd|pwd|pin|cvv)\\b\\s*(?:is|:|=)?\\s*\\S+"),
        Regex("\\b(?:\\d[ -]?){13,19}\\b"),
    )

    fun containsSecret(text: String): Boolean = rules.any { it.containsMatchIn(text) }

    fun redact(text: String): String = rules.fold(text) { acc, rule -> rule.replace(acc, "[hidden]") }
}

object BlockedApps {
    private val finance = setOf(
        "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp",
        "com.dreamplug.androidapp", "com.sbi.lotusintouch", "com.snapwork.hdfc", "com.csam.icici.bank.imobile",
        "com.axis.mobile", "com.msf.kbank.mobile", "com.google.android.apps.walletnfcrel",
        "com.x8bit.bitwarden", "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
    )
    private val system = setOf(
        "com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.vending",
        "com.google.android.gms", "com.coloros.safecenter", "com.oplus.safecenter",
        // Windows programs that could change the system or run commands.
        "cmd.exe", "powershell.exe", "pwsh.exe", "windowsterminal.exe", "wt.exe", "conhost.exe", "regedit.exe",
        "mmc.exe", "taskmgr.exe", "systemsettings.exe", "control.exe", "consent.exe", "msiexec.exe",
        "secpol.msc", "gpedit.msc", "wscript.exe", "cscript.exe", "mshta.exe", "rundll32.exe",
        "1password.exe", "bitwarden.exe", "keepass.exe", "keepassxc.exe",
    )
    private val labelWords = Regex(
        "(?i)\\b(bank|banking|upi|wallet|pay|paytm|phonepe|gpay|password|passwords|authenticator|crypto|trading|broker|" +
            "terminal|powershell|command prompt|cmd|registry|regedit|task manager|bitlocker)\\b",
    )

    /** These apps must not be opened by the assistant. */
    fun blocksOpening(packageName: String?, label: String?): Boolean =
        packageName in finance || (label != null && labelWords.containsMatchIn(label))

    /** These apps may be open, but the assistant must never touch their screens. */
    fun blocksAutomation(packageName: String?, ownPackage: String): Boolean =
        packageName == null || packageName == ownPackage || packageName in finance || packageName in system
}

object PhoneTarget {
    private val allowed = Regex("^\\+?[0-9][0-9 ()-]{2,18}$")
    fun looksLikeNumber(target: String) = target.any { it.isDigit() } && target.none { it.isLetter() }
    fun isSafe(target: String) = allowed.matches(target.trim())
    fun digits(target: String) = target.filter { it.isDigit() }
}

enum class Source { LIVE_USER, UNTRUSTED }

data class PolicyContext(
    val armed: Boolean,
    val source: Source,
    val inPhoneCall: Boolean,
    val tainted: Boolean,
    val voiceVerified: Boolean,
    val gateEnabled: (Category) -> Boolean,
    val originalUtterance: String,
    val foregroundPackage: String?,
    val ownPackage: String,
    val outboundAllowed: Boolean,
)

sealed interface Decision {
    data object Allow : Decision
    data class Confirm(val readback: String) : Decision
    data class Biometric(val readback: String) : Decision
    data class Deny(val reason: String) : Decision
}

object Readback {
    /** Built only from validated arguments. Model text is never used as the confirmation wording. */
    fun of(call: ToolCall): String = when (call.tool) {
        Tool.SEND_SMS -> "Send SMS to ${call.text("to")}: \"${call.text("body")}\""
        Tool.SEND_WHATSAPP -> "Send WhatsApp to ${call.text("to")}: \"${call.text("body")}\""
        Tool.PLACE_CALL -> "Call ${call.text("to")}"
        Tool.TAP_TEXT -> "Tap \"${call.text("text")}\" on the current screen"
        Tool.READ_NOTIFICATIONS -> "Read your notifications aloud"
        Tool.WIFI -> "Turn Wi-Fi ${if (call.bool("on")) "on" else "off"}"
        Tool.BLUETOOTH -> "Turn Bluetooth ${if (call.bool("on")) "on" else "off"}"
        Tool.DO_NOT_DISTURB -> "Turn Do Not Disturb ${if (call.bool("on")) "on" else "off"}"
        else -> call.tool.description
    }
}

object PolicyEngine {
    fun evaluate(call: ToolCall, ctx: PolicyContext): Decision {
        if (!ctx.armed) return Decision.Deny("Assistant is off")
        if (ctx.source != Source.LIVE_USER) return Decision.Deny("Only a live command from you can start an action")
        if (ctx.inPhoneCall) return Decision.Deny("Phone actions are disabled during a call")
        if (!ctx.gateEnabled(call.tool.category)) return Decision.Deny("This tool category is turned off")
        if (call.tool.risk >= Risk.L3_SECURITY) return Decision.Deny("Do this manually")

        call.args.values.filterIsInstance<String>().firstOrNull(SecretRedactor::containsSecret)?.let {
            return Decision.Deny("That looks like a password or code, so I won't handle it")
        }

        if (call.tool == Tool.OPEN_APP && BlockedApps.blocksOpening(null, call.text("name"))) {
            return Decision.Deny("Payment, banking, and password apps must be used manually")
        }
        if (call.tool.category == Category.SCREEN && call.tool != Tool.GO_HOME &&
            BlockedApps.blocksAutomation(ctx.foregroundPackage, ctx.ownPackage)
        ) {
            return Decision.Deny("I can't control this screen")
        }

        if (call.tool.hasRecipient) {
            val target = call.text("to")
            if (PhoneTarget.looksLikeNumber(target) && !PhoneTarget.isSafe(target)) {
                return Decision.Deny("That number contains blocked codes")
            }
            if (target.any { it == '*' || it == '#' }) return Decision.Deny("That number contains blocked codes")
            if (PhoneTarget.looksLikeNumber(target) && PhoneTarget.digits(target).length < 10) {
                return Decision.Deny("Short codes and premium numbers are blocked")
            }
            if (ctx.tainted && !ctx.originalUtterance.contains(target, ignoreCase = true)) {
                return Decision.Deny("Untrusted content can't choose a recipient")
            }
            if (!ctx.outboundAllowed) return Decision.Deny("Too many messages or calls. Try again later")
        }

        return when (call.tool.risk) {
            Risk.L0_SAFE -> Decision.Allow
            Risk.L1_PERSONAL -> if (ctx.voiceVerified) Decision.Allow else Decision.Biometric(Readback.of(call))
            Risk.L2_OUTBOUND ->
                if (ctx.voiceVerified && !ctx.tainted) Decision.Confirm(Readback.of(call))
                else Decision.Biometric(Readback.of(call))
            else -> Decision.Deny("Do this manually")
        }
    }
}

/** Limits one task, so a loop or an injected instruction can't keep acting. */
class TaskLimits(private val now: () -> Long, private val maxSteps: Int = 10, private val maxMillis: Long = 90_000) {
    private val started = now()
    private var steps = 0

    fun tryStep(): Boolean {
        steps++
        return steps <= maxSteps && now() - started <= maxMillis
    }
}

class OutboundRateLimiter(
    private val now: () -> Long,
    private val maxEvents: Int = 5,
    private val windowMillis: Long = 600_000,
) {
    private val events = ArrayDeque<Long>()

    fun allowed(): Boolean {
        prune()
        return events.size < maxEvents
    }

    fun record() {
        prune()
        events.addLast(now())
    }

    private fun prune() {
        while (events.isNotEmpty() && now() - events.first() > windowMillis) events.removeFirst()
    }
}

/** A one-time token tied to the exact action. Any change to the action invalidates it. */
class ConfirmationAuthority(
    private val key: ByteArray,
    private val now: () -> Long,
    private val lifetimeMillis: Long = 30_000,
) {
    data class Token(val nonce: String, val expiresAt: Long, val mac: String)

    private val issued = HashSet<String>()
    private val random = SecureRandom()

    fun issue(call: ToolCall, foregroundPackage: String?): Token {
        val bytes = ByteArray(16).also(random::nextBytes)
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        val expiresAt = now() + lifetimeMillis
        issued += nonce
        return Token(nonce, expiresAt, sign(call, foregroundPackage, nonce, expiresAt))
    }

    /** Consumes the token, so it can never be replayed. */
    fun verify(token: Token, call: ToolCall, foregroundPackage: String?): Boolean {
        if (!issued.remove(token.nonce)) return false
        if (now() > token.expiresAt) return false
        val expected = sign(call, foregroundPackage, token.nonce, token.expiresAt)
        return MessageDigest.isEqual(expected.toByteArray(), token.mac.toByteArray())
    }

    fun revokeAll() = issued.clear()

    private fun sign(call: ToolCall, foregroundPackage: String?, nonce: String, expiresAt: Long): String {
        val args = call.args.toSortedMap().entries.joinToString("\u0000") { "${it.key}=${it.value}" }
        return hmac(key, "${call.tool.id}\u0000$args\u0000$foregroundPackage\u0000$nonce\u0000$expiresAt")
    }
}

internal fun hmac(key: ByteArray, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Tamper-evident log: each entry's MAC covers the previous one, so edits and deletions break the chain. */
object AuditChain {
    const val GENESIS = "genesis"

    fun link(key: ByteArray, previous: String, entry: String): String = hmac(key, "$previous\u0000$entry")

    fun verify(key: ByteArray, entries: List<Pair<String, String>>): Boolean {
        var previous = GENESIS
        for ((entry, mac) in entries) {
            val expected = link(key, previous, entry)
            if (!MessageDigest.isEqual(expected.toByteArray(), mac.toByteArray())) return false
            previous = mac
        }
        return true
    }
}

/** A random spoken code, so a recording of an earlier approval can't be replayed. */
object SpokenChallenge {
    private val words = mapOf(
        "zero" to '0', "oh" to '0', "o" to '0', "one" to '1', "two" to '2', "to" to '2', "too" to '2',
        "three" to '3', "four" to '4', "for" to '4', "five" to '5', "six" to '6', "seven" to '7',
        "eight" to '8', "ate" to '8', "nine" to '9',
    )
    private val random = SecureRandom()

    fun create(): String = List(3) { random.nextInt(10) }.joinToString("")

    fun spaced(code: String): String = code.toList().joinToString(", ")

    fun matches(code: String, heard: String): Boolean {
        val digits = heard.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString("") { token ->
            if (token.all(Char::isDigit)) token else words[token]?.toString().orEmpty()
        }
        return digits == code
    }
}

object VoiceMatch {
    const val THRESHOLD = 0.6f

    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f
        var left = 0f
        var right = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            left += a[i] * a[i]
            right += b[i] * b[i]
        }
        if (left == 0f || right == 0f) return -1f
        return dot / (kotlin.math.sqrt(left) * kotlin.math.sqrt(right))
    }

    fun isMatch(enrolled: FloatArray, sample: FloatArray): Boolean = similarity(enrolled, sample) >= THRESHOLD
}

object ModelPath {
    /** The Windows version of the path check. */
    fun windowsProblem(path: String): String? {
        val p = path.trim()
        return when {
            p.isEmpty() -> "Enter the model path"
            !Regex("^[A-Za-z]:\\\\.+").containsMatchIn(p) -> "Enter the full path, starting with a drive like C:\\"
            p.split('\\').any { it == ".." } -> "The path can't contain .."
            !p.lowercase().endsWith(".litertlm") -> "The model must be a .litertlm file"
            else -> null
        }
    }

    /** Returns why a typed path can't be used, or null if its format is acceptable. */
    fun problem(path: String): String? {
        val p = path.trim()
        return when {
            p.isEmpty() -> "Enter the model path"
            !p.startsWith("/") -> "Enter the full path, starting with /"
            p.split('/').any { it == ".." } -> "The path can't contain .."
            !p.lowercase().endsWith(".litertlm") -> "The model must be a .litertlm file"
            p.contains("/Android/data/") || p.contains("/Android/obb/") ->
                "Android blocks reading another app's private folder. Move the file to Download first"
            else -> null
        }
    }
}

/**
 * Turns a name you type into wake-word tokens, matching the model's own tokenizer.
 * It chooses the highest-scoring way to split the text into known pieces.
 */
class BpeTokenizer(private val pieces: Map<String, Float>) {
    private val longest = pieces.keys.maxOfOrNull { it.length } ?: 0

    fun encode(phrase: String): List<String>? {
        val text = "\u2581" + phrase.trim().uppercase().split(Regex("\\s+")).joinToString("\u2581")
        val best = FloatArray(text.length + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(text.length + 1) { -1 }
        best[0] = 0f
        for (end in 1..text.length) {
            for (start in maxOf(0, end - longest) until end) {
                if (best[start] == Float.NEGATIVE_INFINITY) continue
                val score = pieces[text.substring(start, end)] ?: continue
                if (best[start] + score > best[end]) {
                    best[end] = best[start] + score
                    from[end] = start
                }
            }
        }
        if (from[text.length] < 0) return null
        val out = ArrayList<String>()
        var end = text.length
        while (end > 0) {
            out += text.substring(from[end], end)
            end = from[end]
        }
        return out.reversed()
    }

    companion object {
        fun parse(lines: Sequence<String>): BpeTokenizer {
            val map = HashMap<String, Float>()
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size == 2) parts[1].toFloatOrNull()?.let { map[parts[0]] = it }
            }
            return BpeTokenizer(map)
        }
    }
}

object WakeName {
    const val DEFAULT = "Assistant"
    private val allowed = Regex("^[A-Za-z]+( [A-Za-z]+)?$")

    /** Returns why a name can't be used, or null if it's fine. */
    fun problem(name: String): String? {
        val n = name.trim()
        return when {
            !allowed.matches(n) -> "Use English letters only, in one or two words"
            n.replace(" ", "").length < 3 -> "Use at least 3 letters"
            n.length > 24 -> "Use at most 24 letters"
            else -> null
        }
    }

    fun phrase(name: String): String = "HEY ${name.trim().uppercase()}"

    /** Builds the keyword line the engine expects, or null if the name can't be tokenized. */
    fun keywordLine(tokenizer: BpeTokenizer, name: String): String? {
        val tokens = tokenizer.encode(phrase(name)) ?: return null
        return "${tokens.joinToString(" ")} :2.0 #0.3 @${phrase(name).replace(' ', '_')}"
    }
}

enum class Script { LATIN, DEVANAGARI, TELUGU }

object ScriptDetect {
    /** Picks the reply voice from the letters used in the text. */
    fun of(text: String): Script {
        var hindi = 0
        var telugu = 0
        var latin = 0
        for (c in text) {
            when (c) {
                in '\u0900'..'\u097F' -> hindi++
                in '\u0C00'..'\u0C7F' -> telugu++
                in 'A'..'Z', in 'a'..'z' -> latin++
            }
        }
        return when {
            telugu > 0 && telugu >= hindi && telugu >= latin -> Script.TELUGU
            hindi > 0 && hindi >= latin -> Script.DEVANAGARI
            else -> Script.LATIN
        }
    }
}
