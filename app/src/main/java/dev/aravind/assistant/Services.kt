package dev.aravind.assistant

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.aravind.assistant.policy.BlockedApps
import dev.aravind.assistant.policy.Script
import dev.aravind.assistant.policy.ScriptDetect
import dev.aravind.assistant.policy.SecretRedactor
import dev.aravind.assistant.policy.WakeName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
            .setContentText("Say ${WakeName.phrase(Features.wakeName(this)).lowercase()}, or tap Talk")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(ACTION_LISTEN, "Talk"))
            .addAction(action(ACTION_STOP, "Stop"))
            .build()
    }

    /** Piper speaks English and Hindi. Telugu uses Android's voice, because no Piper voice is available. */
    private suspend fun speak(text: String) {
        AssistantRuntime.say("Assistant: $text")
        val script = ScriptDetect.of(text)
        val spoken = when (script) {
            Script.TELUGU -> false
            Script.DEVANAGARI -> output?.say(text, useHindi = true) == true
            Script.LATIN -> output?.say(text, useHindi = false) == true
        }
        if (!spoken) {
            val locale = when (script) {
                Script.TELUGU -> Locale("te", "IN")
                Script.DEVANAGARI -> Locale("hi", "IN")
                Script.LATIN -> Locale("en", "IN")
            }
            systemSay(text, locale)
        }
    }

    private suspend fun systemSay(text: String, locale: Locale) {
        val voice = fallback ?: return
        if (voice.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
            AssistantRuntime.say("Install the ${locale.displayLanguage} voice in Android text-to-speech settings")
            return
        }
        voice.language = locale
        val done = CompletableDeferred<Unit>()
        voice.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                done.complete(Unit)
            }

            @Deprecated("Required by the Android API")
            override fun onError(utteranceId: String?) {
                done.complete(Unit)
            }
        })
        loop?.speaking = true
        voice.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        withTimeoutOrNull(30_000) { done.await() }
        delay(250)
        loop?.speaking = false
    }

    /** Spoken codes are English digits, so they're always read in English. */
    private suspend fun hear(): Heard? {
        val active = loop ?: return null
        val samples = active.capture(9_000) ?: return null
        return withContext(Dispatchers.Default) { active.understand(samples, "en") }
    }

    fun onWakeNameChanged() {
        loop?.reloadWakeName()
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
            val session = CallSession(this@AssistantService, ::speak, { active.capture(it) }, { active.understand(it, "auto") })
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
        WhisperCpp.release()
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
            val current = event.packageName?.toString()
            // The assistant's own screens never count as the app you're using.
            if (current != null && current != packageName && current != SYSTEM_UI) foregroundPackage = current
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

    /** The top app window that isn't the assistant itself or the status bar. */
    fun targetRoot(): AccessibilityNodeInfo? {
        val fromWindows = runCatching {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString().let { p -> p != packageName && p != SYSTEM_UI } }
        }.getOrNull()
        return fromWindows ?: rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
    }

    fun targetPackage(): String? = targetRoot()?.packageName?.toString()

    private fun allowedRoot(): AccessibilityNodeInfo? {
        if (!AssistantRuntime.armed.value) return null
        val root = targetRoot() ?: return null
        return root.takeUnless { BlockedApps.blocksAutomation(it.packageName?.toString(), packageName) }
    }

    private fun gesture(path: Path, duration: Long): Boolean = dispatchGesture(
        GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
        null,
        null,
    )

    private fun tapAt(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 60)

    private fun press(node: AccessibilityNodeInfo): Boolean {
        if (click(node)) return true
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return !bounds.isEmpty && tapAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

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

    private fun label(node: AccessibilityNodeInfo) =
        (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim().lowercase()

    /** Taps an exact match first, then the closest partial match. */
    fun tap(text: String): Boolean {
        val root = allowedRoot() ?: return false
        val wanted = text.trim().lowercase()
        val target = search(root) { label(it) == wanted }
            ?: search(root) { label(it).startsWith(wanted) }
            ?: search(root) { wanted.length >= 3 && label(it).contains(wanted) }
            ?: return false
        return press(target)
    }

    /** Swipes like a finger, which works in apps that don't report scrollable lists. */
    fun scroll(down: Boolean): Boolean {
        allowedRoot() ?: return false
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val top = metrics.heightPixels * 0.30f
        val bottom = metrics.heightPixels * 0.72f
        val path = Path().apply {
            if (down) {
                moveTo(x, bottom)
                lineTo(x, top)
            } else {
                moveTo(x, top)
                lineTo(x, bottom)
            }
        }
        return gesture(path, 320)
    }

    private fun allRoots(): List<AccessibilityNodeInfo> =
        runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(rootInActiveWindow) }

    /** Finds a Quick Settings tile by its name. */
    fun findTile(labels: List<String>): AccessibilityNodeInfo? {
        for (root in allRoots().filter { it.packageName?.toString() == SYSTEM_UI }) {
            val node = search(root) { node ->
                val text = label(node)
                labels.any { text == it || text.startsWith("$it,") || text.startsWith("$it ") || text.startsWith("$it.") }
            } ?: continue
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            return clickable ?: node
        }
        return null
    }

    private val onWord = Regex("\\b(on|enabled|turned on|active|connected)\\b")
    private val offWord = Regex("\\b(off|disabled|turned off|inactive|disconnected|unavailable)\\b")

    private fun stateFrom(text: CharSequence?): Boolean? {
        val t = text?.toString()?.lowercase() ?: return null
        return when {
            offWord.containsMatchIn(t) -> false
            onWord.containsMatchIn(t) -> true
            else -> null
        }
    }

    /** Reads whether a tile is on. Returns null if the tile doesn't say. */
    fun tileState(tile: AccessibilityNodeInfo): Boolean? {
        if (tile.isCheckable) return tile.isChecked
        stateFrom(tile.stateDescription)?.let { return it }
        for (index in 0 until tile.childCount) {
            val child = tile.getChild(index) ?: continue
            if (child.isCheckable) return child.isChecked
            stateFrom(child.stateDescription)?.let { return it }
            stateFrom(child.text)?.let { return it }
        }
        return stateFrom(tile.contentDescription)
    }

    fun pressTile(tile: AccessibilityNodeInfo) = press(tile)

    /** Moves Quick Settings to its next page of tiles. */
    fun nextQuickSettingsPage(): Boolean {
        for (root in allRoots().filter { it.packageName?.toString() == SYSTEM_UI }) {
            val pager = search(root) { it.isScrollable && it.className?.toString().orEmpty().contains("ViewPager") }
                ?: search(root) { it.isScrollable }
            if (pager != null && pager.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        }
        val metrics = resources.displayMetrics
        val y = metrics.heightPixels * 0.28f
        return gesture(Path().apply { moveTo(metrics.widthPixels * 0.85f, y); lineTo(metrics.widthPixels * 0.15f, y) }, 280)
    }

    /**
     * The one narrow exception to the Settings block. It's reachable only from the approved
     * Wi-Fi and Bluetooth tools, and it only touches the first switch on that page.
     */
    fun flipFirstSystemSwitch(on: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() !in SETTINGS_PACKAGES) return false
        val toggle = search(root) { it.isCheckable && it.className?.toString().orEmpty().contains("Switch") } ?: return false
        if (toggle.isChecked == on) return true
        return click(toggle)
    }

    /** Finds the switch beside a label, for pages where the first switch is a different setting. */
    fun flipSwitchNear(labels: List<String>, on: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() !in SETTINGS_PACKAGES) return false
        val label = search(root) { node ->
            val text = node.text?.toString()?.lowercase().orEmpty()
            labels.any { text.contains(it) }
        } ?: return false
        var parent = label.parent
        repeat(4) {
            val area = parent ?: return false
            val toggle = search(area) { it.isCheckable && it.className?.toString().orEmpty().contains("Switch") }
            if (toggle != null) return if (toggle.isChecked == on) true else click(toggle)
            parent = area.parent
        }
        return false
    }

    /** Works only inside WhatsApp, and only after the message was approved. */
    fun tapSendInWhatsApp(app: String): Boolean {
        val root = allRoots().firstOrNull { it.packageName?.toString() == app } ?: return false
        val target = search(root) {
            it.viewIdResourceName == "$app:id/send" || it.contentDescription?.toString().equals("Send", ignoreCase = true)
        } ?: return false
        return press(target)
    }

    companion object {
        @Volatile
        var instance: AutomationService? = null

        @Volatile
        var foregroundPackage: String? = null

        const val SYSTEM_UI = "com.android.systemui"

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings", "com.oplus.wirelesssettings", "com.coloros.wirelesssettings", "com.android.settings.intelligence",
            "com.android.systemui", "com.android.phone",
        )
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

        fun spokenSummary(context: android.content.Context): String {
            val component = android.content.ComponentName(context, NotificationsService::class.java)
            val granted = context.getSystemService(android.app.NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component)
            if (!granted) return "Turn on notification access for Pocket Assistant in the Settings tab first"
            val service = instance ?: run {
                requestRebind(component)
                return "Notification access is reconnecting. Ask me again in a few seconds"
            }
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
