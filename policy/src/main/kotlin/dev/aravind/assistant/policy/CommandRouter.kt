package dev.aravind.assistant.policy

/** Common commands run without Gemma, so they stay fast and predictable. */
object CommandRouter {
    private val stop = Regex("(?i)^(?:please\\s+)?(?:stop|cancel|turn off|shut down|be quiet|quiet)(?:\\s+(?:now|listening|assistant))?[.!]*$")
    private val splitter = Regex("(?i)\\s*(?:,\\s*)?\\b(?:and then|after that|then)\\b\\s*")

    private val localStops = setOf("रुको", "रुक जाओ", "बंद करो", "बंद", "ఆపు", "ఆగు", "ఆపండి", "ఆగండి")

    fun isStop(text: String): Boolean {
        val t = text.trim().trimEnd('.', '!', '।')
        return stop.matches(t) || t in localStops
    }

    /** Splits "open maps and then go home" into at most five steps. */
    fun steps(text: String): List<String> = text.split(splitter).map { it.trim() }.filter { it.isNotEmpty() }.take(5)

    fun route(text: String): ToolCall? {
        val t = text.trim().trimEnd('.', '!', '?')
        val raw = match(t) ?: return null
        return (ToolValidator.validate(raw.first, raw.second) as? Validated.Ok)?.call
    }

    private fun match(t: String): Pair<String, Map<String, Any?>>? {
        fun find(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(t)?.groupValues

        find("(?:please )?lock (?:my |the )?(?:phone|mobile|screen|device)")?.let { return "lock_phone" to emptyMap() }
        find("(?:go |press )?back")?.let { return "go_back" to emptyMap() }
        find("go (?:to )?(?:the )?home(?: screen)?")?.let { return "go_home" to emptyMap() }
        find("scroll (up|down)")?.let { return "scroll" to mapOf("direction" to it[1].lowercase()) }
        find("(?:turn )?(?:the )?(?:flash ?light|torch) (on|off)")?.let { return "flashlight" to mapOf("on" to (it[1].lowercase() == "on")) }
        find("turn (on|off) (?:the )?(?:flash ?light|torch)")?.let { return "flashlight" to mapOf("on" to (it[1].lowercase() == "on")) }
        find("(?:turn )?(?:the )?volume (up|down)")?.let { return "volume" to mapOf("direction" to it[1].lowercase()) }
        find("(?:turn |switch )?(?:the )?(wi-?fi|wi fi|bluetooth) (on|off)")?.let { return toggle(it[1], it[2]) }
        find("(?:turn|switch) (on|off) (?:the )?(wi-?fi|wi fi|bluetooth)")?.let { return toggle(it[2], it[1]) }
        find("(?:turn |switch )?(?:the )?do not disturb (on|off)")?.let { return "do_not_disturb" to mapOf("on" to (it[1].lowercase() == "on")) }
        find("(?:turn|switch) (on|off) (?:the )?do not disturb")?.let { return "do_not_disturb" to mapOf("on" to (it[1].lowercase() == "on")) }
        find("(?:set )?(?:the )?brightness (?:to |at )?(\\d{1,3})(?: ?%| percent)?")?.let { return "brightness" to mapOf("percent" to it[1].toLong()) }
        find("(?:read|show)(?: me)?(?: my| the)? notifications?")?.let { return "read_notifications" to emptyMap() }
        find("(?:set )?(?:a |an )?timer (?:for )?(\\d{1,4}) (second|seconds|minute|minutes|hour|hours)")?.let {
            val unit = when (it[2].lowercase().first()) { 's' -> 1L; 'm' -> 60L; else -> 3600L }
            return "set_timer" to mapOf("seconds" to it[1].toLong() * unit)
        }
        find("(?:set |wake me up )?(?:an )?(?:alarm )?(?:at |for )(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m|p\\.m)?")?.let {
            val hour = to24(it[1].toLong(), it[3]) ?: return null
            return "set_alarm" to mapOf("hour" to hour, "minute" to (it[2].toLongOrNull() ?: 0L))
        }
        find("(?:search|google|look up)(?: for)? (.+)")?.let { return "web_search" to mapOf("query" to it[1]) }
        find("(?:open|launch|start) (.+)")?.let { return "open_app" to mapOf("name" to it[1]) }
        find("(?:tap|click|press|select) (?:on )?(.+)")?.let { return "tap_text" to mapOf("text" to it[1]) }
        find("(?:call|phone|dial) (.+)")?.let { return "place_call" to mapOf("to" to it[1]) }
        find("(?:send )?(?:a )?(?:sms|text)(?: message)? to (.+?) (?:saying|that says|says) (.+)")?.let {
            return "send_sms" to mapOf("to" to it[1], "body" to it[2])
        }
        find("(?:send )?(?:a )?whats ?app(?: message)? to (.+?) (?:saying|that says|says) (.+)")?.let {
            return "send_whatsapp" to mapOf("to" to it[1], "body" to it[2])
        }
        return null
    }

    private fun toggle(device: String, state: String): Pair<String, Map<String, Any?>> {
        val tool = if (device.lowercase() == "bluetooth") "bluetooth" else "wifi"
        return tool to mapOf("on" to (state.lowercase() == "on"))
    }

    private fun to24(hour: Long, meridiem: String): Long? {
        val m = meridiem.lowercase()
        if (m.isEmpty()) return hour.takeIf { it in 0..23 }
        if (hour !in 1..12) return null
        return if (m.startsWith("a")) hour % 12 else hour % 12 + 12
    }
}
