package dev.aravind.assistant.policy

/** Risk levels are fixed in code. The model can never change them. */
enum class Risk { L0_SAFE, L1_PERSONAL, L2_OUTBOUND, L3_SECURITY, L4_BLOCKED }

/** Each category has its own switch in the Control Center. */
enum class Category { APPS, SYSTEM, SCREEN, NOTIFICATIONS, MESSAGES, CALLS, WEB }

sealed interface ParamSpec {
    val required: Boolean
}

data class TextParam(val maxLength: Int, override val required: Boolean = true) : ParamSpec

data class IntParam(val min: Long, val max: Long, override val required: Boolean = true) : ParamSpec

data class ChoiceParam(val allowed: Set<String>, override val required: Boolean = true) : ParamSpec

data class BoolParam(override val required: Boolean = true) : ParamSpec

enum class Tool(
    val id: String,
    val risk: Risk,
    val category: Category,
    val params: Map<String, ParamSpec> = emptyMap(),
    val description: String,
) {
    OPEN_APP("open_app", Risk.L0_SAFE, Category.APPS, mapOf("name" to TextParam(60)), "Open an app by name"),
    SET_ALARM(
        "set_alarm", Risk.L0_SAFE, Category.SYSTEM,
        mapOf("hour" to IntParam(0, 23), "minute" to IntParam(0, 59), "label" to TextParam(60, required = false)),
        "Set an alarm using a 24-hour time",
    ),
    SET_TIMER("set_timer", Risk.L0_SAFE, Category.SYSTEM, mapOf("seconds" to IntParam(1, 86_400)), "Start a timer"),
    FLASHLIGHT("flashlight", Risk.L0_SAFE, Category.SYSTEM, mapOf("on" to BoolParam()), "Turn the flashlight on or off"),
    VOLUME("volume", Risk.L0_SAFE, Category.SYSTEM, mapOf("direction" to ChoiceParam(setOf("up", "down"))), "Change media volume"),
    BRIGHTNESS("brightness", Risk.L0_SAFE, Category.SYSTEM, mapOf("percent" to IntParam(0, 100)), "Set screen brightness"),
    WIFI("wifi", Risk.L0_SAFE, Category.SYSTEM, mapOf("on" to BoolParam()), "Turn Wi-Fi on or off"),
    BLUETOOTH("bluetooth", Risk.L0_SAFE, Category.SYSTEM, mapOf("on" to BoolParam()), "Turn Bluetooth on or off"),
    MOBILE_DATA("mobile_data", Risk.L0_SAFE, Category.SYSTEM, mapOf("on" to BoolParam()), "Turn mobile data on or off"),
    QUICK_TOGGLE(
        "quick_toggle", Risk.L0_SAFE, Category.SYSTEM,
        mapOf("setting" to ChoiceParam(QuickSettings.ALL), "on" to BoolParam()),
        "Turn a phone mode on or off. setting is one of: ${QuickSettings.ALL.joinToString()}",
    ),
    RINGER_MODE(
        "ringer_mode", Risk.L0_SAFE, Category.SYSTEM,
        mapOf("mode" to ChoiceParam(setOf("normal", "vibrate", "silent"))), "Set the phone to ring, vibrate, or silent",
    ),
    DO_NOT_DISTURB("do_not_disturb", Risk.L0_SAFE, Category.SYSTEM, mapOf("on" to BoolParam()), "Turn Do Not Disturb on or off"),
    LOCK_PHONE("lock_phone", Risk.L0_SAFE, Category.SYSTEM, description = "Lock the phone"),
    WEB_SEARCH(
        "web_search", Risk.L0_SAFE, Category.WEB,
        mapOf("query" to TextParam(200), "site" to ChoiceParam(setOf("google", "youtube"), required = false)),
        "Search Google or YouTube",
    ),
    GO_BACK("go_back", Risk.L0_SAFE, Category.SCREEN, description = "Press back"),
    GO_HOME("go_home", Risk.L0_SAFE, Category.SCREEN, description = "Go to the home screen"),
    SCROLL("scroll", Risk.L0_SAFE, Category.SCREEN, mapOf("direction" to ChoiceParam(setOf("up", "down"))), "Scroll the screen"),
    TAP_TEXT("tap_text", Risk.L0_SAFE, Category.SCREEN, mapOf("text" to TextParam(80)), "Tap a visible button or label"),
    READ_NOTIFICATIONS("read_notifications", Risk.L0_SAFE, Category.NOTIFICATIONS, description = "Read my notifications aloud"),
    SEND_SMS(
        "send_sms", Risk.L2_OUTBOUND, Category.MESSAGES,
        mapOf("to" to TextParam(60), "body" to TextParam(480)), "Send an SMS",
    ),
    SEND_WHATSAPP(
        "send_whatsapp", Risk.L2_OUTBOUND, Category.MESSAGES,
        mapOf("to" to TextParam(60), "body" to TextParam(480)), "Send a WhatsApp message",
    ),
    PLACE_CALL("place_call", Risk.L0_SAFE, Category.CALLS, mapOf("to" to TextParam(60)), "Place a phone call"),
    WEB_ANSWER(
        "web_answer", Risk.L0_SAFE, Category.WEB, mapOf("query" to TextParam(200)),
        "Look up something current on Google, like match results, news, weather, or prices, and say the answer",
    ),
    OPEN_CHAT(
        "open_chat", Risk.L0_SAFE, Category.MESSAGES,
        mapOf("contact" to TextParam(60), "app" to ChoiceParam(setOf("whatsapp", "sms"), required = false)),
        "Open the chat with a contact, without sending anything",
    ),
    APP_SECTION(
        "app_section", Risk.L0_SAFE, Category.APPS,
        mapOf("section" to ChoiceParam(AppSections.ALL.keys)), "Open a part of an app: ${AppSections.ALL.keys.joinToString()}",
    ),
    READ_SCREEN(
        "read_screen", Risk.L0_SAFE, Category.SCREEN,
        mapOf("explain" to BoolParam(required = false)), "Read the screen aloud, or explain what is on it",
    ),
    SCREEN_TASK(
        "screen_task", Risk.L0_SAFE, Category.SCREEN,
        mapOf("goal" to TextParam(200)), "Do a task inside the app that is open, step by step, such as finding a setting",
    );

    val hasRecipient: Boolean get() = "to" in params

    companion object {
        private val byId = entries.associateBy { it.id }
        fun find(id: String): Tool? = byId[id]
    }
}

