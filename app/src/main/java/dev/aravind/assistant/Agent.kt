package dev.aravind.assistant

import android.content.Context
import android.content.Intent
import dev.aravind.assistant.policy.AgentMemory
import dev.aravind.assistant.policy.Category
import dev.aravind.assistant.policy.CommandRouter
import dev.aravind.assistant.policy.ConfirmationAuthority
import dev.aravind.assistant.policy.Decision
import dev.aravind.assistant.policy.FuzzyCommand
import dev.aravind.assistant.policy.ModelOutput
import dev.aravind.assistant.policy.ModelOutputParser
import dev.aravind.assistant.policy.PolicyContext
import dev.aravind.assistant.policy.PolicyEngine
import dev.aravind.assistant.policy.ReplyGuard
import dev.aravind.assistant.policy.Source
import dev.aravind.assistant.policy.TaskLimits
import dev.aravind.assistant.policy.Tool
import dev.aravind.assistant.policy.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class Agent(
    private val context: Context,
    private val speak: suspend (String) -> Unit,
    private val listen: suspend () -> Heard?,
) {
    private val executor = ToolExecutor(context)
    private var greeted = false

    suspend fun handle(heard: Heard) {
        val utterance = heard.text
        AssistantRuntime.say("You: $utterance")
        if (CommandRouter.isStop(utterance)) {
            AssistantRuntime.stopAll(context, "voice")
            return
        }
        // Gemma takes several seconds per step on a phone, so a thinking task gets up to three minutes.
        val limits = TaskLimits(System::currentTimeMillis, maxSteps = 10, maxMillis = 180_000)
        greeted = false
        var tainted = false

        // Splitting stops at the start of a message, so message text is never cut up.
        val steps = CommandRouter.steps(utterance)
        val contacts = executor.contactNames()
        val routed = steps.map { step ->
            CommandRouter.route(step, contacts) ?: FuzzyCommand.match(step)?.let { guess ->
                CommandRouter.route(guess, contacts)?.also { AssistantRuntime.say("Understood as: $guess") }
            }
        }

        // Known commands run straight away. Anything else goes to Gemma, which works it out step by step.
        if (routed.all { it != null }) {
            for (call in routed.filterNotNull()) {
                if (!AssistantRuntime.isActive()) return
                if (!limits.tryStep()) {
                    speak("That task took too long, so I stopped")
                    return
                }
                run(call, utterance, heard.verified, tainted) ?: return
                // After reading notifications or the screen, untrusted text has been seen.
                if (call.tool in READS_UNTRUSTED) tainted = true
                if (routed.size > 1) delay(700)
            }
            return
        }
        think(utterance, heard, limits)
    }

    /**
     * Gemma decides what to do: it picks one action, sees its result, and picks the next, until the request is
     * done. Every action still goes through the same safety checks and approvals as a spoken command.
     */
    private suspend fun think(request: String, heard: Heard, limits: TaskLimits) {
        val memory = AgentMemory(request)
        var tainted = false
        var lastResult = ""
        while (AssistantRuntime.isActive()) {
            if (!memory.canContinue() || !limits.tryStep()) {
                speak("I did ${memory.stepsDone} steps but couldn't finish. ${lastResult.take(120)}".trim())
                return
            }
            AssistantRuntime.status.value = "Thinking"
            val raw = try {
                GemmaEngine.agentStep(context, memory.prompt())
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                speak(e.message ?: "The AI model isn't ready")
                return
            } catch (e: Exception) {
                Audit.log(context, "model_error")
                speak("The AI model couldn't run. I can still do simple commands")
                return
            }
            val call = when (val output = ModelOutputParser.parse(raw)) {
                is ModelOutput.Reply -> {
                    // Before any step, a reply that claims an action is false, because nothing was done.
                    if (memory.stepsDone == 0 && ReplyGuard.isFalseActionClaim(request, output.text)) {
                        Audit.log(context, "false_claim_blocked")
                        speak("I didn't understand that as a command, so nothing was done. Please try saying it another way")
                        return
                    }
                    GemmaEngine.remember(request, output.text)
                    speak(output.text)
                    return
                }
                is ModelOutput.Invalid -> {
                    Audit.log(context, "model_output_rejected")
                    speak(if (memory.stepsDone == 0) "I couldn't work out a safe action for that" else lastResult.ifBlank { "Done" })
                    return
                }
                is ModelOutput.Call -> output.call
                is ModelOutput.Plan -> output.calls.first()
            }
            if (!memory.allow(call)) {
                speak(lastResult.ifBlank { "I was going in circles, so I stopped" })
                return
            }
            AssistantRuntime.say("Step ${memory.stepsDone + 1}: ${AgentMemory.describe(call)}")
            val result = run(call, request, heard.verified, tainted, speakResult = false) ?: return
            if (call.tool in READS_UNTRUSTED) tainted = true
            memory.record(call, result)
            lastResult = result
            delay(400)
        }
    }

    /** Runs one action after the safety checks. Returns its result, or null if it was stopped or failed. */
    private suspend fun run(call: ToolCall, utterance: String, verified: Boolean, tainted: Boolean, speakResult: Boolean = true): String? {
        // The wake screen must be gone before reading or touching the app underneath it.
        if (call.tool !in KEEPS_WAKE_SCREEN && WakeUi.visible) {
            WakeUi.close()
            delay(600)
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
        val READS_UNTRUSTED = setOf(Tool.READ_NOTIFICATIONS, Tool.READ_SCREEN, Tool.SCREEN_TASK, Tool.WEB_ANSWER)
        val LONG_ANSWERS = setOf(Tool.READ_NOTIFICATIONS, Tool.READ_SCREEN, Tool.WEB_ANSWER)
        val NO = Regex("\\b(no|nope|cancel|stop|don't|dont|wait|vaddu|nahi|mat)\\b")
        val YES = Regex("\\b(yes|yeah|yep|yup|send|send it|confirm|ok|okay|sure|do it|go ahead|haan|ha|avunu|sare|sari)\\b")
    }

    /** Reads the message back and waits for a spoken yes. If it doesn't hear one, it shows a Send button. */
    private suspend fun quickApprove(call: ToolCall, readback: String, foreground: String?): Boolean {
        val authority: ConfirmationAuthority = AssistantRuntime.authority ?: return false
        speak("$readback. Say yes to send, or no to cancel")
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
