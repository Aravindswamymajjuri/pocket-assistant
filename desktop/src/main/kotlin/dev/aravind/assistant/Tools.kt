package dev.aravind.assistant

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import dev.aravind.assistant.policy.BlockedApps
import dev.aravind.assistant.policy.Category
import dev.aravind.assistant.policy.CommandRouter
import dev.aravind.assistant.policy.ConfirmationAuthority
import dev.aravind.assistant.policy.Decision
import dev.aravind.assistant.policy.ModelOutput
import dev.aravind.assistant.policy.ModelOutputParser
import dev.aravind.assistant.policy.PhoneTarget
import dev.aravind.assistant.policy.PolicyContext
import dev.aravind.assistant.policy.PolicyEngine
import dev.aravind.assistant.policy.Source
import dev.aravind.assistant.policy.SpokenChallenge
import dev.aravind.assistant.policy.TaskLimits
import dev.aravind.assistant.policy.Tool
import dev.aravind.assistant.policy.ToolCall
import java.awt.Robot
import java.io.File
import java.net.URLEncoder
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.SwingUtilities

private interface KeyboardApi : StdCallLibrary {
    fun keybd_event(key: Byte, scan: Byte, flags: Int, extra: Pointer?)

    companion object {
        val INSTANCE: KeyboardApi = Native.load("user32", KeyboardApi::class.java)
    }
}

object Win {
    private const val KEY_UP = 2

    fun press(vararg keys: Int) {
        keys.forEach { KeyboardApi.INSTANCE.keybd_event(it.toByte(), 0, 0, null) }
        keys.reversed().forEach { KeyboardApi.INSTANCE.keybd_event(it.toByte(), 0, KEY_UP, null) }
    }

    /** Returns the lower-case program name of the window you're using. */
    fun foregroundExe(): String? = runCatching {
        val window = User32.INSTANCE.GetForegroundWindow() ?: return null
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(window, pid)
        File(Kernel32Util.QueryFullProcessImageName(pid.value, 0)).name.lowercase()
    }.getOrNull()

    fun foregroundHandle(): Long? = runCatching {
        Pointer.nativeValue(User32.INSTANCE.GetForegroundWindow().pointer)
    }.getOrNull()

    /** Opens a URI through Windows without a shell, so its text can't run as a command. */
    fun open(target: String) {
        ProcessBuilder(File(System.getenv("SystemRoot") ?: "C:\\Windows", "explorer.exe").path, target).start()
    }
}

/** Runs only calls the policy engine has approved. */
object WindowsTools {
    /** Phone-only tools aren't offered to Gemma on the laptop. */
    val supported = setOf(
        Tool.OPEN_APP, Tool.SET_ALARM, Tool.SET_TIMER, Tool.VOLUME, Tool.BRIGHTNESS, Tool.WIFI, Tool.BLUETOOTH,
        Tool.DO_NOT_DISTURB, Tool.LOCK_PHONE, Tool.WEB_SEARCH, Tool.GO_BACK, Tool.GO_HOME, Tool.SCROLL, Tool.TAP_TEXT,
        Tool.SEND_WHATSAPP, Tool.PLACE_CALL,
    )

    private const val VK_ALT = 0x12
    private const val VK_LEFT = 0x25
    private const val VK_WIN = 0x5B
    private const val VK_D = 0x44
    private const val VK_VOLUME_DOWN = 0xAE
    private const val VK_VOLUME_UP = 0xAF
    private const val VK_RETURN = 0x0D

