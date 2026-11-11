package dev.aravind.assistant.policy

/** Risk levels are fixed in code. The model can never change them. */
enum class Risk { L0_SAFE, L1_PERSONAL, L2_OUTBOUND, L3_SECURITY, L4_BLOCKED }

/** Each category has its own switch in the Control Center. */
enum class Category { APPS, SYSTEM, SCREEN, NOTIFICATIONS, MESSAGES, CALLS, WEB, MEDIA, FILES, MEMORY }

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
    OPEN_NOTIFICATION(
        "open_notification", Risk.L0_SAFE, Category.NOTIFICATIONS,
        mapOf("number" to IntParam(1, 10, required = false), "from" to TextParam(60, required = false)),
        "Open a notification, like tapping it: by its number from read_notifications (1 is the newest), or by app or sender",
    ),
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
    PHONE_STATUS(
        "phone_status", Risk.L0_SAFE, Category.SYSTEM,
        description = "Check battery, storage, Wi-Fi, mobile data, Bluetooth, sound mode, date and time, and the next alarm",
    ),
    FIND_CONTACT("find_contact", Risk.L0_SAFE, Category.CALLS, mapOf("name" to TextParam(60)), "Look up a contact's phone number"),
    RECENT_CALLS(
        "recent_calls", Risk.L0_SAFE, Category.CALLS,
        mapOf("contact" to TextParam(60, required = false)), "List recent calls, optionally only with one contact",
    ),
    RECENT_MESSAGES(
        "recent_messages", Risk.L0_SAFE, Category.MESSAGES,
        mapOf("contact" to TextParam(60, required = false)), "Read recent SMS messages, optionally only from one contact. Codes are hidden",
    ),
    CALENDAR_EVENTS(
        "calendar_events", Risk.L0_SAFE, Category.SYSTEM,
        mapOf("days" to IntParam(1, 7, required = false)), "List calendar events for today and the next few days",
    ),
    LIST_APPS(
        "list_apps", Risk.L0_SAFE, Category.APPS,
        mapOf("query" to TextParam(60, required = false)), "List or search the apps installed on the phone",
    ),
    LONG_PRESS("long_press", Risk.L0_SAFE, Category.SCREEN, mapOf("text" to TextParam(80)), "Long-press a visible button or label"),
    ENTER_TEXT(
        "enter_text", Risk.L0_SAFE, Category.SCREEN,
        mapOf("text" to TextParam(1000), "field" to TextParam(80, required = false)),
        "Fill a text box in the open app, like To, Subject or the message in Gmail. field is the box's label",
    ),
    RECENT_APPS("recent_apps", Risk.L0_SAFE, Category.SCREEN, description = "Show recent apps, to switch between apps"),
    OPEN_WEBSITE("open_website", Risk.L0_SAFE, Category.WEB, mapOf("url" to TextParam(300)), "Open a website address in the browser"),
    SEARCH_MUSIC(
        "search_music", Risk.L0_SAFE, Category.MEDIA,
        mapOf("query" to TextParam(80)), "Find songs stored on the phone by song, movie, album or artist name",
    ),
    PLAY_MUSIC(
        "play_music", Risk.L0_SAFE, Category.MEDIA,
        mapOf("query" to TextParam(80, required = false)),
        "Play songs stored on the phone that match a song, movie, album or artist. Without a query, play all songs",
    ),
    MEDIA_CONTROL(
        "media_control", Risk.L0_SAFE, Category.MEDIA,
        mapOf("action" to ChoiceParam(setOf("pause", "resume", "next", "previous", "stop"))),
        "Pause, resume, skip or stop the music or video that is playing",
    ),
    SEARCH_FILES(
        "search_files", Risk.L0_SAFE, Category.FILES,
        mapOf("query" to TextParam(80), "kind" to ChoiceParam(setOf("any", "document", "pdf", "image", "video", "audio"), required = false)),
        "Search files on the phone by name. Results are numbered for the other file tools",
    ),
    OPEN_FILE("open_file", Risk.L0_SAFE, Category.FILES, mapOf("file" to TextParam(200)), "Open a file by its number from the last search, or its name"),
    READ_DOCUMENT(
        "read_document", Risk.L0_SAFE, Category.FILES,
        mapOf("file" to TextParam(200)), "Read the text of a PDF or text file by its number from the last search, to summarize it",
    ),
    SHARE_FILE(
        "share_file", Risk.L2_OUTBOUND, Category.FILES,
        mapOf("file" to TextParam(200), "app" to TextParam(40, required = false)),
        "Share a file by its number from the last search, optionally with an app like WhatsApp",
    ),
    RENAME_FILE(
        "rename_file", Risk.L2_OUTBOUND, Category.FILES,
        mapOf("file" to TextParam(200), "new_name" to TextParam(80)), "Rename a file by its number from the last search",
    ),
    MOVE_FILE(
        "move_file", Risk.L2_OUTBOUND, Category.FILES,
        mapOf("file" to TextParam(200), "folder" to ChoiceParam(FileFolders.ALL)),
        "Move a file by its number from the last search into a main folder: ${FileFolders.ALL.joinToString()}",
    ),
    FIND_MEDIA(
        "find_media", Risk.L0_SAFE, Category.FILES,
        mapOf("kind" to ChoiceParam(setOf("photo", "video")), "days_ago" to IntParam(0, 365, required = false)),
        "Find photos or videos taken on one day. days_ago 0 is today, 1 is yesterday",
    ),
    OPEN_CAMERA(
        "open_camera", Risk.L0_SAFE, Category.MEDIA,
        mapOf("mode" to ChoiceParam(setOf("photo", "video"), required = false)), "Open the camera app",
    ),
    TAKE_PHOTO("take_photo", Risk.L2_OUTBOUND, Category.MEDIA, description = "Take a photo with the camera app, after the user confirms"),
    RECORD_VIDEO(
        "record_video", Risk.L2_OUTBOUND, Category.MEDIA,
        mapOf("action" to ChoiceParam(setOf("start", "stop"))), "Start recording a video after the user confirms, or stop recording",
    ),
    CREATE_EVENT(
        "create_event", Risk.L0_SAFE, Category.SYSTEM,
        mapOf(
            "title" to TextParam(100), "days_from_today" to IntParam(0, 365), "hour" to IntParam(0, 23), "minute" to IntParam(0, 59),
            "duration_minutes" to IntParam(5, 1440, required = false),
        ),
        "Add an event to the calendar. Use a 24-hour time",
    ),
    CREATE_REMINDER(
        "create_reminder", Risk.L0_SAFE, Category.SYSTEM,
        mapOf("text" to TextParam(100), "days_from_today" to IntParam(0, 365), "hour" to IntParam(0, 23), "minute" to IntParam(0, 59)),
        "Remind the user about something at a time. Use a 24-hour time",
    ),
    DELETE_EVENT(
        "delete_event", Risk.L2_OUTBOUND, Category.SYSTEM,
        mapOf("id" to IntParam(1, Long.MAX_VALUE)), "Delete a calendar event by the id shown by calendar_events",
    ),
    SPEAKERPHONE(
        "speakerphone", Risk.L0_SAFE, Category.CALLS,
        mapOf("on" to BoolParam()), "Turn the speakerphone on or off for the current call",
    ),
    CALL_CONTROL(
        "call_control", Risk.L0_SAFE, Category.CALLS,
        mapOf("action" to ChoiceParam(setOf("answer", "reject"))), "Answer or reject the call that is ringing now",
    ),
    REMEMBER(
        "remember", Risk.L0_SAFE, Category.MEMORY,
        mapOf("fact" to TextParam(200)), "Save a preference or fact, only when the user asks you to remember it",
    ),
    FORGET("forget", Risk.L0_SAFE, Category.MEMORY, mapOf("fact" to TextParam(200)), "Forget something the user asked you to remember"),
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

