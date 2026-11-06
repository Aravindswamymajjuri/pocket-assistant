package dev.aravind.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.SearchManager
import android.bluetooth.BluetoothManager
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
        Tool.DO_NOT_DISTURB -> doNotDisturb(call.bool("on"))
        Tool.LOCK_PHONE -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locked")
        Tool.WEB_SEARCH -> webSearch(call.text("query"))
        Tool.GO_BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Done")
        Tool.GO_HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Done")
        Tool.SCROLL -> automation { if (it.scroll(call.text("direction") == "down")) "Done" else "Nothing to scroll here" }
        Tool.TAP_TEXT -> automation { if (it.tap(call.text("text"))) "Done" else "I couldn't find that on the screen" }
        Tool.READ_NOTIFICATIONS -> NotificationsService.spokenSummary()
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
        launch(intent)
        return "Alarm set for %02d:%02d".format(hour, minute)
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

    private suspend fun bluetooth(on: Boolean): String {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return "This phone has no Bluetooth"
        return flipFromSettings("Bluetooth", on, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), isOn = { adapter.isEnabled })
    }

    private suspend fun mobileData(on: Boolean): String {
        val phone = context.getSystemService(TelephonyManager::class.java)
        return flipFromSettings(
            "Mobile data", on, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
            { runCatching { phone.isDataEnabled }.getOrDefault(!on) },
            { it.flipSwitchNear(listOf("mobile data", "mobile network", "cellular", "sim"), on) },
        )
    }

    private fun webSearch(query: String): String {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            launch(intent)
        } else {
            val encoded = URLEncoder.encode(query, "UTF-8")
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=$encoded")))
        }
        return "Searching for $query"
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

    /** Finds the contact you meant. If two are equally likely, it asks instead of guessing. */
    private fun resolveNumber(target: String): Pair<String?, String?> {
        if (PhoneTarget.looksLikeNumber(target)) return target to null
        if (!granted(Manifest.permission.READ_CONTACTS)) return null to "Allow Contacts permission in the app first"
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
        val name = ContactMatcher.best(target, numbers.keys) ?: return null to "I couldn't find $target in your contacts"
        val options = numbers.getValue(name)
        return (options.firstOrNull { it.second } ?: options.first()).first to null
    }

    private fun sendSms(to: String, body: String): String {
        if (!granted(Manifest.permission.SEND_SMS)) return "SMS permission is off"
        val (number, error) = resolveNumber(to)
        if (number == null) return error ?: "I couldn't find that number"
        val sms = context.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(body), null, null)
        return "SMS sent to $to"
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
        val service = AutomationService.instance ?: return "Turn on the Accessibility Service in Control Center first"
        var digits = PhoneTarget.digits(number)
        if (digits.length == 10) digits = "91$digits"
        val encoded = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=$encoded")).setPackage("com.whatsapp"))
        repeat(16) {
            delay(250)
            if (AutomationService.foregroundPackage == "com.whatsapp" && service.tapSendInWhatsApp()) {
                return "WhatsApp message sent to $to"
            }
        }
        return "I opened the chat, but couldn't press Send. Please check it"
    }
}
