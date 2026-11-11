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
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
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
        Tool.LONG_PRESS -> "Long-press \"${call.text("text")}\" on the current screen"
        Tool.SHARE_FILE -> "Share file ${call.text("file")}" + ((call.args["app"] as? String)?.let { " with $it" } ?: "")
        Tool.RENAME_FILE -> "Rename file ${call.text("file")} to \"${call.text("new_name")}\""
        Tool.MOVE_FILE -> "Move file ${call.text("file")} to ${call.text("folder")}"
        Tool.TAKE_PHOTO -> "Take a photo with the camera"
        Tool.RECORD_VIDEO -> "Start recording a video"
        Tool.DELETE_EVENT -> "Delete calendar event ${call.long("id")}"
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
            return Decision.Deny("I can't control this screen. Open the app you want first, then ask again")
        }

        if (call.tool == Tool.OPEN_WEBSITE && !WebAddress.isSafe(call.text("url"))) {
            return Decision.Deny("I only open normal web addresses")
        }
        // Stopping a recording is always fine. Starting one needs a yes.
        if (call.tool == Tool.RECORD_VIDEO && call.text("action") == "stop") return Decision.Allow
        // Taps on Send, Pay, Delete and similar buttons need your yes, like sending a message does.
        if ((call.tool == Tool.TAP_TEXT || call.tool == Tool.LONG_PRESS) && ScreenAgent.isRisky(call.text("text"))) {
            return if (ctx.tainted) Decision.Biometric(Readback.of(call)) else Decision.Confirm(Readback.of(call))
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
            // A quick yes or a tap is enough, unless untrusted content was read during this task.
            Risk.L2_OUTBOUND -> if (ctx.tainted) Decision.Biometric(Readback.of(call)) else Decision.Confirm(Readback.of(call))
            else -> Decision.Deny("Do this manually")
        }
    }
}

/** Only plain web addresses can be opened: no app links, files, scripts or phone codes. */
object WebAddress {
    private val allowed = Regex("^(https?://)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,}(:\\d{2,5})?(/[^\\s]*)?$", RegexOption.IGNORE_CASE)

    fun isSafe(url: String): Boolean = allowed.matches(url.trim())

    fun normalized(url: String): String = url.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" }
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

/** Finds the contact you meant, even if speech recognition got the spelling slightly wrong. */
object ContactMatcher {
    private val groups = listOf(
        setOf("daddy", "dad", "papa", "father", "nanna", "nannagaru", "appa", "dady", "daddi"),
        setOf("mummy", "mum", "mom", "mother", "amma", "ammagaru", "mummi"),
        setOf("brother", "bro", "anna", "annayya"),
        setOf("sister", "sis", "akka", "chelli"),
    )
    private val fillers = setOf("to", "my", "the", "please", "garu", "gaaru", "number", "mobile", "phone")

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in fillers }

    private fun aliases(word: String): Set<String> = groups.firstOrNull { word in it } ?: setOf(word)

    fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun score(query: List<String>, name: String): Int {
        val target = words(name)
        if (query.isEmpty() || target.isEmpty()) return 0
        if (query == target) return 100
        if (query.all { q -> target.any { it in aliases(q) } }) return 90
        if (query.all { q -> target.any { it.startsWith(q) || q.startsWith(it) } }) return 70
        val close = query.all { q ->
            val allowed = if (q.length <= 4) 1 else 2
            target.any { t -> aliases(q).any { distance(it, t) <= allowed } }
        }
        return if (close) 50 else 0
    }

    /** Returns every contact tied for the best match, so the caller can tell "none" from "too many". */
    fun top(spoken: String, names: Collection<String>): List<String> {
        val query = words(spoken)
        val scored = names.distinct().map { it to score(query, it) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }
    }

    /** Returns the single best contact, or null if nothing matches or two are equally likely. */
    fun best(spoken: String, names: Collection<String>): String? = top(spoken, names).singleOrNull()
}

/** Example commands, shown as hints. */
object SpeechPrompt {
    fun text(name: String): String =
        "Hey $name. Turn on Wi-Fi. Turn off mobile data. Turn on the flashlight. Turn on Bluetooth. " +
            "Call Daddy. Call Amma. Open WhatsApp. Set an alarm for 7 AM. Set brightness to 50 percent. Scroll down. Go back. " +
            "Read my screen. Explain my screen. Read my notifications. Lock my phone. Open reels."
}

object WakeName {
    const val DEFAULT = "Assistant"

