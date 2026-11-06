package dev.aravind.assistant

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.aravind.assistant.policy.BlockedApps
import dev.aravind.assistant.policy.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Runs the voice loop. Android shows its mic indicator whenever the microphone is open. */
class AssistantService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fallback: TextToSpeech? = null
    private var engines: VoiceEngines? = null
    private var loop: VoiceLoop? = null
    private var output: VoiceOutput? = null
    private var loopJob: Job? = null
    private var callJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Assistant status", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        fallback = TextToSpeech(this) { code -> if (code == TextToSpeech.SUCCESS) fallback?.language = Locale("en", "IN") }
        loopJob = scope.launch { startVoice() }
    }

    private suspend fun startVoice() {
        try {
            AssistantRuntime.status.value = "Preparing voice"
            withContext(Dispatchers.IO) { VoiceAssets.ensure(this@AssistantService) }
            val created = VoiceEngines(this)
            engines = created
            output = VoiceOutput(created) { loop }
            val agent = Agent(this, ::speak) { hear() }
            val voiceLoop = VoiceLoop(this, created, scope) { agent.handle(it) }
            loop = voiceLoop
            AssistantRuntime.status.value = "Ready"
            voiceLoop.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Audit.log(this, "voice_start_failed:${e.javaClass.simpleName}")
            AssistantRuntime.status.value = "Voice failed to start"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> AssistantRuntime.stopAll(this, "notification")
            ACTION_LISTEN -> when {
                !AssistantRuntime.armed.value -> stopSelf()
                AssistantRuntime.inPhoneCall(this) -> Unit
                else -> loop?.requestListen()
            }
            else -> if (!AssistantRuntime.armed.value) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        fun action(name: String, label: String): Notification.Action {
            val intent = Intent(this, AssistantService::class.java).setAction(name)
            val pending = PendingIntent.getService(this, name.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(null, label, pending).build()
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_assistant)
            .setContentTitle("Pocket Assistant is on")
            .setContentText("Say Hey Assistant, or tap Talk")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(ACTION_LISTEN, "Talk"))
            .addAction(action(ACTION_STOP, "Stop"))
            .build()
    }

    private suspend fun speak(text: String) {
        AssistantRuntime.say("Assistant: $text")
        val spoken = output?.say(text) == true
        if (!spoken) fallback?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private suspend fun hear(): Heard? {
        val active = loop ?: return null
        val samples = active.capture(9_000) ?: return null
        return withContext(Dispatchers.Default) { active.understand(samples) }
    }

    /** Records five phrases and saves only the resulting voiceprint. */
    fun enrollVoice() {
        val active = loop ?: return
        val voice = engines ?: return
        scope.launch {
            val prints = ArrayList<FloatArray>()
            val phrases = listOf(
                "Say: my voice unlocks this phone",
                "Say: the quick brown fox jumps over the lazy dog",
                "Say: please open my messages and read them",
                "Say: call my friend and tell him I am late",
                "Say: today is a good day to build something",
            )
            for (phrase in phrases) {
                speak(phrase)
                val samples = active.capture(9_000) ?: continue
                withContext(Dispatchers.Default) { voice.embedding(samples) }?.let { prints += it }
            }
            if (prints.size >= 3) {
                VoiceId.save(this@AssistantService, prints)
                Audit.log(this@AssistantService, "voice_enrolled")
                speak("Your voice is saved")
            } else {
                speak("I couldn't hear enough, so please try again")
            }
        }
    }

    fun onAllowedIncomingCall(number: String) {
        if (callJob?.isActive == true) return
        val active = loop ?: return
        callJob = scope.launch {
            val session = CallSession(this@AssistantService, ::speak, { active.capture(it) }, { active.understand(it) })
            session.run(number)
        }
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        fallback?.stop()
        fallback?.shutdown()
        fallback = null
        GemmaEngine.unload()
        GemmaEngine.clearHistory()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "status"
        const val ACTION_LISTEN = "dev.aravind.assistant.LISTEN"
        const val ACTION_STOP = "dev.aravind.assistant.STOP"

        @Volatile
        var instance: AssistantService? = null
    }
}

/** Reads only visible screen items, skips password fields, and doesn't filter key events. */
class AutomationService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPackage = event.packageName?.toString()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun usable(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || node.isPassword) return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return !bounds.isEmpty
    }

    private fun allowedHere(): Boolean =
        AssistantRuntime.armed.value && !BlockedApps.blocksAutomation(rootInActiveWindow?.packageName?.toString(), packageName)

    private fun search(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (usable(node) && test(node)) return node
        for (index in 0 until node.childCount) {
            search(node.getChild(index), test)?.let { return it }
        }
        return null
    }

    private fun click(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        while (node != null) {
            if (node.isClickable && node.isEnabled) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
        }
        return false
    }

    fun tap(label: String): Boolean {
        if (!allowedHere()) return false
        val target = search(rootInActiveWindow) {
            it.text?.toString().equals(label, ignoreCase = true) ||
                it.contentDescription?.toString().equals(label, ignoreCase = true)
        } ?: return false
        return click(target)
    }

    fun scroll(down: Boolean): Boolean {
        if (!allowedHere()) return false
        val target = search(rootInActiveWindow) { it.isScrollable } ?: return false
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return target.performAction(action)
    }

    /** Works only inside WhatsApp, and only after the message was approved. */
    fun tapSendInWhatsApp(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.whatsapp") return false
        val target = search(root) {
            it.viewIdResourceName == "com.whatsapp:id/send" || it.contentDescription?.toString() == "Send"
        } ?: return false
        return click(target)
    }

    companion object {
        @Volatile
        var instance: AutomationService? = null

        @Volatile
        var foregroundPackage: String? = null
    }
}

/** Notification text is untrusted. It's only spoken, and it never reaches Gemma. */
class NotificationsService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    private fun lines(): List<String> {
        val items: Array<StatusBarNotification> = activeNotifications ?: return emptyList()
        return items.filter { !it.isOngoing && it.packageName != packageName }.takeLast(5).mapNotNull { item ->
            val extras = item.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            SecretRedactor.redact("$title: $body").takeIf { title.isNotBlank() || body.isNotBlank() }
        }
    }

    companion object {
        @Volatile
        var instance: NotificationsService? = null

        fun spokenSummary(): String {
            val service = instance ?: return "Turn on notification access in Control Center first"
            val lines = service.lines()
            return if (lines.isEmpty()) "You have no notifications" else lines.joinToString(". ")
        }
    }
}

/** The tile can stop the assistant at once. Turning it on always goes through the app. */
class StopTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        if (AssistantRuntime.armed.value) {
            AssistantRuntime.stopAll(this, "tile")
            refresh()
        } else {
            startActivityAndCollapse(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (AssistantRuntime.armed.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (AssistantRuntime.armed.value) "Stop assistant" else "Assistant off"
        tile.updateTile()
    }
}
