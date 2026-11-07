package dev.aravind.assistant.policy

/** Common commands run without Gemma, so they stay fast and predictable. */
object CommandRouter {
    private val stop = Regex("(?i)^(?:please\\s+)?(?:stop|cancel|turn off|shut down|be quiet|quiet)(?:\\s+(?:now|listening|assistant))?[.!]*$")
    private const val VERBS =
        "(?:open|launch|start|close|send|message|msg|text|whats ?app|sms|tell|call|dial|phone|ring|set|turn|switch|enable|" +
            "disable|activate|deactivate|search|google|look up|scroll|go|press|read|show|tap|click|select|lock|put|mute|" +
            "unmute|wake|make|play|find)"
    private val boundary = Regex(
        "(?i)\\s*,\\s*(?:and\\s+)?(?:then\\s+)?(?=$VERBS\\b)|\\s+(?:and then|and|then|after that)\\s+(?=$VERBS\\b)",
    )
    private val messageStart = Regex("(?i)^(?:please )?(?:send|message|msg|text|whats ?app|sms|tell|write)\\b")

    private val localStops = setOf("रुको", "रुक जाओ", "बंद करो", "बंद", "ఆపు", "ఆగు", "ఆపండి", "ఆగండి")

    fun isStop(text: String): Boolean {
        val t = text.trim().trimEnd('.', '!', '।')
        return stop.matches(t) || t in localStops
    }

    /**
     * Splits "open WhatsApp, send a message to Daddy hi" into steps. Once a message starts,
     * everything after it stays in that message, so its text is never cut up.
     */
    fun steps(text: String): List<String> {
        val t = normalize(text)
        val out = ArrayList<String>()
        var start = 0
        for (match in boundary.findAll(t)) {
            val part = t.substring(start, match.range.first).trim()
            if (messageStart.containsMatchIn(part)) break
            if (part.isNotEmpty()) out += part
            start = match.range.last + 1
        }
        t.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out.take(5)
    }

    private val misheardTurn = Regex("(?i)^(?:please\\s+)?(?:turrent|turret|turned|turnt|torn|tern|term|tune|trun|ton)\\b")
    private val joinedTurn = Regex("(?i)^turn(on|off)\\b")

