package dev.aravind.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.Telephony
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import dev.aravind.assistant.policy.AppSection
import dev.aravind.assistant.policy.AppSections
import dev.aravind.assistant.policy.BlockedApps
import dev.aravind.assistant.policy.ScreenAction
import dev.aravind.assistant.policy.ScreenAgent
import dev.aravind.assistant.policy.SecretRedactor
import dev.aravind.assistant.policy.ContactMatcher
import dev.aravind.assistant.policy.PhoneTarget
import dev.aravind.assistant.policy.QuickSettings
import dev.aravind.assistant.policy.Tool
import dev.aravind.assistant.policy.ToolCall
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Runs only calls the policy engine has already approved. It rechecks app and permission limits. */
class ToolExecutor(private val context: Context) {

    private val phone = PhoneTools(context)

    /** The words you said, used when explaining the screen. */
    private var utterance = ""

    suspend fun execute(call: ToolCall, said: String = ""): String = run { utterance = said; dispatch(call) }

    private suspend fun dispatch(call: ToolCall): String = when (call.tool) {
        Tool.OPEN_APP -> openApp(call.text("name"))
        Tool.SET_ALARM -> setAlarm(call)
        Tool.SET_TIMER -> setTimer(call.long("seconds").toInt())
        Tool.FLASHLIGHT -> flashlight(call.bool("on"))
        Tool.VOLUME -> volume(call.text("direction") == "up")
        Tool.BRIGHTNESS -> brightness(call.long("percent").toInt())
        Tool.WIFI -> wifi(call.bool("on"))
        Tool.BLUETOOTH -> bluetooth(call.bool("on"))
        Tool.MOBILE_DATA -> mobileData(call.bool("on"))
        Tool.QUICK_TOGGLE -> quickToggle(call.text("setting"), call.bool("on"))
        Tool.RINGER_MODE -> ringer(call.text("mode"))
        Tool.DO_NOT_DISTURB -> doNotDisturb(call.bool("on"))
        Tool.LOCK_PHONE -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locked")
        Tool.WEB_SEARCH -> webSearch(call.text("query"), call.args["site"] as? String)
        Tool.GO_BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Done")
        Tool.GO_HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Done")
        Tool.SCROLL -> automation { if (it.scroll(call.text("direction") == "down")) "Done" else "I can't scroll this screen" }
        Tool.TAP_TEXT -> automation { if (it.tap(call.text("text"))) "Done" else "I couldn't find ${call.text("text")} on the screen" }
        Tool.READ_NOTIFICATIONS ->
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) "Unlock your phone first, then ask again"
            else NotificationsService.spokenSummary(context)
        Tool.SEND_SMS -> sendSms(call.text("to"), call.text("body"))
        Tool.SEND_WHATSAPP -> sendWhatsApp(call.text("to"), call.text("body"))
        Tool.PLACE_CALL -> placeCall(call.text("to"))
        Tool.WEB_ANSWER -> webAnswer(call.text("query"))
        Tool.PHONE_STATUS -> phoneStatus()
        Tool.FIND_CONTACT -> findContact(call.text("name"))
        Tool.RECENT_CALLS -> recentCalls(call.args["contact"] as? String)
        Tool.RECENT_MESSAGES -> recentMessages(call.args["contact"] as? String)
        Tool.CALENDAR_EVENTS -> calendarEvents((call.args["days"] as? Long)?.toInt() ?: 1)
        Tool.LIST_APPS -> phone.listApps(call.args["query"] as? String)
        Tool.LONG_PRESS -> automation { if (it.longPress(call.text("text"))) "Done" else "I couldn't find ${call.text("text")} on the screen" }
        Tool.ENTER_TEXT -> automation {
            if (it.enterText(call.args["field"] as? String, call.text("text"))) "Typed it" else "I couldn't find a text box to type in"
        }
        Tool.RECENT_APPS -> automation { if (it.showRecents()) "Showing recent apps" else "Recent apps couldn't be opened" }
        Tool.OPEN_WEBSITE -> phone.openWebsite(call.text("url"))
        Tool.SEARCH_MUSIC -> phone.searchMusic(call.text("query"))
        Tool.PLAY_MUSIC -> phone.playMusic(call.args["query"] as? String)
        Tool.MEDIA_CONTROL -> phone.mediaControl(call.text("action"))
        Tool.SEARCH_FILES -> phone.searchFiles(call.text("query"), call.args["kind"] as? String)
        Tool.OPEN_FILE -> phone.openFile(call.text("file"))
        Tool.READ_DOCUMENT -> phone.readDocument(call.text("file"))
        Tool.SHARE_FILE -> phone.shareFile(call.text("file"), call.args["app"] as? String)
        Tool.RENAME_FILE -> phone.renameFile(call.text("file"), call.text("new_name"))
        Tool.MOVE_FILE -> phone.moveFile(call.text("file"), call.text("folder"))
        Tool.FIND_MEDIA -> phone.findMedia(call.text("kind"), (call.args["days_ago"] as? Long)?.toInt() ?: 0)
        Tool.OPEN_CAMERA -> phone.openCamera(call.args["mode"] as? String)
        Tool.TAKE_PHOTO -> phone.takePhoto()
        Tool.RECORD_VIDEO -> phone.recordVideo(call.text("action"))
        Tool.CREATE_EVENT -> phone.createEvent(
            call.text("title"), call.long("days_from_today").toInt(), call.long("hour").toInt(), call.long("minute").toInt(),
            (call.args["duration_minutes"] as? Long)?.toInt() ?: 60,
        )
        Tool.CREATE_REMINDER -> phone.createReminder(
            call.text("text"), call.long("days_from_today").toInt(), call.long("hour").toInt(), call.long("minute").toInt(),
        )
        Tool.DELETE_EVENT -> phone.deleteEvent(call.long("id"))
        Tool.CALL_CONTROL -> phone.callControl(call.text("action"))
        Tool.SPEAKERPHONE -> phone.speakerphone(call.bool("on"))
        Tool.REMEMBER -> phone.remember(call.text("fact"), utterance)
        Tool.FORGET -> phone.forget(call.text("fact"))
        Tool.OPEN_CHAT -> openChat(call.text("contact"), call.args["app"] as? String ?: "whatsapp")
        Tool.APP_SECTION -> appSection(AppSections.ALL.getValue(call.text("section")))
        Tool.READ_SCREEN -> readScreen(call.args["explain"] as? Boolean ?: false, utterance)
        Tool.SCREEN_TASK -> screenTask(call.text("goal"))
    }

    private fun launch(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openApp(name: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
        val wanted = name.lowercase().removeSuffix(" app").trim()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().startsWith(wanted) }
            ?: return "I couldn't find an app called $name"
        val packageName = match.activityInfo.packageName
        if (BlockedApps.blocksOpening(packageName, match.loadLabel(pm).toString())) {
            return "Payment, banking, and password apps must be used manually"
        }
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return "That app can't be opened"
        launch(intent)
        return "Opening ${match.loadLabel(pm)}"
    }

    private suspend fun setAlarm(call: ToolCall): String {
        val hour = call.long("hour").toInt()
        val minute = call.long("minute").toInt()
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        (call.args["label"] as? String)?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        try {
            launch(intent)
        } catch (e: ActivityNotFoundException) {
            return "Your clock app didn't accept the alarm"
        }
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val spoken = "%d:%02d %s".format(hour12, minute, if (hour < 12) "AM" else "PM")
        // Checks that the clock app really saved it, instead of trusting that it did.
        repeat(8) {
            delay(400)
            val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return@repeat
            val time = java.util.Calendar.getInstance().apply { timeInMillis = next.triggerTime }
            if (time.get(java.util.Calendar.HOUR_OF_DAY) == hour && time.get(java.util.Calendar.MINUTE) == minute) return "Alarm set for $spoken"
        }
        return "I asked your clock app to set an alarm for $spoken. Please check that it appears"
    }

    private fun setTimer(seconds: Int): String {
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        )
        return "Timer started"
    }

    private fun flashlight(on: Boolean): String {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight"
        camera.setTorchMode(id, on)
        return if (on) "Flashlight on" else "Flashlight off"
    }

    private fun volume(up: Boolean): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
        return "Volume ${if (up) "up" else "down"}"
    }

    private fun brightness(percent: Int): String {
        if (!Settings.System.canWrite(context)) return "Allow Modify system settings in Control Center first"
        val resolver = context.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100).coerceAtLeast(1))
        return "Brightness set to $percent percent"
    }

    private fun doNotDisturb(on: Boolean): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) return "Allow Do Not Disturb access in Control Center first"
        manager.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return "Do Not Disturb ${if (on) "on" else "off"}"
    }

    /** Android lets apps open these switches, not flip them directly, so the screen is used. */
    private suspend fun flipFromSettings(
        name: String,
        on: Boolean,
        intent: Intent,
        isOn: () -> Boolean,
        flip: (AutomationService) -> Boolean = { it.flipFirstSystemSwitch(on) },
    ): String {
        if (isOn() == on) return "$name is already ${if (on) "on" else "off"}"
        val service = AutomationService.instance
        launch(intent)
        if (service == null) return "I opened $name settings. Please tap the switch"
        repeat(12) {
            delay(250)
            if (flip(service)) {
                // Goes back as soon as the switch changes, instead of always waiting a second and a half.
                repeat(10) { if (isOn() != on) delay(150) }
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return if (isOn() == on) "$name ${if (on) "on" else "off"}" else "I tried, but please check $name"
            }
        }
        return "I opened $name settings. Please tap the switch"
    }

    private suspend fun wifi(on: Boolean): String {
        val wifi = context.getSystemService(WifiManager::class.java)
        return flipFromSettings("Wi-Fi", on, Intent(Settings.Panel.ACTION_WIFI), isOn = { wifi.isWifiEnabled })
    }

    /** Android 12 still lets this app switch Bluetooth directly. Quick Settings is the backup. */
    private suspend fun bluetooth(on: Boolean): String {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return "This phone has no Bluetooth"
        if (adapter.isEnabled == on) return "Bluetooth is already ${onOff(on)}"
        if (granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            @Suppress("DEPRECATION", "MissingPermission")
            val started = runCatching { if (on) adapter.enable() else adapter.disable() }.getOrDefault(false)
            if (started) {
                repeat(10) {
                    delay(300)
                    if (adapter.isEnabled == on) return "Bluetooth ${onOff(on)}"
                }
            }
        }
        return quickTile("Bluetooth", listOf("bluetooth"), on, Settings.ACTION_BLUETOOTH_SETTINGS) { adapter.isEnabled }
    }

    private suspend fun mobileData(on: Boolean): String {
        val phone = context.getSystemService(TelephonyManager::class.java)
        return quickTile(
            "Mobile data", listOf("mobile data", "cellular data", "mobile network", "sim data"), on,
            Settings.ACTION_DATA_USAGE_SETTINGS,
        ) { runCatching { phone.isDataEnabled }.getOrNull() }
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private data class Mode(val name: String, val labels: List<String>, val settings: String?)

    private val modes = mapOf(
        QuickSettings.HOTSPOT to Mode("Hotspot", listOf("hotspot", "personal hotspot", "mobile hotspot", "portable hotspot", "wi-fi hotspot"), Settings.ACTION_WIRELESS_SETTINGS),
        QuickSettings.AIRPLANE to Mode("Airplane mode", listOf("airplane mode", "aeroplane mode", "flight mode", "airplane"), Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        QuickSettings.LOCATION to Mode("Location", listOf("location", "gps"), Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        QuickSettings.DARK_MODE to Mode("Dark mode", listOf("dark mode", "dark theme", "night mode"), Settings.ACTION_DISPLAY_SETTINGS),
        QuickSettings.BATTERY_SAVER to Mode("Power saving mode", listOf("power saving mode", "power saving", "battery saver", "power saver", "battery saving"), Settings.ACTION_BATTERY_SAVER_SETTINGS),
        QuickSettings.SUPER_SAVER to Mode("Super power saving mode", listOf("super power saving mode", "super power saving", "ultra power saving", "super battery saver", "extreme battery saver"), Settings.ACTION_BATTERY_SAVER_SETTINGS),
        QuickSettings.SCREEN_RECORDING to Mode("Screen recording", listOf("screen recording", "screen record", "screen recorder", "record screen"), null),
        QuickSettings.AUTO_ROTATE to Mode("Auto-rotate", listOf("auto-rotate", "auto rotate", "autorotate", "rotation", "screen rotation"), Settings.ACTION_DISPLAY_SETTINGS),
    )

    private suspend fun quickToggle(setting: String, on: Boolean): String {
        val mode = modes.getValue(setting)
        // Screen recording opens its own prompt, so the panel isn't closed after tapping it.
        return quickTile(mode.name, mode.labels, on, mode.settings, closeAfter = setting != QuickSettings.SCREEN_RECORDING) { null }
    }

    /**
     * Android doesn't let apps switch these modes directly, so this opens Quick Settings
     * and taps the tile, the same way you would.
     */
    private suspend fun quickTile(
        name: String,
        labels: List<String>,
        on: Boolean,
        settingsAction: String?,
        closeAfter: Boolean = true,
        isOn: () -> Boolean?,
    ): String {
        if (isOn() == on) return "$name is already ${onOff(on)}"
        val service = AutomationService.instance ?: return "Turn on the Accessibility Service for Pocket Assistant first"
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        // Waits only until the tiles show, up to a second.
        repeat(5) { if (service.findTile(labels) == null) delay(200) }
        var close = true
        try {
            repeat(4) {
                val tile = service.findTile(labels)
                if (tile != null) {
                    val before = isOn() ?: service.tileState(tile)
                    if (before == on) return "$name is already ${onOff(on)}"
                    if (!service.pressTile(tile)) return "I found $name but couldn't tap it"
                    close = closeAfter
                    repeat(8) { if (isOn() != on) delay(150) }
                    val after = isOn() ?: service.findTile(labels)?.let(service::tileState)
                    return when (after) {
                        on -> "$name ${onOff(on)}"
                        null -> "I tapped $name"
                        else -> "I tapped $name, but please check it, as it may need you to confirm"
                    }
                }
                service.nextQuickSettingsPage()
                delay(600)
            }
        } finally {
            if (close) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        if (settingsAction != null) {
            delay(300)
            launch(Intent(settingsAction))
            return "I couldn't find the $name tile in Quick Settings, so I opened its settings"
        }
        return "I couldn't find the $name tile. Add it to Quick Settings, then try again"
    }

    private fun ringer(mode: String): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val target = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val policy = context.getSystemService(NotificationManager::class.java)
        return try {
            audio.ringerMode = target
            when (mode) {
                "silent" -> "Phone is on silent"
                "vibrate" -> "Phone is on vibrate"
                else -> "Ring mode on"
            }
        } catch (e: SecurityException) {
            if (!policy.isNotificationPolicyAccessGranted) launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            "Allow Do Not Disturb access for Pocket Assistant, then try again"
        }
    }

    private fun webSearch(query: String, site: String?): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (site == "youtube") "https://www.youtube.com/results?search_query=$encoded" else "https://www.google.com/search?q=$encoded"
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return if (site == "youtube") "Searching YouTube for $query" else "Searching Google for $query"
    }

    private fun global(action: Int, done: String): String {
        val service = AutomationService.instance ?: return "Turn on the Accessibility Service in Control Center first"
        return if (service.performGlobalAction(action)) done else "Android refused that action"
    }

    private fun automation(block: (AutomationService) -> String): String {
        val service = AutomationService.instance ?: return "Turn on the Accessibility Service in Control Center first"
        return block(service)
    }

    // ---- Reading details from the phone, so the AI can answer questions and decide what to do next.

    private fun whenText(millis: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        val days = (today.get(Calendar.YEAR) - then.get(Calendar.YEAR)) * 365 + today.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        val day = when (days) {
            0 -> "today"
            1 -> "yesterday"
            -1 -> "tomorrow"
            else -> SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(millis))
        }
        return "$day ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(millis))}"
    }

    private fun onOff(value: Boolean?) = when (value) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }

    private fun phoneStatus(): String {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val stat = StatFs(Environment.getDataDirectory().path)
        val wifi = runCatching { context.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrNull()
        val data = runCatching { context.getSystemService(TelephonyManager::class.java).isDataEnabled }.getOrNull()
        val bluetooth = runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled }.getOrNull()
        val sound = when (context.getSystemService(AudioManager::class.java).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "ring"
        }
        val dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
        return "Battery $level%${if (battery.isCharging) ", charging" else ""}. " +
            "Storage %.1f GB free of %.0f GB. ".format(stat.availableBytes / 1e9, stat.totalBytes / 1e9) +
            "Wi-Fi ${onOff(wifi)}, mobile data ${onOff(data)}, Bluetooth ${onOff(bluetooth)}. " +
            "Sound mode $sound, Do Not Disturb ${onOff(dnd)}. " +
            "Now ${SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())}. " +
            "Next alarm: ${alarm?.let(::whenText) ?: "none"}."
    }

    private fun findContact(name: String): String {
        if (!granted(Manifest.permission.READ_CONTACTS)) return "Allow the Contacts permission in Settings first"
        val contacts = readContacts()
        val matches = ContactMatcher.top(name, contacts.keys)
        if (matches.isEmpty()) return "No contact matches $name"
        return matches.take(3).joinToString("; ") { match -> "$match: ${contacts[match].orEmpty().joinToString(", ") { it.first }}" }
    }

    /** Last 10 digits of each saved number, so calls and messages can show names. */
    private fun namesByNumber(): Map<String, String> =
        readContacts().flatMap { (name, numbers) -> numbers.map { it.first.filter(Char::isDigit).takeLast(10) to name } }.toMap()

    private fun wantedNumbers(contact: String?): Set<String>? {
        if (contact.isNullOrBlank()) return null
        val contacts = readContacts()
        val match = ContactMatcher.top(contact, contacts.keys).firstOrNull() ?: return emptySet()
        return contacts[match].orEmpty().map { it.first.filter(Char::isDigit).takeLast(10) }.toSet()
    }

    private fun recentCalls(contact: String?): String {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return "Allow the Call log permission in Settings first"
        val names = namesByNumber()
        val wanted = wantedNumbers(contact)
        if (wanted != null && wanted.isEmpty()) return "No contact matches $contact"
        val lines = ArrayList<String>()
        val columns = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, columns, null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
            while (cursor.moveToNext() && lines.size < 8) {
                val number = cursor.getString(0).orEmpty()
                val key = number.filter(Char::isDigit).takeLast(10)
                if (wanted != null && key !in wanted) continue
                val who = names[key] ?: number.ifBlank { "unknown number" }
                val kind = when (cursor.getInt(1)) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming call from"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing call to"
                    CallLog.Calls.MISSED_TYPE -> "Missed call from"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected call from"
                    else -> "Call with"
                }
                val minutes = cursor.getLong(3) / 60
                lines += "$kind $who, ${whenText(cursor.getLong(2))}" + if (minutes > 0) " ($minutes min)" else ""
            }
        }
        return if (lines.isEmpty()) "No recent calls" else lines.joinToString("; ")
    }

    private fun recentMessages(contact: String?): String {
        if (!granted(Manifest.permission.READ_SMS)) return "Allow the Read SMS permission in Settings first"
        val names = namesByNumber()
        val wanted = wantedNumbers(contact)
        if (wanted != null && wanted.isEmpty()) return "No contact matches $contact"
        val lines = ArrayList<String>()
        val columns = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, columns, null, null, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
            while (cursor.moveToNext() && lines.size < 5) {
                val address = cursor.getString(0).orEmpty()
                val key = address.filter(Char::isDigit).takeLast(10)
                if (wanted != null && key !in wanted) continue
                val who = names[key] ?: address
                // Codes and passwords are hidden before anything sees the text.
                val body = SecretRedactor.redact(cursor.getString(1).orEmpty()).replace(Regex("\\s+"), " ").take(120)
                lines += "From $who, ${whenText(cursor.getLong(2))}: $body"
            }
        }
        return if (lines.isEmpty()) "No messages found" else lines.joinToString("; ")
    }

    private fun calendarEvents(days: Int): String {
        if (!granted(Manifest.permission.READ_CALENDAR)) return "Allow the Calendar permission in Settings first"
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
        val end = start + days.coerceIn(1, 7) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_ID,
        )
        val lines = ArrayList<String>()
        context.contentResolver.query(uri, columns, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext() && lines.size < 8) {
                val title = SecretRedactor.redact(cursor.getString(0).orEmpty()).take(80).ifBlank { "Untitled event" }
                val time = if (cursor.getInt(2) == 1) "all day" else whenText(cursor.getLong(1))
                lines += "$title, $time (id ${cursor.getLong(3)})"
            }
        }
        return if (lines.isEmpty()) "No events in the next $days day(s)" else lines.joinToString("; ")
    }


    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun readContacts(): Map<String, List<Pair<String, Boolean>>> {
        if (!granted(Manifest.permission.READ_CONTACTS)) return emptyMap()
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        val numbers = LinkedHashMap<String, MutableList<Pair<String, Boolean>>>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: cursor.getString(2) ?: continue
                numbers.getOrPut(name) { ArrayList() }.add(number to (cursor.getInt(3) == 1))
            }
        }
        return numbers
    }

    /** Contact names, used to tell where a name ends and a message begins. */
    fun contactNames(): Collection<String> = runCatching { readContacts().keys }.getOrDefault(emptySet())

    /** Finds the contact you meant. If two are equally likely, it asks instead of guessing. */
    private fun resolveNumber(target: String): Pair<String?, String?> {
        if (PhoneTarget.looksLikeNumber(target)) return target to null
        if (!granted(Manifest.permission.READ_CONTACTS)) return null to "Allow Contacts permission in the app first"
        val numbers = readContacts()
        val found = ContactMatcher.top(target, numbers.keys)
        val name = when (found.size) {
            0 -> return null to "I couldn't find $target in your contacts"
            1 -> found.first()
            else -> return null to "I found ${found.take(3).joinToString(" and ")}. Please say the full name"
        }
        val options = numbers.getValue(name)
        return (options.firstOrNull { it.second } ?: options.first()).first to null
    }

    private fun sendSms(to: String, body: String): String {
        if (!granted(Manifest.permission.SEND_SMS)) return "SMS permission is off"
        val (number, error) = resolveNumber(to)
        if (number == null) return error ?: "I couldn't find that number"
        val sms = context.getSystemService(SmsManager::class.java)
        return try {
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(body), null, null)
            "SMS sent to $to"
        } catch (e: Exception) {
            "The SMS couldn't be sent. Check that a SIM is set for SMS"
        }
    }

    private fun placeCall(to: String): String {
        if (!granted(Manifest.permission.CALL_PHONE)) return "Phone permission is off"
        val (number, error) = resolveNumber(to)
        if (number == null) return error ?: "I couldn't find that number"
        launch(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
        return "Calling $to"
    }

    private fun installedWhatsApp(): String? = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    private fun waDigits(number: String): String {
        var digits = PhoneTarget.digits(number)
        if (digits.length == 11 && digits.startsWith("0")) digits = digits.drop(1)
        if (digits.length == 10) digits = "91$digits"
        return digits
    }

    private fun openChat(contact: String, app: String): String {
        val (number, error) = resolveNumber(contact)
        if (number == null) return error ?: "I couldn't find $contact"
        if (app == "sms") {
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${PhoneTarget.digits(number)}")))
            return "Opening your messages with $contact"
        }
        val pkg = installedWhatsApp() ?: return "WhatsApp isn't installed"
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${waDigits(number)}")).setPackage(pkg))
        return "Opening your WhatsApp chat with $contact"
    }

    /** Starts the app, waits for it, then taps the tab, like you would. */
    private suspend fun appSection(section: AppSection): String {
        val service = AutomationService.instance ?: return "Turn on the Accessibility Service for Pocket Assistant first"
        val intent = context.packageManager.getLaunchIntentForPackage(section.packageName)
            ?: return "${section.app} isn't installed"
        launch(intent)
        repeat(20) {
            delay(250)
            if (service.targetPackage() == section.packageName) {
                delay(400)
                repeat(8) {
                    if (section.tab.any { tab -> service.tap(tab) }) return "Opened ${section.tab.first()} in ${section.app}"
                    delay(300)
                }
                return "I opened ${section.app}, but couldn't find ${section.tab.first()}"
            }
        }
        return "${section.app} didn't open"
    }

    private suspend fun readScreen(explain: Boolean, question: String): String {
        val service = AutomationService.instance ?: return "Turn on the Accessibility Service for Pocket Assistant first"
        val items = service.screenItems() ?: return "I can't read this screen. Open the app you want first"
        if (items.isEmpty()) return "I can't find any text on this screen"
        val text = SecretRedactor.redact(items.joinToString(". ") { it.first.label })
        if (!explain) return "On the screen: " + text.take(700)
        return try {
            GemmaEngine.explainScreen(context, text, question.ifBlank { "Explain my screen" }).trim().take(700)
        } catch (e: IllegalStateException) {
            "Set up the Gemma model to get explanations. Here's what's on the screen: " + text.take(500)
        }
    }

    /**
     * Searches Google in your browser and reads the answer from the results page.
     * This app has no internet access itself. The search goes through your browser.
     */
    private suspend fun webAnswer(query: String): String {
        val service = AutomationService.instance ?: return "Turn on the Accessibility Service for Pocket Assistant first"
        val own = context.packageName
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))))
        val keywords = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP_WORDS }
        var lastSize = -1
        var text = ""
        for (attempt in 0 until 24) {
            delay(500)
            if (service.targetPackage() == own) continue
            val items = service.screenItems()?.filter { (item, _) ->
                item.kind == "text" || (item.kind == "button" && item.label.length >= 20)
            } ?: continue
            val joined = items.joinToString("\n") { it.first.label }
            val relevant = keywords.isEmpty() || keywords.any { joined.lowercase().contains(it) }
            // Waits until the page has loaded and stopped changing.
            if (relevant && joined.length > 200 && joined.length == lastSize) {
                text = SecretRedactor.redact(joined)
                break
            }
            lastSize = joined.length
        }
        if (text.isEmpty()) return "I opened the Google results, but couldn't read them. They're on your screen now"
        return try {
            GemmaEngine.answerFromSearch(context, query, text).trim().take(500)
        } catch (e: IllegalStateException) {
            val lines = text.lines().filter { line -> line.length >= 20 && keywords.any { line.lowercase().contains(it) } }.take(2)
            if (lines.isEmpty()) "The results are on your screen" else "Here's what Google shows: " + lines.joinToString(". ")
        }
    }

    private companion object {
        val STOP_WORDS = setOf("the", "and", "what", "who", "when", "where", "how", "was", "were", "today", "latest", "tell", "about")
    }

    /** Does a task inside the open app, one checked step at a time, for at most six steps. */
    private suspend fun screenTask(goal: String): String {
        val service = AutomationService.instance ?: return "Turn on the Accessibility Service for Pocket Assistant first"
        ModelStore.problem(context)?.let { return "Screen tasks need the Gemma model. $it" }
        val done = ArrayList<String>()
        repeat(ScreenAgent.MAX_STEPS) {
            val items = service.screenItems() ?: return "I can't control this screen"
            val visible = items.take(ScreenAgent.MAX_ITEMS)
            val prompt = ScreenAgent.prompt(goal, visible.map { it.first.copy(label = SecretRedactor.redact(it.first.label)) }, done)
            val raw = try {
                GemmaEngine.screenStep(context, prompt)
            } catch (e: IllegalStateException) {
                return e.message ?: "The AI model isn't ready"
            }
            when (val action = ScreenAgent.parse(raw, visible.size, goal)) {
                null -> return "I couldn't work out the next step, so I stopped"
                is ScreenAction.Done -> return action.say.ifBlank { "Done" }
                is ScreenAction.Tap -> {
                    val label = visible[action.index].first.label
                    if (ScreenAgent.isRisky(label)) return "I stopped before tapping \"$label\". Say tap $label if you want me to"
                    if (!service.pressNode(visible[action.index].second)) return "I couldn't tap $label"
                    done += "tapped \"$label\""
                }
                is ScreenAction.Type -> {
                    if (!service.typeInto(visible[action.index].second, action.text)) return "I couldn't type there"
                    done += "typed \"${action.text}\""
                }
                is ScreenAction.Scroll -> {
                    service.scroll(action.down)
                    done += if (action.down) "scrolled down" else "scrolled up"
                }
                ScreenAction.Back -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    done += "went back"
                }
            }
            delay(1000)
        }
        return "I tried ${ScreenAgent.MAX_STEPS} steps but didn't finish. Please check the screen"
    }

    private suspend fun sendWhatsApp(to: String, body: String): String {
        val (number, error) = resolveNumber(to)
        if (number == null) return error ?: "I couldn't find that number"
        val service = AutomationService.instance ?: return "Turn on the Accessibility Service for Pocket Assistant first"
        var digits = PhoneTarget.digits(number)
        if (digits.length == 11 && digits.startsWith("0")) digits = digits.drop(1)
        if (digits.length == 10) digits = "91$digits"
        val encoded = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
        val link = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=$encoded")
        val app = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        } ?: return "WhatsApp isn't installed"
        launch(Intent(Intent.ACTION_VIEW, link).setPackage(app))
        repeat(24) {
            delay(300)
            if (service.tapSendInWhatsApp(app)) {
                delay(500)
                return "WhatsApp message sent to $to"
            }
        }
        return "I opened the chat with your message, but couldn't press Send. Please tap Send"
    }
}