    fun execute(call: ToolCall, speak: (String) -> Unit): String = when (call.tool) {
        Tool.OPEN_APP -> openApp(call.text("name"))
        Tool.SET_ALARM -> alarm(call.long("hour").toInt(), call.long("minute").toInt(), speak)
        Tool.SET_TIMER -> timer(call.long("seconds"), speak)
        Tool.VOLUME -> {
            repeat(4) { Win.press(if (call.text("direction") == "up") VK_VOLUME_UP else VK_VOLUME_DOWN) }
            "Volume ${call.text("direction")}"
        }
        Tool.BRIGHTNESS -> brightness(call.long("percent").toInt())
        Tool.WIFI -> radio("WiFi", "Wi-Fi", call.bool("on"))
        Tool.BLUETOOTH -> radio("Bluetooth", "Bluetooth", call.bool("on"))
        Tool.DO_NOT_DISTURB -> {
            Win.open("ms-settings:quietmomentshome")
            "Windows has no Do Not Disturb control for apps, so I opened its settings"
        }
        Tool.LOCK_PHONE -> if (User32.INSTANCE.LockWorkStation().booleanValue()) "Locked" else "Windows refused to lock"
        Tool.WEB_SEARCH -> {
            Win.open("https://duckduckgo.com/?q=" + URLEncoder.encode(call.text("query"), "UTF-8"))
            "Searching for ${call.text("query")}"
        }
        Tool.GO_BACK -> {
            Win.press(VK_ALT, VK_LEFT)
            "Done"
        }
        Tool.GO_HOME -> {
            Win.press(VK_WIN, VK_D)
            "Done"
        }
        Tool.SCROLL -> {
            Robot().mouseWheel(if (call.text("direction") == "down") 6 else -6)
            "Done"
        }
        Tool.TAP_TEXT -> tap(call.text("text"))
        Tool.SEND_WHATSAPP -> whatsapp(call.text("to"), call.text("body"))
        Tool.PLACE_CALL -> placeCall(call.text("to"))
        else -> "That needs a phone, so it isn't available on this laptop"
    }