    /** Repairs common speech mistakes at the start of a command. Message text is left alone. */
    fun normalize(text: String): String {
        val cleaned = text.trim().trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)^(?:can you |could you |please |will you )+"), "")
        return cleaned.replace(misheardTurn, "turn").replace(joinedTurn) { "turn ${it.groupValues[1]}" }
            .replace(Regex("(?i)\\b(turn|switch) of\\b")) { "${it.groupValues[1]} off" }
    }

    /** contacts helps split "send a message to Daddy hi" into a name and a message. */
    fun route(text: String, contacts: Collection<String> = emptyList()): ToolCall? {
        val t = normalize(text)
        val raw = message(t, contacts) ?: match(t) ?: return null
        return (ToolValidator.validate(raw.first, raw.second) as? Validated.Ok)?.call
    }

    private val devices: List<Pair<Regex, Pair<String, String?>>> = listOf(
        "(?:super|ultra|extreme) (?:power|battery) sav(?:er|ing)(?: mode)?" to ("quick_toggle" to QuickSettings.SUPER_SAVER),
        "(?:battery|power) sav(?:er|ing)(?: mode)?|power save(?: mode)?|battery saver mode" to ("quick_toggle" to QuickSettings.BATTERY_SAVER),
        "(?:aero|air) ?plane(?: mode)?|flight mode" to ("quick_toggle" to QuickSettings.AIRPLANE),
        "(?:mobile |personal |portable |wi-?fi )?hot ?spot|tethering" to ("quick_toggle" to QuickSettings.HOTSPOT),
        "screen ?record(?:ing|er)?" to ("quick_toggle" to QuickSettings.SCREEN_RECORDING),
        "dark (?:mode|theme)|night mode" to ("quick_toggle" to QuickSettings.DARK_MODE),
        "location|gps" to ("quick_toggle" to QuickSettings.LOCATION),
        "auto ?-?rotat(?:e|ion)|screen rotation|rotation" to ("quick_toggle" to QuickSettings.AUTO_ROTATE),
        "flash ?light|flash|torch(?: ?light)?" to ("flashlight" to null),
        "wi-?fi|wi fi|why fi|wlan" to ("wifi" to null),
        "blue ?tooth" to ("bluetooth" to null),
        "(?:mobile |cellular )?(?:data|internet)(?: network| connection)?|mobile network" to ("mobile_data" to null),
        "do not disturb|dnd" to ("do_not_disturb" to null),
    ).map { Regex("^(?:${it.first})$", RegexOption.IGNORE_CASE) to it.second }

    private val onWords = Regex("(?i)^(?:turn on|switch on|enable|activate|start)$")
    private val toggleFirst = Regex("(?i)^(turn on|switch on|turn off|switch off|enable|disable|activate|deactivate|start|stop) (?:the |my )?(.+?)(?: mode)?$")
    private val toggleLast = Regex("(?i)^(?:turn |switch |put )?(?:the |my )?(.+?)(?: mode)? (on|off)$")

    private fun toggleCall(device: String, on: Boolean): Pair<String, Map<String, Any?>>? {
        val (tool, setting) = devices.firstOrNull { it.first.matches(device.trim()) }?.second ?: return null
        return if (setting == null) tool to mapOf("on" to on) else tool to mapOf("setting" to setting, "on" to on)
    }

    private fun toggle(t: String): Pair<String, Map<String, Any?>>? {
        toggleFirst.matchEntire(t)?.let { m ->
            val verb = m.groupValues[1].lowercase()
            val on = onWords.matches(verb)
            return toggleCall(m.groupValues[2], on) ?: toggleCall("${m.groupValues[2]} mode", on)
        }
        toggleLast.matchEntire(t)?.let { m ->
            val on = m.groupValues[2].lowercase() == "on"
            return toggleCall(m.groupValues[1], on) ?: toggleCall("${m.groupValues[1]} mode", on)
        }
        return null
    }

    private fun ringer(t: String): String? {
        val l = t.lowercase()
        if (Regex("^un ?mute(?: my| the)?(?: phone| mobile)?$").matches(l)) return "normal"
        if (Regex("^mute(?: my| the)? (?:phone|mobile|ringer)$|^silent(?: my| the)? (?:phone|mobile)$").matches(l)) return "silent"
        val mode = Regex(
            "^(?:(turn on|switch on|enable|activate|turn off|switch off|disable|deactivate|put|set|switch|change|go|keep|make)" +
                "(?: my| the)?(?: phone| mobile)?(?: on| in| to| into)? )?(?:the )?(silent|vibrat\\w*|ring\\w*|normal|sound|general|loud)(?: mode| profile)?(?: on| off)?$",
        ).matchEntire(l) ?: return null
        val verb = mode.groupValues[1]
        val turningOff = verb.contains("off") || verb.startsWith("disable") || verb.startsWith("deactivate") || l.endsWith(" off")
        val kind = mode.groupValues[2]
        return when {
            kind == "silent" || kind.startsWith("vibrat") -> if (turningOff) "normal" else if (kind == "silent") "silent" else "vibrate"
            turningOff -> "vibrate"
            else -> "normal"
        }
    }

    /** Message commands, in many ways of saying them. WhatsApp is used unless you say SMS or text. */
    private fun message(t: String, contacts: Collection<String>): Pair<String, Map<String, Any?>>? {
        val l = t.lowercase()
        if (!Regex("^(?:please )?(?:send|message|msg|text|whats ?app|sms|tell|ping)\\b").containsMatchIn(l)) return null
        if (Regex("^(?:send|message|msg|text) (?:a |an )?(?:location|photo|picture|file|voice)").containsMatchIn(l)) return null
        if (Regex("^tell (?:me|us)\\b").containsMatchIn(l)) return null
        val tool = when {
            Regex("\\bwhats ?app\\b").containsMatchIn(l) -> "send_whatsapp"
            Regex("\\b(?:sms|text|texts)\\b").containsMatchIn(l) -> "send_sms"
            else -> "send_whatsapp"
        }
        var s = t.replace(Regex("(?i)\\s*\\b(?:on|in|via|through|using|by|over) (?:whats ?app|sms|text message|text)\\b"), "")
        s = s.replace(Regex("(?i)^(?:please )?(?:whats ?app|sms)\\b"), "message")
            .replace(Regex("(?i)\\b(?:whats ?app|sms) (message|msg)\\b"), "\$1")
            .replace(Regex("(?i)\\b(?:a |an )?whats ?app\\b"), "a message")
            .trim()
        fun result(to: String, body: String): Pair<String, Map<String, Any?>>? {
            val name = to.trim().replace(Regex("(?i)^(?:to |my |the )+"), "")
            val text = body.trim().trimStart(',', ':').trim()
            if (name.isEmpty() || text.isEmpty()) return null
            return tool to mapOf("to" to name, "body" to text)
        }
        Regex("(?i)^(?:please )?send (?:a |an |the )?(?:message|msg|text|sms|note)?s? ?to (.+)$").matchEntire(s)?.let {
            return split(it.groupValues[1], contacts)?.let { (to, body) -> result(to, body) }
        }
        Regex("(?i)^(?:please )?send (.+?) to ([\\p{L}.]+(?: [\\p{L}.]+)?)$").matchEntire(s)?.let {
            return result(it.groupValues[2], it.groupValues[1])
        }
        Regex("(?i)^(?:please )?(?:message|msg|text|tell|ping) (.+)$").matchEntire(s)?.let {
            return split(it.groupValues[1], contacts)?.let { (to, body) -> result(to, body) }
        }
        return null
    }

    private val separator = Regex("(?i)^(.+?)(?::| saying| that says| says| to say| telling| that)\\s+(.+)$")
    private val comma = Regex("^([^,]+),\\s*(.+)$")

    /** Finds where the name ends and the message begins. */
    private fun split(rest: String, contacts: Collection<String>): Pair<String, String>? {
        separator.matchEntire(rest)?.let { m ->
            if (m.groupValues[1].trim().split(' ').size <= 4) return m.groupValues[1] to m.groupValues[2]
        }
        val words = rest.trim().replace(",", " ,").split(Regex("\\s+")).toMutableList()
        while (words.size > 1 && words.first().lowercase() in setOf("my", "the", "to")) words.removeAt(0)
        if (words.size < 2) return null
        fun rest(n: Int) = words.drop(n).joinToString(" ").replace(" ,", ",").trimStart(',', ' ')
        if (contacts.isNotEmpty()) {
            for (n in minOf(3, words.size - 1) downTo 1) {
                val name = words.take(n).joinToString(" ")
                if (!name.contains(',') && ContactMatcher.top(name, contacts).isNotEmpty()) return name to rest(n)
            }
        }
        comma.matchEntire(rest.trim())?.let { m ->
            if (m.groupValues[1].trim().split(' ').size <= 3) return m.groupValues[1] to m.groupValues[2]
        }
        return words.first() to rest(1)
    }

    private fun match(t: String): Pair<String, Map<String, Any?>>? {
        fun find(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(t)?.groupValues

        find("lock (?:my |the )?(?:phone|mobile|screen|device)")?.let { return "lock_phone" to emptyMap() }
        find("(?:go |press )?back")?.let { return "go_back" to emptyMap() }
        find("go (?:to )?(?:the )?home(?: screen)?")?.let { return "go_home" to emptyMap() }
        find("scroll (up|down)(?: a (?:bit|little))?")?.let { return "scroll" to mapOf("direction" to it[1].lowercase()) }
        find("(?:swipe|move) (up|down)")?.let { return "scroll" to mapOf("direction" to if (it[1].lowercase() == "up") "down" else "up") }
        ringer(t)?.let { return "ringer_mode" to mapOf("mode" to it) }
        toggle(t)?.let { return it }
        find("(?:turn )?(?:the )?volume (up|down)")?.let { return "volume" to mapOf("direction" to it[1].lowercase()) }
        find("(?:increase|raise) (?:the )?volume")?.let { return "volume" to mapOf("direction" to "up") }
        find("(?:decrease|lower|reduce) (?:the )?volume")?.let { return "volume" to mapOf("direction" to "down") }
        find("(?:set )?(?:the )?brightness (?:to |at )?(\\d{1,3})(?: ?%| percent)?")?.let { return "brightness" to mapOf("percent" to it[1].toLong()) }
        find("(?:read|show|check)(?: me)?(?: out)?(?: my| the| all)?(?: new| latest)? notifications?")?.let { return "read_notifications" to emptyMap() }
        find("(?:set |start )?(?:a |an |the )?timer (?:for )?(\\d{1,4}) (second|seconds|sec|secs|minute|minutes|min|mins|hour|hours)")?.let {
            val unit = when (it[2].lowercase().first()) { 's' -> 1L; 'm' -> 60L; else -> 3600L }
            return "set_timer" to mapOf("seconds" to it[1].toLong() * unit)
        }
        alarm(t)?.let { return it }
        find("(?:search|find|play)(?: for)? (.+?) (?:on|in) you ?tube")?.let { return "web_search" to mapOf("query" to it[1], "site" to "youtube") }
        find("(?:search|google|look up|find)(?: on google| in google)?(?: for| about)? (.+?)(?: on google| in google| on the internet| on internet| online| on the web)?")?.let {
            return "web_search" to mapOf("query" to it[1], "site" to "google")
        }
        find("(?:open|launch|start) (.+)")?.let { return "open_app" to mapOf("name" to it[1].removePrefix("the ")) }
        find("(?:tap|click|press|select|touch) (?:on )?(?:the )?(.+?)(?: button| option)?")?.let { return "tap_text" to mapOf("text" to it[1]) }
        find("(?:make a call to|give a call to|call to|call|phone|dial|ring)(?: to)? (?:my )?(.+)")?.let { return "place_call" to mapOf("to" to it[1]) }
        return null
    }

    private fun alarm(t: String): Pair<String, Map<String, Any?>>? {
        val time = "(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?|o'?clock)?"
        val part = "(?:\\s+(today|tonight|tomorrow|in the morning|in the evening|in the afternoon|at night|this morning|this evening|this afternoon|tomorrow morning|tomorrow evening))?"
        val m = Regex(
            "(?i)^(?:(?:set|create|put|keep|add)(?: me)? )?(?:an |a |the |my )?(?:alarm|wake ?up(?: call)?|wake me up|wake me)(?: for| at| by)? $time$part$",
        ).matchEntire(t) ?: Regex("(?i)^(?:set|keep|put) (?:an |a )?(?:alarm )?(?:for |at )$time$part$").matchEntire(t) ?: return null
        val g = m.groupValues
        val hour = to24(g[1].toLong(), g[3], g[4]) ?: return null
        return "set_alarm" to mapOf("hour" to hour, "minute" to (g[2].toLongOrNull() ?: 0L))
    }

    private fun to24(hour: Long, meridiem: String, dayPart: String = ""): Long? {
        val m = meridiem.lowercase().let { if (it.startsWith("o")) "" else it }
        if (m.isEmpty()) {
            val evening = Regex("evening|afternoon|night|tonight").containsMatchIn(dayPart.lowercase())
            if (evening && hour in 1..11) return hour + 12
            return hour.takeIf { it in 0..23 }
        }
        if (hour !in 1..12) return null
        return if (m.startsWith("a")) hour % 12 else hour % 12 + 12
    }
}