/** Phone modes with no public Android switch. The phone app flips their Quick Settings tiles. */
object QuickSettings {
    const val HOTSPOT = "hotspot"
    const val AIRPLANE = "airplane_mode"
    const val LOCATION = "location"
    const val DARK_MODE = "dark_mode"
    const val BATTERY_SAVER = "battery_saver"
    const val SUPER_SAVER = "super_power_saving"
    const val SCREEN_RECORDING = "screen_recording"
    const val AUTO_ROTATE = "auto_rotate"
    val ALL = setOf(HOTSPOT, AIRPLANE, LOCATION, DARK_MODE, BATTERY_SAVER, SUPER_SAVER, SCREEN_RECORDING, AUTO_ROTATE)
}

/** A part of an app, opened by starting the app and tapping its tab. */
data class AppSection(val app: String, val packageName: String, val tab: List<String>)

object AppSections {
    val ALL = mapOf(
        "instagram_reels" to AppSection("Instagram", "com.instagram.android", listOf("Reels")),
        "instagram_search" to AppSection("Instagram", "com.instagram.android", listOf("Search and explore", "Search", "Explore")),
        "instagram_profile" to AppSection("Instagram", "com.instagram.android", listOf("Profile")),
        "youtube_shorts" to AppSection("YouTube", "com.google.android.youtube", listOf("Shorts")),
        "youtube_subscriptions" to AppSection("YouTube", "com.google.android.youtube", listOf("Subscriptions")),
        "whatsapp_status" to AppSection("WhatsApp", "com.whatsapp", listOf("Updates", "Status")),
        "whatsapp_calls" to AppSection("WhatsApp", "com.whatsapp", listOf("Calls")),
    )
}

data class ToolCall(val tool: Tool, val args: Map<String, Any>) {
    fun text(name: String): String = args[name] as String
    fun long(name: String): Long = args[name] as Long
    fun bool(name: String): Boolean = args[name] as Boolean
}

sealed interface Validated {
    data class Ok(val call: ToolCall) : Validated
    data class Rejected(val reason: String) : Validated
}

/** Deny by default: unknown tools, unknown arguments, and wrong types are all rejected, never repaired. */
object ToolValidator {
    fun validate(name: String?, rawArgs: Map<String, Any?>): Validated {
        val tool = name?.let(Tool::find) ?: return Validated.Rejected("unknown tool")
        val unknown = rawArgs.keys - tool.params.keys
        if (unknown.isNotEmpty()) return Validated.Rejected("unknown argument: ${unknown.first()}")

        val clean = LinkedHashMap<String, Any>()
        for ((key, spec) in tool.params) {
            val value = rawArgs[key]
            if (value == null) {
                if (spec.required) return Validated.Rejected("missing argument: $key")
                continue
            }
            clean[key] = when (spec) {
                is TextParam -> {
                    val text = value as? String ?: return Validated.Rejected("$key must be text")
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) return Validated.Rejected("$key is empty")
                    if (trimmed.length > spec.maxLength) return Validated.Rejected("$key is too long")
                    if (trimmed.any { it.isISOControl() && it != '\n' }) return Validated.Rejected("$key has control characters")
                    trimmed
                }
                is IntParam -> {
                    val number = value as? Long ?: return Validated.Rejected("$key must be a whole number")
                    if (number !in spec.min..spec.max) return Validated.Rejected("$key is out of range")
                    number
                }
                is ChoiceParam -> {
                    val choice = value as? String ?: return Validated.Rejected("$key must be text")
                    if (choice !in spec.allowed) return Validated.Rejected("$key has an invalid value")
                    choice
                }
                is BoolParam -> value as? Boolean ?: return Validated.Rejected("$key must be true or false")
            }
        }
        return Validated.Ok(ToolCall(tool, clean))
    }
}