    private fun startApps(): List<Pair<String, String>> =
        Ps.run("Get-StartApps | ForEach-Object { ${'$'}_.Name + \"`t\" + ${'$'}_.AppID }").lines()
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }

    private fun openApp(name: String): String {
        val wanted = name.removeSuffix(" app").trim()
        val apps = startApps()
        val match = apps.firstOrNull { it.first.equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.first.startsWith(wanted, ignoreCase = true) }
            ?: return "I couldn't find an app called $name"
        if (BlockedApps.blocksOpening(null, match.first)) return "That app must be used manually"
        Win.open("shell:AppsFolder\\${match.second}")
        return "Opening ${match.first}"
    }

    private fun later(delayMillis: Long, speak: (String) -> Unit, message: String) {
        Thread {
            Thread.sleep(delayMillis)
            if (Runtime.armed) {
                java.awt.Toolkit.getDefaultToolkit().beep()
                speak(message)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun alarm(hour: Int, minute: Int, speak: (String) -> Unit): String {
        val now = LocalDateTime.now()
        var at = now.withHour(hour).withMinute(minute).withSecond(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        later(java.time.Duration.between(now, at).toMillis(), speak, "Your alarm is ringing")
        return "Alarm set for %02d:%02d. It only rings while the assistant is on".format(hour, minute)
    }

    private fun timer(seconds: Long, speak: (String) -> Unit): String {
        later(seconds * 1000, speak, "Your timer is done")
        return "Timer started"
    }

    private fun brightness(percent: Int): String {
        val result = Ps.run(
            "Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods | " +
                "Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{Timeout=[uint32]1;Brightness=[byte]\$env:PA_VALUE} | Out-Null; 'OK'",
            mapOf("PA_VALUE" to percent.toString()),
        )
        return if (result.endsWith("OK")) "Brightness set to $percent percent" else "This screen doesn't support brightness control"
    }

    private fun radio(kind: String, name: String, on: Boolean): String {
        val script = Ps.WINRT_AWAIT + """
[Windows.Devices.Radios.Radio,Windows.System.Devices,ContentType=WindowsRuntime] | Out-Null
[Windows.Devices.Radios.RadioAccessStatus,Windows.System.Devices,ContentType=WindowsRuntime] | Out-Null
[Windows.Devices.Radios.RadioState,Windows.System.Devices,ContentType=WindowsRuntime] | Out-Null
Await ([Windows.Devices.Radios.Radio]::RequestAccessAsync()) ([Windows.Devices.Radios.RadioAccessStatus]) | Out-Null
${'$'}radios = Await ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]])
${'$'}radio = ${'$'}radios | Where-Object { ${'$'}_.Kind -eq ${'$'}env:PA_KIND } | Select-Object -First 1
if (${'$'}null -eq ${'$'}radio) { 'NONE'; exit }
Await (${'$'}radio.SetStateAsync(${'$'}env:PA_STATE)) ([Windows.Devices.Radios.RadioAccessStatus])
"""
        val result = Ps.run(script, mapOf("PA_KIND" to kind, "PA_STATE" to if (on) "On" else "Off"))
        return when {
            result.endsWith("Allowed") -> "$name ${if (on) "on" else "off"}"
            result.endsWith("NONE") -> "This laptop has no $name radio"
            else -> "Windows didn't allow changing $name"
        }
    }

    private fun tap(text: String): String {
        val hwnd = Win.foregroundHandle() ?: return "I can't find the active window"
        val script = """
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
${'$'}root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr][long]${'$'}env:PA_HWND)
${'$'}cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, ${'$'}env:PA_TEXT, [System.Windows.Automation.PropertyConditionFlags]::IgnoreCase)
${'$'}el = ${'$'}root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, ${'$'}cond)
if (${'$'}null -eq ${'$'}el) { 'MISSING'; exit }
if (${'$'}el.Current.IsPassword -or ${'$'}el.Current.IsOffscreen) { 'MISSING'; exit }
${'$'}pattern = ${'$'}null
if (${'$'}el.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]${'$'}pattern)) { ${'$'}pattern.Invoke(); 'OK'; exit }
if (${'$'}el.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]${'$'}pattern)) { ${'$'}pattern.Toggle(); 'OK'; exit }
'MISSING'
"""
        val result = Ps.run(script, mapOf("PA_HWND" to hwnd.toString(), "PA_TEXT" to text))
        return if (result.endsWith("OK")) "Done" else "I couldn't find that on the screen"
    }

    private fun resolve(target: String): String? =
        if (PhoneTarget.looksLikeNumber(target)) target else Contacts.find(target)

    private fun whatsapp(to: String, body: String): String {
        val number = resolve(to) ?: return "Add $to to your contacts in Control Center first"
        if (!PhoneTarget.isSafe(number)) return "That number isn't valid"
        var digits = PhoneTarget.digits(number)
        if (digits.length == 10) digits = "91$digits"
        Win.open("whatsapp://send?phone=$digits&text=" + URLEncoder.encode(body, "UTF-8").replace("+", "%20"))
        repeat(24) {
            Thread.sleep(250)
            if (Win.foregroundExe()?.startsWith("whatsapp") == true) {
                Thread.sleep(1200)
                if (Win.foregroundExe()?.startsWith("whatsapp") == true) {
                    Win.press(VK_RETURN)
                    return "WhatsApp message sent to $to"
                }
            }
        }
        return "I opened the chat, but couldn't press Send. Is WhatsApp installed?"
    }

    private fun placeCall(to: String): String {
        val number = resolve(to) ?: return "Add $to to your contacts in Control Center first"
        if (!PhoneTarget.isSafe(number)) return "That number isn't valid"
        Win.open("tel:" + PhoneTarget.digits(number))
        return "I sent the call to your phone-calling app. Calls need Phone Link"
    }
}

class Agent(private val speak: (String) -> Unit, private val listen: () -> Heard?) {
    fun handle(heard: Heard) {
        val utterance = heard.text
        Runtime.say("You: $utterance")
        if (CommandRouter.isStop(utterance)) {
            Runtime.stopAll("voice")
            return
        }
        val limits = TaskLimits(System::currentTimeMillis)
        val whole = CommandRouter.route(utterance)
        val steps = if (whole?.tool?.hasRecipient == true) listOf(utterance) else CommandRouter.steps(utterance)
        for (step in steps) {
            if (!Runtime.isActive()) return
            val routed = CommandRouter.route(step)
            val calls: List<ToolCall> = if (routed != null) {
                listOf(routed)
            } else {
                when (val output = askGemma(step)) {
                    null -> return
                    is ModelOutput.Reply -> {
                        speak(output.text)
                        continue
                    }
                    is ModelOutput.Invalid -> {
                        Audit.log("model_output_rejected")
                        speak("I couldn't work out a safe action for that")
                        return
                    }
                    is ModelOutput.Call -> listOf(output.call)
                    is ModelOutput.Plan -> output.calls
                }
            }
            for (call in calls) {
                if (!limits.tryStep()) {
                    speak("That task took too long, so I stopped")
                    return
                }
                if (!run(call, utterance, heard.verified)) return
                if (steps.size > 1 || calls.size > 1) Thread.sleep(700)
            }
        }
    }

    private fun askGemma(text: String): ModelOutput? {
        val raw = try {
            Gemma.ask(text)
        } catch (e: IllegalStateException) {
            speak(e.message ?: "The AI model isn't ready")
            return null
        } catch (e: Exception) {
            Audit.log("model_error")
            speak("The AI model couldn't run. I can still do simple commands")
            return null
        }
        return ModelOutputParser.parse(raw)
    }

    private fun run(call: ToolCall, utterance: String, verified: Boolean): Boolean {
        if (call.tool !in WindowsTools.supported) {
            speak("That needs a phone, so it isn't available on this laptop")
            return false
        }
        val foreground = Win.foregroundExe()
        val policy = PolicyContext(
            armed = Runtime.isActive(),
            source = Source.LIVE_USER,
            inPhoneCall = false,
            tainted = false,
            voiceVerified = verified,
            gateEnabled = { category: Category -> Features.gate(category) },
            originalUtterance = utterance,
            foregroundPackage = foreground,
            ownPackage = "pocketassistant.exe",
            outboundAllowed = Runtime.rateLimiter.allowed(),
        )
        when (val decision = PolicyEngine.evaluate(call, policy)) {
            is Decision.Deny -> {
                Audit.log("denied:${call.tool.id}")
                speak(decision.reason)
                return false
            }
            is Decision.Allow -> Unit
            is Decision.Confirm -> if (!voiceApprove(call, decision.readback, foreground)) return false
            is Decision.Biometric -> if (!secureApprove(call, decision.readback, foreground)) return false
        }
        if (call.tool.hasRecipient) Runtime.rateLimiter.record()
        val result = try {
            WindowsTools.execute(call, speak)
        } catch (e: Exception) {
            Audit.log("failed:${call.tool.id}")
            speak("That action failed")
            return false
        }
        Features.markUsed(call.tool.category)
        Audit.log("done:${call.tool.id}")
        speak(result)
        return true
    }

    private fun voiceApprove(call: ToolCall, readback: String, foreground: String?): Boolean {
        val authority: ConfirmationAuthority = Runtime.authority ?: return false
        val code = SpokenChallenge.create()
        speak("$readback. To confirm, say ${SpokenChallenge.spaced(code)}")
        val answer = listen()
        if (answer == null || !answer.verified || !SpokenChallenge.matches(code, answer.text)) {
            Audit.log("voice_confirm_failed:${call.tool.id}")
            return secureApprove(call, readback, foreground)
        }
        val token = authority.issue(call, foreground)
        return authority.verify(token, call, currentApp(call, foreground))
    }

    private fun secureApprove(call: ToolCall, readback: String, foreground: String?): Boolean {
        val authority: ConfirmationAuthority = Runtime.authority ?: return false
        val token = authority.issue(call, foreground)
        speak("Please confirm on the screen")
        val approved = Auth.confirm(readback)
        if (!approved) {
            Audit.log("not_approved:${call.tool.id}")
            speak("Cancelled")
            return false
        }
        val valid = authority.verify(token, call, currentApp(call, foreground))
        if (!valid) speak("That approval is no longer valid, so I cancelled it")
        return valid
    }

    /** Screen actions must land on the same app that was open when you were asked. */
    private fun currentApp(call: ToolCall, foreground: String?): String? {
        if (call.tool.category != Category.SCREEN) return foreground
        Thread.sleep(500)
        return Win.foregroundExe()
    }
}

/** Windows Hello when it's available, otherwise your app PIN. */
object Auth {
    private fun onUi(block: () -> Boolean): Boolean {
        val result = CompletableFuture<Boolean>()
        SwingUtilities.invokeLater { result.complete(runCatching(block).getOrDefault(false)) }
        return result.get()
    }

    private fun askPin(title: String, text: String): CharArray? = run {
        val field = JPasswordField(12)
        val choice = JOptionPane.showConfirmDialog(null, arrayOf(text, field), title, JOptionPane.OK_CANCEL_OPTION)
        if (choice == JOptionPane.OK_OPTION) field.password else null
    }

    /** Asks for Windows Hello or your PIN. */
    fun verify(reason: String): Boolean {
        when (WindowsHello.verify(reason)) {
            true -> return true
            false -> return false
            null -> Unit
        }
        return onUi {
            if (!Pin.isSet()) {
                val pin = askPin("Create a PIN", "Windows Hello isn't available. Create a PIN of at least 6 digits.")
                if (pin == null || pin.size < 6) return@onUi false
                Pin.set(pin)
                true
            } else {
                askPin("Enter your PIN", reason)?.let(Pin::check) == true
            }
        }
    }

    /** Shows the app-built readback, then asks for proof that it's you. */
    fun confirm(readback: String): Boolean {
        val agreed = onUi {
            JOptionPane.showConfirmDialog(null, readback, "Confirm this action", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION
        }
        return agreed && verify("Approve: ${readback.take(100)}")
    }
}
