package dev.aravind.assistant

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
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
import dev.aravind.assistant.policy.ScreenAgent
import dev.aravind.assistant.policy.ScreenItem
import dev.aravind.assistant.policy.ContactMatcher
import dev.aravind.assistant.policy.FieldInfo
import dev.aravind.assistant.policy.FieldMatcher
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
    private var agent: Agent? = null
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
            this.agent = agent
            val voiceLoop = VoiceLoop(this, created, scope) { agent.handle(it) }
            loop = voiceLoop
            AssistantRuntime.status.value = "Ready"
            // The AI model takes a while to load, so it loads now instead of on your first request.
            scope.launch { GemmaEngine.preload(this@AssistantService) }
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

    /** Piper speaks every reply. Android's own voice is the backup if Piper fails. */
    private suspend fun speak(text: String) {
        AssistantRuntime.say("Assistant: $text")
        if (output?.say(text) != true) systemSay(text, Locale("en", "IN"))
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
        // The microphone ignores audio while the assistant talks. "finally" makes sure it always listens again,
        // even if speaking is interrupted. Before, an interruption could leave the wake word deaf.
        loop?.speaking = true
        try {
            voice.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
            withTimeoutOrNull(30_000) { done.await() }
            delay(250)
        } finally {
            loop?.speaking = false
        }
    }

    /** Spoken codes are English digits, so they're always read in English. */
    private suspend fun hear(): Heard? {
        val active = loop ?: return null
        return when (val result = active.listenWithGoogleNow()) {
            is GoogleStt.Result.Text -> Heard(result.text, false, FloatArray(0))
            is GoogleStt.Result.Unavailable -> {
                AssistantRuntime.say(result.reason)
                null
            }
            GoogleStt.Result.Nothing -> null
        }
    }


    /** Google listens once, then shows what it heard, how long it took, and which recognizer was used. */
    fun speechTest(report: (String) -> Unit) {
        val active = loop ?: return report("Turn on the assistant first")
        scope.launch {
            report("Speak now, for example: set an alarm for 7:15 pm today")
            val start = System.currentTimeMillis()
            val result = active.listenWithGoogleNow()
            val ms = System.currentTimeMillis() - start
            val mode = GoogleStt.modeName(this@AssistantService)
            report(
                when (result) {
                    // The heard text is added after formatting, so a "%" in it can't break the message.
                    is GoogleStt.Result.Text -> "$mode heard, in %.1f s including your speech:\n".format(ms / 1000f) + result.text
                    is GoogleStt.Result.Unavailable -> result.reason
                    GoogleStt.Result.Nothing -> "Google didn't hear any words. Speak a little closer and try again"
                },
            )
        }
    }

    /** A command typed in the app instead of spoken. It runs through the same agent and safety checks. */
    fun runText(text: String) {
        val active = agent
        val command = text.trim().take(500)
        when {
            command.isEmpty() -> return
            active == null -> AssistantRuntime.say("Assistant: The voice system is still starting")
            !AssistantRuntime.isActive() -> AssistantRuntime.say("Assistant: Turn the assistant on first")
            AssistantRuntime.taskJob?.isActive == true -> AssistantRuntime.say("Assistant: I'm still working on the last task")
            else -> scope.launch {
                AssistantRuntime.status.value = "Working"
                try {
                    active.handle(Heard(command, false, FloatArray(0)))
                } finally {
                    if (AssistantRuntime.armed.value) AssistantRuntime.status.value = "Ready"
                }
            }
        }
    }

    /** The Stop button: cancels the running task straight away. Nothing more runs after it. */
    fun cancelTask() {
        val job = AssistantRuntime.taskJob ?: return
        job.cancel()
        Audit.log(this, "task_cancelled")
        AssistantRuntime.status.value = if (AssistantRuntime.armed.value) "Ready" else "Off"
        scope.launch { speak("Stopped") }
    }

    /** Saves the last wake word recording as a WAV file in Downloads/PocketAssistant. */
    fun saveLastRecording(report: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching { Recordings.saveLast(this@AssistantService) }
            report(result.fold({ "Saved to Downloads/PocketAssistant: ${it.joinToString(", ")}" }, { it.message ?: "Couldn't save it" }))
        }
    }

    fun playLastTest(): Boolean {
        val samples = AssistantRuntime.lastAudio ?: return false
        scope.launch(Dispatchers.Default) { Audio.play(samples, 16_000) }
        return true
    }

    /**
     * Records you saying the wake word five times and learns what the speech model hears for your voice,
     * like "juno" for "Junnu". Only words close to the name are learned, so it can't learn something like "general".
     */
    fun teachWakeWord(report: (String) -> Unit) {
        val active = loop ?: return report("Turn on the assistant first")
        val voice = engines ?: return report("The voice system is still starting")
        scope.launch {
            val context = this@AssistantService
            val name = Features.wakeName(context)
            val phrase = WakeName.phrase(name).lowercase()
            val heardWords = ArrayList<String>()
            var takes = 0
            var fast = 0
            var named = 0
            for (round in 1..5) {
                report("Say \"$phrase\" now ($round of 5)")
                speak("Say $phrase")
                val samples = active.capture(6_000) ?: continue
                takes++
                val text = withContext(Dispatchers.Default) { voice.nameText(samples) }
                if (withContext(Dispatchers.Default) { voice.spotterCatches(context, samples) }) fast++
                if (WakeName.nameHeard(text, name)) named++
                WakeName.learnable(text, name)?.let { heardWords += it.lowercase() }
            }
            // A word is kept if it was heard at least twice, or if it's already very close to the name.
            val learned = heardWords.groupingBy { it }.eachCount()
                .filter { (word, count) -> count >= 2 || WakeName.nameHeard(word, name) }
                .keys.take(WakeName.MAX_LEARNED)
            Features.setLearnedWakeWords(context, learned)
            loop?.reloadWakeName()
            Audit.log(context, "wake_taught:${learned.size}")
            val summary = "Heard you $takes of 5 times. Fast detector caught $fast, name check caught $named. " +
                if (learned.isEmpty()) "Nothing new to learn." else "Learned: ${learned.joinToString()}. Try the wake word now."
            report(summary)
            speak(if (learned.isEmpty()) "Done" else "Got it. Try $phrase now")
        }
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
            val session = CallSession(this@AssistantService, ::speak) {
                (active.listenWithGoogleNow() as? GoogleStt.Result.Text)?.text
            }
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

    /** Visible items on the app screen, in reading order. Password fields are never included. */
    fun screenItems(): List<Pair<ScreenItem, AccessibilityNodeInfo>>? {
        val root = allowedRoot() ?: return null
        val out = ArrayList<Pair<ScreenItem, AccessibilityNodeInfo>>()
        fun textUnder(node: AccessibilityNodeInfo, depth: Int = 0): String {
            if (depth > 4 || node.isPassword) return ""
            val own = label(node)
            if (own.isNotEmpty()) return own
            return (0 until node.childCount).mapNotNull { node.getChild(it) }.map { textUnder(it, depth + 1) }
                .filter { it.isNotEmpty() }.joinToString(" ").take(80)
        }
        fun walk(node: AccessibilityNodeInfo?, insideButton: Boolean) {
            if (node == null || out.size >= ScreenAgent.MAX_ITEMS * 2) return
            if (!node.isVisibleToUser || node.isPassword) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return
            val clickable = node.isClickable && node.isEnabled
            when {
                node.isEditable -> {
                    val hint = node.hintText?.toString() ?: node.text?.toString() ?: node.contentDescription?.toString() ?: "text box"
                    out += ScreenItem(hint.take(80), "text box") to node
                }
                clickable && !insideButton -> {
                    val text = textUnder(node)
                    if (text.isNotEmpty()) out += ScreenItem(text.take(80), "button") to node
                }
                !insideButton && label(node).isNotEmpty() -> out += ScreenItem(label(node).take(160), "text") to node
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), insideButton || (clickable && textUnder(node).isNotEmpty()))
        }
        walk(root, false)
        return out.distinctBy { it.first.label to it.first.kind }
    }

    fun pressNode(node: AccessibilityNodeInfo) = press(node)

    /** Taps the first item whose label matches one of [labels], trying exact labels before partial ones. */
    fun tapAny(labels: List<String>): Boolean {
        val root = allowedRoot() ?: return false
        for (wanted in labels) search(root) { label(it) == wanted }?.let { return press(it) }
        for (wanted in labels) search(root) { label(it).contains(wanted) }?.let { return press(it) }
        return false
    }

    /** Taps a spot given as a fraction of the screen size, for buttons with no label, like some camera shutters. */
    fun tapFraction(x: Float, y: Float): Boolean {
        if (allowedRoot() == null) return false
        val metrics = resources.displayMetrics
        return tapAt(metrics.widthPixels * x, metrics.heightPixels * y)
    }

    fun longPress(text: String): Boolean {
        val root = allowedRoot() ?: return false
        val wanted = text.trim().lowercase()
        val target = search(root) { label(it) == wanted }
            ?: search(root) { label(it).startsWith(wanted) }
            ?: search(root) { wanted.length >= 3 && label(it).contains(wanted) }
            ?: return false
        if (target.isLongClickable && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        return !bounds.isEmpty && gesture(Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }, 800)
    }

    /**
     * Text boxes on the screen, with what's known about each: its hint, id, linked label, and the label
     * written next to it, like "To" or "Subject" in Gmail. Password boxes are never included.
     */
    private fun textBoxes(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, FieldInfo>> {
        val boxes = ArrayList<AccessibilityNodeInfo>()
        val labels = ArrayList<Pair<String, Rect>>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || !node.isVisibleToUser) return
            if (node.isEditable) {
                if (!node.isPassword) boxes += node
            } else {
                val text = (node.text ?: node.contentDescription)?.toString()?.trim()
                if (!text.isNullOrEmpty() && text.length <= 30) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) labels += text to bounds
                }
            }
            for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
        }
        walk(root, 0)
        return boxes.map { box ->
            val r = Rect()
            box.getBoundsInScreen(r)
            val left = labels.filter { (_, l) -> l.right <= r.left + 16 && l.centerY() in r.top..r.bottom }.maxByOrNull { it.second.right }
            val above = labels.filter { (_, l) -> l.bottom <= r.top + 8 && r.top - l.bottom < 160 && l.left < r.right && l.right > r.left }
                .maxByOrNull { it.second.bottom }
            box to FieldInfo(
                hint = box.hintText?.toString().orEmpty(),
                description = box.contentDescription?.toString().orEmpty(),
                viewId = box.viewIdResourceName.orEmpty(),
                labeledBy = box.labeledBy?.text?.toString().orEmpty(),
                nearby = (left ?: above)?.first.orEmpty(),
                multiLine = box.isMultiLine,
                focused = box.isFocused,
                height = r.height(),
            )
        }
    }

    /**
     * Types into the text box you named, like "subject" or "to", in any app. With no name, it uses the focused
     * or first box. Returns the name of the box it filled, or null if it couldn't.
     */
    fun enterText(field: String?, text: String): String? {
        val root = allowedRoot() ?: return null
        val boxes = textBoxes(root)
        val index = FieldMatcher.pick(field, boxes.map { it.second }) ?: return null
        val (node, info) = boxes[index]
        if (!typeInto(node, text) && !paste(node, text)) return null
        return listOf(info.hint, info.nearby, info.labeledBy, info.description).firstOrNull { it.isNotBlank() }?.take(30)
            ?: field ?: "the text box"
    }

    /** Some apps ignore typed text from accessibility, but accept a paste. */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun showRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        press(node)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

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

    /** A notification as it was read out, so "open the second one" later means the same one. */
    data class Heard(val key: String, val packageName: String, val app: String, val title: String)

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** The newest five notifications, newest first. */
    private fun recent(): List<StatusBarNotification> {
        val items: Array<StatusBarNotification> = activeNotifications ?: return emptyList()
        return items.filter { !it.isOngoing && it.packageName != packageName }.sortedByDescending { it.postTime }.filter {
            val extras = it.notification.extras
            !extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() || !extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
        }.take(5)
    }

    private fun lines(): List<String> {
        val items = recent()
        lastRead = items.map { item ->
            Heard(item.key, item.packageName, appLabel(item.packageName), item.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
        }
        return items.mapIndexed { index, item ->
            val extras = item.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val number = if (items.size > 1) "${index + 1}. " else ""
            number + SecretRedactor.redact("${appLabel(item.packageName)}, $title: $body")
        }
    }

    /** Opens a notification like tapping it. Picks by number from the last reading, or by app or sender name. */
    private fun open(number: Int?, from: String?): String {
        val heard = lastRead.ifEmpty {
            recent().map { Heard(it.key, it.packageName, appLabel(it.packageName), it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()) }
        }
        if (heard.isEmpty()) return "You have no notifications to open"
        val wanted = from?.trim()?.lowercase()
        val pick = when {
            !wanted.isNullOrEmpty() -> heard.firstOrNull { it.app.lowercase().contains(wanted) || it.title.lowercase().contains(wanted) }
                ?: ContactMatcher.top(wanted, heard.map { it.title } + heard.map { it.app }).firstOrNull()
                    ?.let { name -> heard.firstOrNull { it.title == name || it.app == name } }
            number != null -> heard.getOrNull(number - 1)
            else -> heard.first()
        } ?: return if (wanted != null) "I couldn't find a notification from $from" else "There's no notification number $number"
        val live = activeNotifications?.firstOrNull { it.key == pick.key }
        val opened = live?.notification?.contentIntent?.let { intent ->
            runCatching { intent.send() }.isSuccess
        } == true
        if (opened) {
            if (live != null && live.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) runCatching { cancelNotification(live.key) }
            return "Opened the ${pick.app} notification${if (pick.title.isBlank()) "" else " from ${pick.title}"}"
        }
        // The notification is gone or can't be opened, so open its app instead.
        val launch = packageManager.getLaunchIntentForPackage(pick.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "That notification can't be opened anymore"
        startActivity(launch)
        return "That notification is gone, so I opened ${pick.app}"
    }

    companion object {
        @Volatile
        var instance: NotificationsService? = null

        @Volatile
        var lastRead: List<Heard> = emptyList()

        /** App and sender names only, never the message text, so earlier notifications can be referred to safely. */
        fun safeSummary(): String {
            val heard = lastRead
            if (heard.isEmpty()) return "Read your notifications: there were none"
            return "Read ${heard.size} notification(s): " + heard.mapIndexed { i, h ->
                "${i + 1}. ${h.app}${if (h.title.isBlank()) "" else " (${SecretRedactor.redact(h.title).take(30)})"}"
            }.joinToString(", ")
        }

        fun openNotification(context: android.content.Context, number: Int?, from: String?): String {
            if (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return "Unlock your phone first, then ask again"
            val service = instance ?: return "Turn on notification access for Pocket Assistant in the Settings tab first"
            return service.open(number, from)
        }

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

/** A Quick Settings button that starts listening at once, for when the wake word doesn't catch you. */
class TalkTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (AssistantRuntime.armed.value) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.label = "Talk"
        tile.updateTile()
    }

    override fun onClick() {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(Intent(this, AssistLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
