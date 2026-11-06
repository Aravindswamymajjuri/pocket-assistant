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
    )
    private val labelWords = Regex(
        "(?i)\\b(bank|banking|upi|wallet|pay|paytm|phonepe|gpay|password|passwords|authenticator|crypto|trading|broker)\\b",
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
