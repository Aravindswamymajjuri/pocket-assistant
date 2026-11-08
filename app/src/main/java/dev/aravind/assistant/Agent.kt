package dev.aravind.assistant

import android.content.Context
import android.content.Intent
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

    suspend fun handle(heard: Heard) {
        val utterance = heard.text
        AssistantRuntime.say("You: $utterance")
        if (CommandRouter.isStop(utterance)) {
            AssistantRuntime.stopAll(context, "voice")
            return
        }
        val limits = TaskLimits(System::currentTimeMillis)
        var tainted = false

        // Splitting stops at the start of a message, so message text is never cut up.
        val steps = CommandRouter.steps(utterance)
        val contacts = executor.contactNames()

        for (step in steps) {
            if (!AssistantRuntime.isActive()) return
            val routed = CommandRouter.route(step, contacts) ?: FuzzyCommand.match(step)?.let { guess ->
                AssistantRuntime.say("Understood as: $guess")
                CommandRouter.route(guess, contacts)
            }
            val calls: List<ToolCall> = if (routed != null) {
                listOf(routed)
            } else {
                when (val output = askGemma(step)) {
                    null -> return
                    is ModelOutput.Reply -> {
                        if (ReplyGuard.isFalseActionClaim(step, output.text)) {
                            Audit.log(context, "false_claim_blocked")
                            speak("I didn't understand that as a command, so nothing was done. Please try saying it another way")
                            return
                        }
                        speak(output.text)
                        continue
                    }
                    is ModelOutput.Invalid -> {
                        Audit.log(context, "model_output_rejected")
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
                if (!run(call, utterance, heard.verified, tainted)) return
                // After reading notifications or the screen, untrusted text has been seen.
                if (call.tool in READS_UNTRUSTED) tainted = true
                if (steps.size > 1 || calls.size > 1) delay(700)
            }
        }
    }

    private suspend fun askGemma(text: String): ModelOutput? {
        val raw = try {
            GemmaEngine.ask(context, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            speak(e.message ?: "The AI model isn't ready")
            return null
        } catch (e: Exception) {
            Audit.log(context, "model_error")
            speak("The AI model couldn't run. I can still do simple commands")
            return null
        }
        return ModelOutputParser.parse(raw)
    }

    private suspend fun run(call: ToolCall, utterance: String, verified: Boolean, tainted: Boolean): Boolean {
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
                return false
            }
            is Decision.Allow -> Unit
            is Decision.Confirm -> if (!quickApprove(call, decision.readback, foreground)) return false
            is Decision.Biometric -> if (!screenApprove(call, decision.readback, foreground, biometric = true)) return false
        }
        if (call.tool.hasRecipient) AssistantRuntime.rateLimiter.record()
        val result = try {
            executor.execute(call, utterance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Audit.log(context, "failed:${call.tool.id}")
            speak("That action failed")
            return false
        }
        Gates.markUsed(context, call.tool.category)
        Audit.log(context, "done:${call.tool.id}")
        speak(result)
        return true
    }

    private companion object {
        val KEEPS_WAKE_SCREEN = setOf(
            Tool.FLASHLIGHT, Tool.VOLUME, Tool.BRIGHTNESS, Tool.DO_NOT_DISTURB, Tool.SET_ALARM, Tool.SET_TIMER, Tool.READ_NOTIFICATIONS,
            Tool.RINGER_MODE,
        )
        val READS_UNTRUSTED = setOf(Tool.READ_NOTIFICATIONS, Tool.READ_SCREEN, Tool.SCREEN_TASK)
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