/** Main storage folders a file can be moved into. */
object FileFolders {
    val ALL = setOf("Download", "Documents", "Pictures", "Music", "Movies", "DCIM")
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

/**
 * Picks the tools that matter for a request, so the AI reads a short list and answers faster.
 * If nothing matches, every tool is included.
 */
object ToolSelector {
    private val core = setOf(
        Tool.OPEN_APP, Tool.SCREEN_TASK, Tool.READ_SCREEN, Tool.TAP_TEXT, Tool.ENTER_TEXT, Tool.GO_BACK, Tool.GO_HOME,
        Tool.SCROLL, Tool.WEB_ANSWER, Tool.PHONE_STATUS,
    )

    private val groups: List<Pair<Regex, Set<Tool>>> = listOf(
        "play|song|songs|music|album|artist|pause|resume|skip|next|previous|track" to setOf(
            Tool.PLAY_MUSIC, Tool.SEARCH_MUSIC, Tool.MEDIA_CONTROL, Tool.VOLUME, Tool.WEB_SEARCH, Tool.APP_SECTION,
        ),
        "call|phone|ring|dial|speaker|answer|reject|missed|contact|number" to setOf(
            Tool.PLACE_CALL, Tool.SPEAKERPHONE, Tool.CALL_CONTROL, Tool.FIND_CONTACT, Tool.RECENT_CALLS,
        ),
        "message|messages|msg|text|sms|whatsapp|chat|reply|send|notification|notifications|unread" to setOf(
            Tool.SEND_SMS, Tool.SEND_WHATSAPP, Tool.OPEN_CHAT, Tool.RECENT_MESSAGES, Tool.READ_NOTIFICATIONS,
            Tool.OPEN_NOTIFICATION, Tool.FIND_CONTACT,
        ),
        "file|files|pdf|document|documents|notes|share|rename|move|folder|download|downloads|summari[sz]e|assignment" to setOf(
            Tool.SEARCH_FILES, Tool.OPEN_FILE, Tool.READ_DOCUMENT, Tool.SHARE_FILE, Tool.RENAME_FILE, Tool.MOVE_FILE,
        ),
        "photo|photos|picture|pictures|video|videos|camera|selfie|gallery|record" to setOf(
            Tool.FIND_MEDIA, Tool.OPEN_CAMERA, Tool.TAKE_PHOTO, Tool.RECORD_VIDEO, Tool.OPEN_FILE, Tool.SHARE_FILE,
        ),
        "calendar|event|events|meeting|remind|reminder|alarm|timer|schedule|tomorrow|today" to setOf(
            Tool.CALENDAR_EVENTS, Tool.CREATE_EVENT, Tool.CREATE_REMINDER, Tool.DELETE_EVENT, Tool.SET_ALARM, Tool.SET_TIMER,
        ),
        "wi-?fi|bluetooth|volume|brightness|data|hotspot|silent|vibrate|ring|disturb|dnd|battery|flash|torch|lock|" +
            "airplane|aeroplane|location|dark|rotate|storage|setting|settings" to setOf(
            Tool.WIFI, Tool.BLUETOOTH, Tool.MOBILE_DATA, Tool.VOLUME, Tool.BRIGHTNESS, Tool.QUICK_TOGGLE, Tool.RINGER_MODE,
            Tool.DO_NOT_DISTURB, Tool.LOCK_PHONE, Tool.FLASHLIGHT,
        ),
        "search|google|youtube|website|site|browser|news|weather|score|price|who|what|when|where|how" to setOf(
            Tool.WEB_SEARCH, Tool.WEB_ANSWER, Tool.OPEN_WEBSITE,
        ),
        "app|apps|installed|open|recent|switch" to setOf(Tool.LIST_APPS, Tool.RECENT_APPS, Tool.APP_SECTION, Tool.LONG_PRESS),
        "remember|forget|memory" to setOf(Tool.REMEMBER, Tool.FORGET),
    ).map { Regex("(?i)\\b(?:${it.first})\\b") to it.second }

    fun relevant(request: String): List<Tool> {
        val picked = groups.filter { it.first.containsMatchIn(request) }.flatMap { it.second }.toSet()
        if (picked.isEmpty()) return Tool.entries
        return Tool.entries.filter { it in core || it in picked }
    }
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
