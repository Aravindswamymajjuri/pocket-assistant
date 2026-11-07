package dev.aravind.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import dev.aravind.assistant.policy.BlockedApps
import dev.aravind.assistant.policy.ContactMatcher
import dev.aravind.assistant.policy.PhoneTarget
import dev.aravind.assistant.policy.QuickSettings
import dev.aravind.assistant.policy.Tool
import dev.aravind.assistant.policy.ToolCall
import kotlinx.coroutines.delay
import java.net.URLEncoder

/** Runs only calls the policy engine has already approved. It rechecks app and permission limits. */
class ToolExecutor(private val context: Context) {

    suspend fun execute(call: ToolCall): String = when (call.tool) {
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

    private fun setAlarm(call: ToolCall): String {
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
        return "Alarm set for %d:%02d %s".format(hour12, minute, if (hour < 12) "AM" else "PM")
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
                delay(1500)
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
        delay(1000)
        var close = true
        try {
            repeat(4) {
                val tile = service.findTile(labels)
                if (tile != null) {
                    val before = isOn() ?: service.tileState(tile)
                    if (before == on) return "$name is already ${onOff(on)}"
                    if (!service.pressTile(tile)) return "I found $name but couldn't tap it"
                    close = closeAfter
                    delay(1200)
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