    /**
     * Checks speech for "hey" plus your assistant's name, allowing small spelling mistakes.
     * Returns the words spoken after the name, or null if the name wasn't said.
     */
    fun heardIn(text: String, name: String): String? {
        val spoken = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val parts = name.lowercase().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty() || spoken.size < parts.size) return null
        val allowed = if (parts.joinToString("").length <= 5) 1 else 2
        val greetings = setOf("hey", "hi", "hello", "ok", "okay", "a", "he", "hay", "ay")
        for (start in 0..minOf(1, spoken.size - parts.size)) {
            if (start == 1 && spoken[0] !in greetings) continue
            val candidate = spoken.subList(start, start + parts.size).joinToString("")
            if (ContactMatcher.distance(candidate, parts.joinToString("")) <= allowed) {
                return spoken.drop(start + parts.size).joinToString(" ")
            }
        }
        return null
    }

    /**
     * The check used to wake the assistant from silence, on what Moonshine heard.
     * It accepts a short first word and then the name, like "hey nova" or "a nova",
     * or the name on its own spelled almost exactly. For longer names, a greeting plus a
     * name that sounds the same also counts, so "he arriving" wakes "Aravind".
     * Returns the words spoken after the name, or null.
     */
    fun heardInStrict(text: String, name: String): String? {
        val spoken = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val parts = name.lowercase().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty() || spoken.size < parts.size) return null
        val target = parts.joinToString("")
        val allowed = if (target.length <= 5) 1 else 2
        val targetKey = FuzzyCommand.key(target)
        for (start in 0..1) {
            if (spoken.size < start + parts.size) break
            if (start == 1 && spoken[0].length > 5) continue
            val limit = if (start == 1) allowed else allowed - 1
            // The recognizer sometimes splits a name in two, like "ara vind", so one extra word is tried too.
            for (extra in 0..1) {
                val end = start + parts.size + extra
                if (end > spoken.size) continue
                val candidate = spoken.subList(start, end).joinToString("")
                if (ContactMatcher.distance(candidate, target) <= limit) return spoken.drop(end).joinToString(" ")
                if (start == 1 && extra == 0 && spoken[0] in WAKE_GREETINGS && target.length >= 6 && targetKey.length >= 3 &&
                    ContactMatcher.distance(FuzzyCommand.key(candidate), targetKey) <= 1
                ) {
                    return spoken.drop(end).joinToString(" ")
                }
            }
        }
        return null
    }

    private val WAKE_GREETINGS = setOf("hey", "hi", "hello", "ok", "okay", "hay", "he", "a", "ay", "high", "hai", "hei")

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
    fun keywordLine(tokenizer: BpeTokenizer, name: String): String? = line(tokenizer, phrase(name), 3.0f, 0.15f)

    private fun line(tokenizer: BpeTokenizer, phrase: String, boost: Float, threshold: Float): String? {
        val tokens = tokenizer.encode(phrase) ?: return null
        return "${tokens.joinToString(" ")} :$boost #$threshold @${phrase.replace(' ', '_')}"
    }

    /**
     * Every way of calling the assistant that the fast detector should accept, joined with "/".
     * Higher sensitivity wakes more easily, but may also wake by mistake more often.
     */
    fun keywords(tokenizer: BpeTokenizer, name: String, sensitivity: WakeSensitivity, learned: List<String> = emptyList()): String? {
        val upper = name.trim().uppercase()
        val greetings = when (sensitivity) {
            WakeSensitivity.HIGH -> listOf("HEY", "HI", "HELLO", "OK", "OKAY")
            WakeSensitivity.NORMAL -> listOf("HEY", "HI", "HELLO", "OK")
            WakeSensitivity.LOW -> listOf("HEY")
        }
        // Words learned from your own voice ("Teach my wake word") are listened for too.
        val extra = learned.map { it.uppercase().filter { c -> c in 'A'..'Z' } }.filter { it.length >= 2 }
        val names = (spellings(upper) + extra).distinct().take(MAX_SPELLINGS + MAX_LEARNED)
        val phrases = greetings.flatMap { greeting -> names.map { "$greeting $it" } }
        // The name on its own isn't added. In testing it competed with "hey" plus the name.
        val lines = phrases.mapNotNull { line(tokenizer, it, sensitivity.boost, sensitivity.threshold) }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    /**
     * Other spellings of the name that sound the same. The wake detector matches spelling pieces, so "JUNNU" expects
     * two N sounds, but people say "ju-nu". Names ending in "u", like Junnu, Chintu or Raju, also get "oo" spellings.
     * In testing, "hey Junnu" woke 0 of 40 times with only its own spelling and 22 of 40 with these, with no extra false wakes.
     */
    fun spellings(upperName: String): List<String> {
        val words = upperName.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val last = words.last()
        val single = last.replace(Regex("([A-Z])\\1+"), "$1")
        val forms = linkedSetOf(last, single)
        if (single.endsWith("U") && single.length >= 3) {
            val endOo = single.dropLast(1) + "OO"
            val firstU = single.indexOf('U')
            forms += endOo
            if (firstU in 1 until single.length - 1) {
                val firstOo = single.substring(0, firstU) + "OO" + single.substring(firstU + 1)
                forms += firstOo
                forms += firstOo.dropLast(1) + "OO"
            }
        }
        val prefix = words.dropLast(1).joinToString(" ")
        return forms.take(MAX_SPELLINGS).map { if (prefix.isEmpty()) it else "$prefix $it" }
    }

    private const val MAX_SPELLINGS = 5
    const val MAX_LEARNED = 4

    /** Spells a word the way it sounds, so "Junnu", "Juno" and "Junoo" all become "junu". */
    fun soundForm(word: String): String {
        var w = word.lowercase().filter { it in 'a'..'z' }
        w = w.replace(Regex("(.)\\1+"), "$1")
        for ((from, to) in listOf("oo" to "u", "ew" to "u", "ow" to "u", "ou" to "u")) w = w.replace(from, to)
        if (w.endsWith("o")) w = w.dropLast(1) + "u"
        return w
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    /**
     * The second wake check, on what a small speech model heard: a greeting and then the name, or the name on its own
     * in a short phrase. Words learned from your voice count as the name too. "Hey Juno" wakes "Junnu";
     * "hey June", "you know" and "Jenny" don't.
     */
    fun nameHeard(text: String, name: String, learned: Collection<String> = emptyList()): Boolean {
        val spoken = words(text)
        val targets = (listOf(name.replace(" ", "")) + learned).map(::soundForm).filter { it.length >= 2 }.toSet()
        for (start in 0..1) {
            if (start == 1 && (spoken.isEmpty() || spoken[0] !in WAKE_GREETINGS)) continue
            if (start == 0 && spoken.size > 3) continue
            for (count in 1..2) {
                if (spoken.size < start + count) continue
                val heard = soundForm(spoken.subList(start, start + count).joinToString(""))
                if (heard.isEmpty()) continue
                val close = targets.any { t -> heard.first() == t.first() && heard.last() == t.last() && ContactMatcher.distance(heard, t) <= 1 }
                if (close) return true
            }
        }
        return false
    }

    /**
     * While teaching the wake word, the word the speech model heard in place of the name, if it's close enough to be
     * safe to learn. "Hey Juno" gives "juno" for Junnu. Very different words, like "general", are never learned.
     */
    fun learnable(text: String, name: String): String? {
        val spoken = words(text)
        val start = if (spoken.isNotEmpty() && spoken[0] in WAKE_GREETINGS) 1 else 0
        val target = soundForm(name.replace(" ", ""))
        val nameWords = name.trim().split(' ').count { it.isNotEmpty() }
        return (nameWords..nameWords + 1).mapNotNull { count ->
            if (spoken.size < start + count) return@mapNotNull null
            val heard = spoken.subList(start, start + count).joinToString("")
            val form = soundForm(heard)
            if (form.length < 2 || form.first() != target.firstOrNull()) return@mapNotNull null
            val distance = ContactMatcher.distance(form, target)
            if (distance <= 2) heard to distance else null
        }.minByOrNull { it.second }?.first
    }
}

enum class WakeSensitivity(val boost: Float, val threshold: Float) {
    // Tuned on quiet, noisy test speech with Indian English voices. The speech and noise checks in the
    // phone app stop false wakes, so the detector itself can be more sensitive than before.
    LOW(2.5f, 0.2f),
    NORMAL(3.5f, 0.1f),
    HIGH(4.0f, 0.06f),
}


/**
 * The AI model can only act through tool calls. If it answers a command in words as if
 * it had done it, that answer is false and must not be spoken.
 */
object ReplyGuard {
    private val command = Regex(
        "(?i)\\b(set|turn|switch|send|call|open|alarm|timer|remind|message|text|play|enable|disable|start|stop|lock|search|whatsapp|sms|dial)\\b",
    )
    private val claim = Regex(
        "(?i)\\b(i've|i have|i'll|i will|i set|i've set|is set|has been|have been|done|sent|calling|turned|switched|opened|opening|" +
            "scheduled|created|enabled|disabled|alarm set|set for|set an alarm|setting)\\b",
    )

    fun isFalseActionClaim(userText: String, reply: String): Boolean = command.containsMatchIn(userText) && claim.containsMatchIn(reply)
}
