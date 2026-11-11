package dev.aravind.assistant

import android.content.Context
import android.content.Intent
import dev.aravind.assistant.policy.AgentMemory
import dev.aravind.assistant.policy.Category
import dev.aravind.assistant.policy.CommandRouter
import dev.aravind.assistant.policy.ConfirmationAuthority
import dev.aravind.assistant.policy.Decision
import dev.aravind.assistant.policy.FormFill
import dev.aravind.assistant.policy.FuzzyCommand
import dev.aravind.assistant.policy.ModelOutput
import dev.aravind.assistant.policy.ModelOutputParser
import dev.aravind.assistant.policy.PolicyContext
import dev.aravind.assistant.policy.PolicyEngine
import dev.aravind.assistant.policy.ReplyGuard
import dev.aravind.assistant.policy.SecretRedactor
import dev.aravind.assistant.policy.Source
import dev.aravind.assistant.policy.TaskLimits
import dev.aravind.assistant.policy.Tool
import dev.aravind.assistant.policy.ToolCall
import dev.aravind.assistant.policy.ToolSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Agent(
    private val context: Context,
    private val speak: suspend (String) -> Unit,
    private val listen: suspend () -> Heard?,
) {
    private val executor = ToolExecutor(context)
    private var greeted = false

    // What the last request did, so a follow-up like "open it" knows what "it" is.
    private var previousTool: Tool? = null
    private var previousAt = 0L
    private var summary: String? = null

    suspend fun handle(heard: Heard) {
        AssistantRuntime.taskJob = currentCoroutineContext()[Job]
        var outcome = "Done"
        summary = null
        try {
            outcome = work(heard)
        } catch (e: CancellationException) {
            outcome = "Stopped"
            AssistantRuntime.note("Stopped by you")
            throw e
        } finally {
            AssistantRuntime.taskJob = null
            AssistantRuntime.finishTask(heard.text, outcome)
            // Every request is remembered for the next few, including ones that ran without the AI,
            // so "open it" or "call him" makes sense. Text from notifications and messages is never kept here.
            if (outcome != "Stopped") GemmaEngine.remember(heard.text, summary ?: outcome)
        }
    }

    /** What the chat history keeps about one action. Untrusted text, like message contents, is left out. */
    private fun historyNote(call: ToolCall, result: String): String = when {
        call.tool == Tool.READ_NOTIFICATIONS -> NotificationsService.safeSummary()
        call.tool in READS_UNTRUSTED -> "Did: ${doing(call)}"
        else -> result
    }

    /** Speaks [text] and remembers it as the task's outcome. */
    private suspend fun finish(text: String): String {
        speak(text)
        return text
    }

    private suspend fun work(heard: Heard): String {
        val utterance = heard.text
        AssistantRuntime.say("You: $utterance")
        if (CommandRouter.isStop(utterance)) {
            AssistantRuntime.stopAll(context, "voice")
            return "Stopped"
        }
        // Gemma takes several seconds per step on a phone, so a thinking task gets up to three minutes.
        val limits = TaskLimits(System::currentTimeMillis, maxSteps = 10, maxMillis = 180_000)
        AssistantRuntime.agentSteps.value = emptyList()
        greeted = false
        var tainted = false

        // "Fill to as Ravi, subject as Leave and body as ..." fills each box in the open app, without thinking.
        FormFill.parse(utterance)?.let { fills ->
            var last = "Done"
            for (call in fills) {
                if (!AssistantRuntime.isActive()) return "Stopped"
                AssistantRuntime.note("Doing: ${doing(call)}")
                last = run(call, utterance, heard.verified, tainted, speakResult = false) ?: return "Not done"
                AssistantRuntime.note("Found: $last")
                if (last.startsWith("I couldn't") || last.startsWith("Turn on")) return finish(last)
                delay(250)
            }
            return finish(if (fills.size == 1) last else "Filled ${fills.size} boxes: ${fills.joinToString(", ") { it.args["field"].toString() }}")
        }

        // Splitting stops at the start of a message, so message text is never cut up.
        val steps = CommandRouter.steps(utterance)
        val contacts = executor.contactNames()
        // A follow-up like "open it" refers to the request just before, if it was in the last few minutes.
        var before = previousTool.takeIf { System.currentTimeMillis() - previousAt < FOLLOW_UP_MILLIS }
        val routed = CommandRouter.inContext(
            steps,
            steps.map { step ->
                (
                    CommandRouter.followUp(step, before) ?: CommandRouter.route(step, contacts) ?: FuzzyCommand.match(step)?.let { guess ->
                        CommandRouter.route(guess, contacts)?.also { AssistantRuntime.say("Understood as: $guess") }
                    }
                    ).also { call -> if (call != null) before = call.tool }
            },
        )

        // Known commands run straight away. Anything else goes to Gemma, which works it out step by step.
        if (routed.all { it != null }) {
            // "Play X songs" is tried on the phone's own music first. If none match, the AI works out another way,
            // like YouTube or a music app.
            val only = routed.singleOrNull()
            if (only?.tool == Tool.PLAY_MUSIC && only.args["query"] != null) {
                val result = run(only, utterance, heard.verified, tainted, speakResult = false) ?: return "Not done"
                if (!result.startsWith("No songs")) return finish(result)
                AssistantRuntime.note("Found: $result")
                return think(utterance, heard, limits)
            }
            var last = "Done"
            for (call in routed.filterNotNull()) {
                if (!AssistantRuntime.isActive()) return "Stopped"
                if (!limits.tryStep()) return finish("That task took too long, so I stopped")
                last = run(call, utterance, heard.verified, tainted) ?: return "Not done"
                // After reading notifications or the screen, untrusted text has been seen.
                if (call.tool in READS_UNTRUSTED) tainted = true
                if (routed.size > 1) delay(if (call.tool == Tool.OPEN_APP || call.tool == Tool.PLACE_CALL) 700 else 300)
            }
            return last
        }
        return think(utterance, heard, limits)
    }

    /**
     * Gemma decides what to do: it picks one action, sees its result, and picks the next, until the request is
     * done. Every action still goes through the same safety checks and approvals as a spoken command.
     */
    private suspend fun think(request: String, heard: Heard, limits: TaskLimits): String {
        val chat = GemmaEngine.startTask()
        try {
            return thinkWith(chat, request, heard, limits)
        } finally {
            withContext(NonCancellable) { chat.close() }
        }
    }

    private suspend fun thinkWith(chat: GemmaEngine.AgentChat, request: String, heard: Heard, limits: TaskLimits): String {
        val memory = AgentMemory(request)
        val offered = ToolSelector.relevant(request)
        var tainted = false
        var lastResult = ""
        while (AssistantRuntime.isActive()) {
            if (!memory.canContinue() || !limits.tryStep()) {
                return finish("I did ${memory.stepsDone} steps but couldn't finish. ${lastResult.take(120)}".trim())
            }
            AssistantRuntime.status.value = "Thinking"
            val thinkStart = System.currentTimeMillis()
            val raw = try {
                chat.step(context, memory, offered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                return finish(e.message ?: "The AI model isn't ready")
            } catch (e: Exception) {
                Audit.log(context, "model_error")
                return finish("The AI model couldn't run. I can still do simple commands")
            }
            val (thought, output) = ModelOutputParser.parseWithThought(raw)
            val seconds = (System.currentTimeMillis() - thinkStart) / 100 / 10.0
            AssistantRuntime.note("Thinking: ${thought ?: "decided"} (${seconds}s)")
            val calls = when (output) {
                is ModelOutput.Reply -> {
                    // Before any step, a reply that claims an action is false, because nothing was done.
                    if (memory.stepsDone == 0 && ReplyGuard.isFalseActionClaim(request, output.text)) {
                        Audit.log(context, "false_claim_blocked")
                        return finish("I didn't understand that as a command, so nothing was done. Please try saying it another way")
                    }
                    if (!tainted) summary = output.text
                    AssistantRuntime.note("Answer: ${output.text}")
                    return finish(output.text)
                }
                is ModelOutput.Ask -> {
                    // The request was unclear, so it asks you and carries on with your answer.
                    AssistantRuntime.note("Asking: ${output.question}")
                    speak(output.question)
                    val answer = listen()?.text?.trim().orEmpty()
                    if (answer.isEmpty()) return finish("I didn't hear an answer, so I stopped")
                    if (CommandRouter.isStop(answer)) return finish("OK, stopped")
                    AssistantRuntime.say("You: $answer")
                    AssistantRuntime.note("You said: $answer")
                    memory.recordAnswer(output.question, answer)
                    continue
                }
                is ModelOutput.Invalid -> {
                    Audit.log(context, "model_output_rejected")
                    return finish(if (memory.stepsDone == 0) "I couldn't work out a safe action for that" else lastResult.ifBlank { "Done" })
                }
                is ModelOutput.Call -> listOf(output.call)
                is ModelOutput.Plan -> output.calls
            }
            // A plan runs all its steps without thinking again in between, which saves a model call per step.
            // It only goes back to thinking if a step fails or reads text the AI has to look at.
            val results = ArrayList<String>()
            var needsThought = false
            for ((index, call) in calls.withIndex()) {
                if (!AssistantRuntime.isActive()) return "Stopped"
                if (index > 0 && !limits.tryStep()) return finish("That task took too long, so I stopped")
                if (!memory.allow(call)) return finish(lastResult.ifBlank { "I was going in circles, so I stopped" })
                AssistantRuntime.note("Doing: ${doing(call)}")
                val started = System.currentTimeMillis()
                val result = run(call, request, heard.verified, tainted, speakResult = false)
                if (result == null) {
                    AssistantRuntime.note("Stopped")
                    return "Not done"
                }
                AssistantRuntime.note("Found: ${SecretRedactor.redact(result).take(160)} (${(System.currentTimeMillis() - started) / 100 / 10.0}s)")
                if (call.tool in READS_UNTRUSTED) tainted = true
                memory.record(call, result)
                lastResult = result
                results += result
                if (FAILED.containsMatchIn(result) || call.tool in NEEDS_A_LOOK) {
                    needsThought = true
                    break
                }
                if (index < calls.lastIndex) delay(500)
            }
            // When the AI said this was the last step, or a whole plan went through, the results are the answer.
            val done = (output is ModelOutput.Call && output.final) || (output is ModelOutput.Plan && calls.size > 1)
            if (done && !needsThought) {
                val answer = results.joinToString(". ") { it.trim().trimEnd('.') }.take(300)
                AssistantRuntime.note("Answer: $answer")
                return finish(answer)
            }
            delay(300)
        }
        return "Stopped"
    }

    /** With "Learn from my usage" on, counts the apps and contacts you use, so the AI can suggest them first. */
    private fun learn(call: ToolCall) {
        when (call.tool) {
            Tool.OPEN_APP -> MemoryStore.recordUsage(context, "app", call.text("name"))
            Tool.PLACE_CALL, Tool.SEND_SMS, Tool.SEND_WHATSAPP -> MemoryStore.recordUsage(context, "contact", call.text("to"))
            Tool.OPEN_CHAT -> MemoryStore.recordUsage(context, "contact", call.text("contact"))
            else -> Unit
        }
    }

    private fun appName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return ""
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    /** A plain description of a step, for the screen. */
    private fun doing(call: ToolCall): String {
        val contact = (call.args["contact"] ?: call.args["name"] ?: call.args["to"])?.toString()
        return when (call.tool) {
            Tool.PHONE_STATUS -> "Checking the phone's status"
            Tool.FIND_CONTACT -> "Looking up ${call.text("name")} in contacts"
            Tool.RECENT_CALLS -> if (contact == null) "Checking recent calls" else "Checking calls with $contact"
            Tool.RECENT_MESSAGES -> if (contact == null) "Reading recent messages" else "Reading messages from $contact"
            Tool.CALENDAR_EVENTS -> "Checking your calendar"
            Tool.LIST_APPS -> "Listing installed apps"
            Tool.READ_NOTIFICATIONS -> "Reading notifications"
            Tool.READ_SCREEN -> "Reading the screen"
            Tool.WEB_ANSWER -> "Looking it up on Google"
            Tool.SCREEN_TASK -> "Working in the app: ${call.text("goal")}"
            Tool.SEARCH_MUSIC -> "Searching your music for ${call.text("query")}"
            Tool.PLAY_MUSIC -> (call.args["query"] as? String)?.let { "Playing $it from your music" } ?: "Playing your music"
            Tool.MEDIA_CONTROL -> "Music: ${call.text("action")}"
            Tool.SEARCH_FILES -> "Searching files for ${call.text("query")}"
            Tool.OPEN_FILE -> "Opening file ${call.text("file")}"
            Tool.READ_DOCUMENT -> "Reading file ${call.text("file")}"
            Tool.SHARE_FILE -> "Sharing file ${call.text("file")}"
            Tool.RENAME_FILE -> "Renaming file ${call.text("file")}"
            Tool.MOVE_FILE -> "Moving file ${call.text("file")} to ${call.text("folder")}"
            Tool.FIND_MEDIA -> "Looking for ${call.text("kind")}s"
            Tool.OPEN_CAMERA -> "Opening the camera"
            Tool.TAKE_PHOTO -> "Taking a photo"
            Tool.RECORD_VIDEO -> "Video: ${call.text("action")} recording"
            Tool.CREATE_EVENT -> "Adding \"${call.text("title")}\" to your calendar"
            Tool.CREATE_REMINDER -> "Setting a reminder: ${call.text("text")}"
            Tool.DELETE_EVENT -> "Deleting calendar event ${call.long("id")}"
            Tool.OPEN_WEBSITE -> "Opening ${call.text("url")}"
            Tool.OPEN_NOTIFICATION -> (call.args["from"] as? String)?.let { "Opening the notification from $it" }
                ?: (call.args["number"] as? Long)?.let { "Opening notification $it" } ?: "Opening the notification"
            Tool.SPEAKERPHONE -> if (call.bool("on")) "Turning on the speaker" else "Turning off the speaker"
            Tool.ENTER_TEXT -> (call.args["field"] as? String)?.let { "Filling the $it box" } ?: "Typing into the screen"
            Tool.REMEMBER -> "Saving to memory"
            Tool.FORGET -> "Removing from memory"
            else -> AgentMemory.describe(call)
        }
    }

    /** Runs one action after the safety checks. Returns its result, or null if it was stopped or failed. */
    private suspend fun run(call: ToolCall, utterance: String, verified: Boolean, tainted: Boolean, speakResult: Boolean = true): String? {
        // The wake screen must be gone before reading or touching the app underneath it.
        if (call.tool !in KEEPS_WAKE_SCREEN && WakeUi.visible) {
            WakeUi.close()
            delay(350)
        }
        val foreground = AutomationService.instance?.targetPackage() ?: AutomationService.foregroundPackage
        val policy = PolicyContext(
            armed = AssistantRuntime.isActive(),
            source = Source.LIVE_USER,
            inPhoneCall = AssistantRuntime.inPhoneCall(context),
            tainted = tainted,
            voiceVerified = verified,
            gateEnabled = { category: Category -> Gates.enabled(context, category) },
            originalUtterance = utterance,
            foregroundPackage = foreground,
            ownPackage = context.packageName,
            outboundAllowed = AssistantRuntime.rateLimiter.allowed(),
        )
        when (val decision = PolicyEngine.evaluate(call, policy)) {
            is Decision.Deny -> {
                Audit.log(context, "denied:${call.tool.id}")
                speak(decision.reason)
                return null
            }
            is Decision.Allow -> Unit
            is Decision.Confirm -> if (!quickApprove(call, decision.readback, foreground)) return null
            is Decision.Biometric -> if (!screenApprove(call, decision.readback, foreground, biometric = true)) return null
        }
        if (call.tool.hasRecipient) AssistantRuntime.rateLimiter.record()
        val result = try {
            executor.execute(call, utterance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Audit.log(context, "failed:${call.tool.id}")
            speak("That action failed")
            return null
        }
        Gates.markUsed(context, call.tool.category)
        Audit.log(context, "done:${call.tool.id}")
        learn(call)
        previousTool = call.tool
        previousAt = System.currentTimeMillis()
        summary = listOfNotNull(summary?.takeIf { it.length < 200 }, historyNote(call, result).take(250)).joinToString(". ")
        AssistantRuntime.currentApp.value = appName(AutomationService.instance?.targetPackage())
        // Uses your name for the first reply of a request, like "OK Aravind, flashlight on".
        if (speakResult) {
            val name = Features.userName(context)
            speak(if (name.isNotBlank() && !greeted && call.tool !in LONG_ANSWERS) "OK $name, $result" else result)
            greeted = true
        }
        return result
    }

    private companion object {
        val KEEPS_WAKE_SCREEN = setOf(
            Tool.FLASHLIGHT, Tool.VOLUME, Tool.BRIGHTNESS, Tool.DO_NOT_DISTURB, Tool.SET_ALARM, Tool.SET_TIMER, Tool.READ_NOTIFICATIONS,
            Tool.RINGER_MODE,
        )
        // Text written by other people or websites. After reading it, sending or calling needs your fingerprint.
        val READS_UNTRUSTED = setOf(
            Tool.READ_NOTIFICATIONS, Tool.READ_SCREEN, Tool.SCREEN_TASK, Tool.WEB_ANSWER, Tool.RECENT_MESSAGES, Tool.CALENDAR_EVENTS,
        )
        const val FOLLOW_UP_MILLIS = 5 * 60_000L

        // After these, the AI looks at the result before going on, because the next step depends on what it says.
        val NEEDS_A_LOOK = READS_UNTRUSTED + Tool.READ_DOCUMENT
        // Results that mean a step didn't work, so the AI thinks again instead of reporting it as done.
        val FAILED = Regex(
            "(?i)(?:couldn't|could not|can't|cannot|failed|not found|no call|wasn't|didn't|isn't (?:installed|allowed|on)|" +
                "^no |allow .+ in settings|not allowed|turn on .+ first)",
        )
        val LONG_ANSWERS = setOf(
            Tool.READ_NOTIFICATIONS, Tool.READ_SCREEN, Tool.WEB_ANSWER, Tool.PHONE_STATUS, Tool.RECENT_CALLS, Tool.RECENT_MESSAGES,
            Tool.CALENDAR_EVENTS, Tool.LIST_APPS, Tool.FIND_CONTACT,
        )
        val NO = Regex("\\b(no|nope|cancel|stop|don't|dont|wait|vaddu|nahi|mat)\\b")
        val YES = Regex("\\b(yes|yeah|yep|yup|send|send it|confirm|ok|okay|sure|do it|go ahead|haan|ha|avunu|sare|sari)\\b")
    }

    /** Reads the message back and waits for a spoken yes. If it doesn't hear one, it shows a Send button. */
    private suspend fun quickApprove(call: ToolCall, readback: String, foreground: String?): Boolean {
        val authority: ConfirmationAuthority = AssistantRuntime.authority ?: return false
        val verb = if (call.tool.hasRecipient) "send" else "go ahead"
        speak("$readback. Say yes to $verb, or no to cancel")
        val answer = listen()?.text?.lowercase().orEmpty()
        when {
            NO.containsMatchIn(answer) -> {
                speak("Cancelled")
                return false
            }
            YES.containsMatchIn(answer) -> Unit
            else -> return screenApprove(call, readback, foreground, biometric = false)
        }
        Audit.log(context, "voice_confirmed:${call.tool.id}")
        val token = authority.issue(call, foreground)
        return authority.verify(token, call, currentApp(call, foreground))
    }

    /** Shows the app-built readback, then verifies a one-time token for this exact action. */
    private suspend fun screenApprove(call: ToolCall, readback: String, foreground: String?, biometric: Boolean): Boolean {
        val authority: ConfirmationAuthority = AssistantRuntime.authority ?: return false
        val token = authority.issue(call, foreground)
        val pending = CompletableDeferred<Boolean>()
        AssistantRuntime.pendingApproval = pending
        speak("Please confirm on the screen")
        context.startActivity(
            Intent(context, ConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ConfirmActivity.EXTRA_READBACK, readback)
                .putExtra(ConfirmActivity.EXTRA_BIOMETRIC, biometric),
        )
        val approved = withTimeoutOrNull(30_000) { pending.await() } == true
        AssistantRuntime.pendingApproval = null
        if (!approved) {
            Audit.log(context, "not_approved:${call.tool.id}")
            speak("Cancelled")
            return false
        }
        val valid = authority.verify(token, call, currentApp(call, foreground))
        if (!valid) speak("That approval is no longer valid, so I cancelled it")
        return valid
    }

    /** Screen actions must land on the same app that was open when you were asked. */
    private suspend fun currentApp(call: ToolCall, foreground: String?): String? {
        if (call.tool.category != Category.SCREEN) return foreground
        delay(500)
        return AutomationService.foregroundPackage
    }
}
