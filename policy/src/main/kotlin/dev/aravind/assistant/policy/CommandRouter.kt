package dev.aravind.assistant.policy

/** Common commands run without Gemma, so they stay fast and predictable. */
object CommandRouter {
    /**
     * Fixes steps that depend on the step before: "open YouTube and search for Java tutorials"
     * searches inside YouTube, not on Google.
     */
    fun inContext(steps: List<String>, calls: List<ToolCall?>): List<ToolCall?> = calls.mapIndexed { index, call ->
        val previous = calls.getOrNull(index - 1)
        val saidGoogle = steps.getOrNull(index).orEmpty().contains("google", ignoreCase = true)
        if (call?.tool == Tool.WEB_SEARCH && call.args["site"] != "youtube" && !saidGoogle && previous?.tool == Tool.OPEN_APP &&
            previous.text("name").contains("youtube", ignoreCase = true)
        ) {
            call.copy(args = call.args + ("site" to "youtube"))
        } else {
            call
        }
    }

    private val stop = Regex("(?i)^(?:please\\s+)?(?:stop|cancel|turn off|shut down|be quiet|quiet)(?:\\s+(?:now|listening|assistant))?[.!]*$")
    private const val VERBS =
        "(?:open|launch|start|close|send|message|msg|text|whats ?app|sms|tell|call|dial|phone|ring|set|turn|switch|enable|" +
            "disable|activate|deactivate|search|google|look up|scroll|go|press|read|show|tap|click|select|lock|put|mute|" +
            "unmute|wake|make|play|find)"
    private val boundary = Regex(
        "(?i)\\s*,\\s*(?:and\\s+)?(?:then\\s+)?(?=$VERBS\\b)|\\s+(?:and then|and|then|after that)\\s+(?=$VERBS\\b)",
    )
    private val callOnSpeaker = Regex("(?i)^((?:make a call to|call|phone|dial|ring) .+?) (?:on|with|in) (?:the )?speaker(?: ?phone)?(?: mode)?$")
    private val messageStart = Regex("(?i)^(?:please )?(?:send|message|msg|text|whats ?app|sms|tell|write)\\b")

    fun isStop(text: String): Boolean {
        return stop.matches(text.trim().trimEnd('.', '!'))
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
        // "Call Daddy on speaker" is a call, then the speakerphone.
        val split = out.flatMap { part ->
            val onSpeaker = callOnSpeaker.matchEntire(part)
            if (onSpeaker != null) listOf(onSpeaker.groupValues[1], "turn on speaker") else listOf(part)
        }
        return split.take(5)
    }

    private val misheardTurn = Regex("(?i)^(?:please\\s+)?(?:turrent|turret|turned|turnt|torn|tern|term|tune|trun|ton)\\b")
    private val joinedTurn = Regex("(?i)^turn(on|off)\\b")

    /** Repairs common speech mistakes at the start of a command. Message text is left alone. */
    fun normalize(text: String): String {
        val cleaned = text.trim().replace('\u2019', '\'').trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)^(?:can you |could you |please |will you )+"), "")
        return cleaned.replace(misheardTurn, "turn").replace(joinedTurn) { "turn ${it.groupValues[1]}" }
            .replace(Regex("(?i)\\b(turn|switch) of\\b")) { "${it.groupValues[1]} off" }
    }

    /** contacts helps split "send a message to Daddy hi" into a name and a message. */
    fun route(text: String, contacts: Collection<String> = emptyList()): ToolCall? {
        var t = normalize(text)
        // Speech recognition often mishears the first word, such as "and" for "set an".
        // Only harmless filler words are dropped, never words like "don't" that change the meaning.
        repeat(3) {
            val raw = message(t, contacts) ?: match(t)
            if (raw != null) return (ToolValidator.validate(raw.first, raw.second) as? Validated.Ok)?.call
            val next = t.replaceFirst(misheardStart, "")
            if (next == t || next.isBlank()) return null
            t = next
        }
        return null
    }

    private val misheardStart = Regex("(?i)^(?:and|an|in|on|so|um|uh|the|a|okay|ok|now|then|sat|said|said an|set in|just|hey|hi|yeah|yes)\\s+")

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
        webQuestion(t)?.let { return "web_answer" to mapOf("query" to it) }
        find("(?:search|find|play)(?: for)? (.+?) (?:on|in) you ?tube")?.let { return "web_search" to mapOf("query" to it[1], "site" to "youtube") }
        // Speakerphone and music run straight away, without the AI thinking.
        find("(?:turn|switch|put) (?:the )?(?:call )?(?:on )?(?:to )?(?:the )?speaker(?: ?phone)?(?: mode)?(?: on)?|speaker(?: ?phone)? on")?.let {
            return "speakerphone" to mapOf("on" to true)
        }
        find("(?:turn|switch) off (?:the )?speaker(?: ?phone)?|(?:turn |switch )?(?:the )?speaker(?: ?phone)? off")?.let {
            return "speakerphone" to mapOf("on" to false)
        }
        music(t)?.let { return it }
        find("(?:search|google|look up|find)(?: on google| in google)?(?: for| about)? (.+?)(?: on google| in google| on the internet| on internet| online| on the web)?")?.let {
            return "web_search" to mapOf("query" to it[1], "site" to "google")
        }
        screen(t)?.let { return it }
        chat(t)?.let { return it }
        section(t)?.let { return it }
        find("(?:open|launch|start) (.+)")?.let { return "open_app" to mapOf("name" to it[1].removePrefix("the ")) }
        find("(?:tap|click|press|select|touch) (?:on )?(?:the )?(.+?)(?: button| option)?")?.let { return "tap_text" to mapOf("text" to it[1]) }
        find("(?:make a call to|give a call to|call to|call|phone|dial|ring)(?: to)? (?:my )?(.+)")?.let { return "place_call" to mapOf("to" to it[1]) }
        return null
    }

    /** Music commands that need no thinking: play, pause, resume, skip, previous, stop. */
    private fun music(t: String): Pair<String, Map<String, Any?>>? {
        fun find(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(t)?.groupValues
        val control = when {
            find("pause(?: (?:the )?(?:music|song|songs|it))?") != null -> "pause"
            find("stop (?:the )?(?:music|song|songs|playing)") != null -> "stop"
            find("(?:resume|continue|unpause)(?: (?:the )?(?:music|song|playing|it))?|play (?:the )?music again") != null -> "resume"
            find("(?:next|skip)(?: (?:the|this))?(?: song| track)?|play (?:the )?next (?:song|track)") != null -> "next"
            find("(?:previous|last) (?:song|track)|play (?:the )?previous (?:song|track)") != null -> "previous"
            else -> null
        }
        if (control != null) return "media_control" to mapOf("action" to control)
        if (find("play (?:some |my |all )?(?:music|songs)(?: (?:on|from|in) my phone)?") != null) return "play_music" to emptyMap()
        find("play (?:all |some |my )?(?:the )?(.+?) (?:songs?|music|album)(?: (?:on|from|in) my phone)?")?.let {
            return "play_music" to mapOf("query" to it[1])
        }
        find("play (?:the )?song (.+)")?.let { return "play_music" to mapOf("query" to it[1]) }
        return null
    }

    private val numberWords = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50,
    )

    /** Turns the many ways speech recognition writes a time into one form, such as "7:15 pm". */
    fun normalizeTime(text: String): String {
        var t = " " + text.lowercase().replace(",", " ").replace(Regex("\\s+"), " ") + " "
        t = t.replace(Regex("\\bala+r[aeu]?m\\b"), "alarm")
        // "forty five" becomes 45, then each remaining number word becomes its digits.
        t = t.replace(Regex("\\b(twenty|thirty|forty|fifty)[ -](one|two|three|four|five|six|seven|eight|nine)\\b")) {
            (numberWords.getValue(it.groupValues[1]) + numberWords.getValue(it.groupValues[2])).toString()
        }
        t = t.replace(Regex("\\b(${numberWords.keys.filter { it != "oh" }.joinToString("|")})\\b")) { numberWords.getValue(it.value).toString() }
        t = t.replace(Regex("\\b(\\d{1,2}) oh (\\d)\\b")) { "${it.groupValues[1]}:0${it.groupValues[2]}" }
        t = t.replace(Regex("\\bquarter past (\\d{1,2})\\b")) { "${it.groupValues[1]}:15" }
        t = t.replace(Regex("\\bhalf past (\\d{1,2})\\b")) { "${it.groupValues[1]}:30" }
        t = t.replace(Regex("\\bquarter to (\\d{1,2})\\b")) { "${(it.groupValues[1].toInt() + 10) % 12 + 1}:45" }
        t = t.replace(Regex("\\b(a|p)\\.? ?m\\b\\.?")) { "${it.groupValues[1]}m" }
        t = t.replace(Regex("\\b(\\d{1,2})[.\\- ](\\d{2})\\b(?= ?(?:am|pm|o'?clock)?\\b)")) { "${it.groupValues[1]}:${it.groupValues[2]}" }
        t = t.replace(Regex("\\b(\\d{1,2})(\\d{2})\\b(?= ?(?:am|pm|o'?clock|hours?)?\\b)")) { m ->
            if (m.groupValues[2].toInt() < 60) "${m.groupValues[1]}:${m.groupValues[2]}" else m.value
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Questions whose answers change over time, so they're looked up instead of guessed. */
    private val current = Regex(
        "(?i)^(?:who (?:won|is winning|will win|lost)|which team (?:won|is winning)|what(?:'s| is| was) the (?:score|result|final score|latest)|" +
            "score of|result of|(?:the )?latest (?:news|score|scores|updates?)|news (?:about|on|today)|today'?s (?:news|weather|score|match|headlines)|" +
            "(?:what(?:'s| is) the )?(?:weather|temperature|forecast)\\b|(?:what(?:'s| is) the )?(?:price|rate|cost) of|" +
            "how much (?:is|does|did) .+ cost|when is the next|who is the (?:current )?(?:prime minister|president|chief minister|ceo|captain)|" +
            "(?:ask|check) google|find out|look up .+ and tell me|tell me the (?:latest|score|news)).*",
    )

    private fun webQuestion(t: String): String? {
        if (!current.matches(t)) return null
        return t.replace(Regex("(?i)^(?:ask google|check google|find out|look up)\\s+(?:about |for |whether |if )?"), "")
            .replace(Regex("(?i)\\s+and tell me$"), "").trim().takeIf { it.isNotEmpty() }
    }

    private fun screen(t: String): Pair<String, Map<String, Any?>>? {
        val l = t.lowercase()
        val screenWord = "(?:my |the |this )?(?:screen|page|display)"
        if (Regex("^(?:read|read out|read aloud|tell me)(?: what'?s| what is| everything)?(?: on| in)? $screenWord(?: to me| aloud| out)?$").matches(l)) {
            return "read_screen" to mapOf("explain" to false)
        }
        if (Regex("^(?:explain|describe|summari[sz]e|what(?:'s| is) (?:on|in)|tell me what(?:'s| is) (?:on|in)|what do you see on|look at)(?: the)? $screenWord.*$").matches(l) ||
            Regex("^what am i (?:looking at|seeing)$").matches(l) ||
            Regex("^read $screenWord and (?:explain|describe|summari[sz]e).*$").matches(l)
        ) {
            return "read_screen" to mapOf("explain" to true)
        }
        return null
    }

    /** "Open WhatsApp chat with Daddy", "go to the Daddy chat", "open Amma's messages". */
    private fun chat(t: String): Pair<String, Map<String, Any?>>? {
        val l = t.lowercase()
        val app = if (Regex("\\b(sms|text messages?|messages app)\\b").containsMatchIn(l)) "sms" else "whatsapp"
        val open = "(?:open|go to|show|show me|take me to|go into|enter)(?: the| my)?"
        val where = "(?: (?:on|in) (?:whats ?app|sms|messages))?"
        val patterns = listOf(
            Regex("(?i)^$open (?:whats ?app |sms )?(?:chat|conversation|messages?)(?: with| of| from) (.+?)$where$"),
            Regex("(?i)^$open (?:whats ?app |sms )?(.+?)(?:'s|s')? (?:whats ?app )?(?:chat|conversation)$where$"),
        )
        for (pattern in patterns) {
            val name = pattern.matchEntire(t)?.groupValues?.get(1)?.trim() ?: continue
            val clean = name.replace(Regex("(?i)^(?:whats ?app|sms)\\s+"), "").removePrefix("my ").trim()
            if (clean.isEmpty() || clean.lowercase() in setOf("whatsapp", "the", "my", "a")) continue
            return "open_chat" to mapOf("contact" to clean, "app" to app)
        }
        return null
    }

    private fun section(t: String): Pair<String, Map<String, Any?>>? {
        val l = t.lowercase()
        val open = "^(?:open|show|show me|go to|play|watch|take me to)(?: the| my| me)? "
        fun has(app: String) = Regex("\\b$app\\b").containsMatchIn(l)
        val key = when {
            Regex("${open}(?:instagram )?reels?(?: (?:on|in) instagram)?$").matches(l) -> "instagram_reels"
            has("instagram") && Regex("${open}(?:instagram )?(?:search|explore)(?: (?:on|in) instagram)?$").matches(l) -> "instagram_search"
            has("instagram") && Regex("${open}(?:instagram )?profile(?: (?:on|in) instagram)?$").matches(l) -> "instagram_profile"
            Regex("${open}(?:youtube |you tube )?shorts(?: (?:on|in) you ?tube)?$").matches(l) -> "youtube_shorts"
            Regex("${open}(?:youtube |you tube )?subscriptions(?: (?:on|in) you ?tube)?$").matches(l) -> "youtube_subscriptions"
            Regex("${open}(?:whats ?app )?(?:status|updates)(?: (?:on|in) whats ?app)?$").matches(l) && (has("whats ?app") || l.contains("status")) -> "whatsapp_status"
            has("whats ?app") && Regex("${open}(?:whats ?app )?calls(?: (?:on|in) whats ?app)?$").matches(l) -> "whatsapp_calls"
            else -> return null
        }
        return "app_section" to mapOf("section" to key)
    }

    private fun alarm(raw: String): Pair<String, Map<String, Any?>>? {
        if (!Regex("(?i)\\b(ala+r[aeu]?m|wake)").containsMatchIn(raw)) return null
        val t = normalizeTime(raw)
        val time = "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|o'?clock|hours)?"
        val part = "(?:\\s+(?:at |for )?(today|tonight|tomorrow|in the morning|in the evening|in the afternoon|at night|this morning|this evening|this afternoon|tomorrow morning|tomorrow evening|morning|evening|night))?"
        val lead = "^(?:please )?(?:(?:set|create|put|keep|add|make)(?: me)?(?: up)? )?(?:an |a |the |my |one )?"
        val m = Regex("$lead(?:alarm|wake ?up(?: call)?|wake me up|wake me)(?: for| at| by| on)? $time$part(?: please)?$").matchEntire(t)
            ?: Regex("^(?:set|keep|put|create) (?:an |a )?(?:alarm )?(?:for |at )$time$part$").matchEntire(t)
            ?: Regex("^(?:set|keep|put|create) (?:an |a )?$time(?: alarm)$part$").matchEntire(t)
            ?: return null
        val g = m.groupValues
        val hour = to24(g[1].toLong(), g[3], g[4]) ?: return null
        val minute = g[2].toLongOrNull() ?: 0L
        if (minute > 59) return null
        return "set_alarm" to mapOf("hour" to hour, "minute" to minute)
    }

    private fun to24(hour: Long, meridiem: String, dayPart: String = ""): Long? {
        val m = meridiem.lowercase().let { if (it.startsWith("o") || it.startsWith("h")) "" else it }
        if (m.isEmpty()) {
            val evening = Regex("evening|afternoon|night|tonight").containsMatchIn(dayPart.lowercase())
            if (evening && hour in 1..11) return hour + 12
            return hour.takeIf { it in 0..23 }
        }
        if (hour !in 1..12) return null
        return if (m.startsWith("a")) hour % 12 else hour % 12 + 12
    }
}
