package dev.aravind.assistant

import android.content.Context
import android.content.Intent
import dev.aravind.assistant.policy.Category
import dev.aravind.assistant.policy.CommandRouter
import dev.aravind.assistant.policy.ConfirmationAuthority
import dev.aravind.assistant.policy.Decision
import dev.aravind.assistant.policy.ModelOutput
import dev.aravind.assistant.policy.ModelOutputParser
import dev.aravind.assistant.policy.PolicyContext
import dev.aravind.assistant.policy.PolicyEngine
import dev.aravind.assistant.policy.Source
import dev.aravind.assistant.policy.TaskLimits
import dev.aravind.assistant.policy.Tool
import dev.aravind.assistant.policy.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class Agent(private val context: Context, private val speak: (String) -> Unit) {
    private val executor = ToolExecutor(context)

    suspend fun handle(utterance: String) {
        if (CommandRouter.isStop(utterance)) {
            AssistantRuntime.stopAll(context, "voice")
            return
        }
        val limits = TaskLimits(System::currentTimeMillis)
        var tainted = false

        // A message may contain words like "then", so a complete send command is never split.
        val whole = CommandRouter.route(utterance)
        val steps = if (whole?.tool?.hasRecipient == true) listOf(utterance) else CommandRouter.steps(utterance)

        for (step in steps) {
            if (!AssistantRuntime.armed.value) return
            if (!limits.tryStep()) {
                speak("That task took too long, so I stopped")
                return
            }
            val call = CommandRouter.route(step) ?: when (val output = askGemma(step)) {
                null -> return
                is ModelOutput.Reply -> {
                    speak(output.text)
                    continue
                }
                is ModelOutput.Invalid -> {
                    Audit.log(context, "model_output_rejected")
                    speak("I couldn't work out a safe action for that")
                    return
                }
                is ModelOutput.Call -> output.call
            }
            if (!run(call, utterance, tainted)) return
            if (call.tool == Tool.READ_NOTIFICATIONS) tainted = true
            if (steps.size > 1) delay(700)
        }
    }

    private suspend fun askGemma(text: String): ModelOutput? {
        val raw = try {
            GemmaEngine.ask(context, text)
        } catch (e: Exception) {
            Audit.log(context, "model_error")
            speak("The AI model couldn't run. I can still do simple commands")
            return null
        }
        if (raw == null) {
            speak("Import the Gemma model in Control Center to ask me questions")
            return null
        }
        return ModelOutputParser.parse(raw)
    }

    private suspend fun run(call: ToolCall, utterance: String, tainted: Boolean): Boolean {
        val foreground = AutomationService.foregroundPackage
        val policy = PolicyContext(
            armed = AssistantRuntime.armed.value,
            source = Source.LIVE_USER,
            inPhoneCall = AssistantRuntime.inPhoneCall(context),
            tainted = tainted,
            voiceVerified = false, // Voice ID isn't built yet, so personal actions need your fingerprint.
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
            is Decision.Confirm -> if (!approve(call, decision.readback, foreground)) return false
            is Decision.Biometric -> if (!approve(call, decision.readback, foreground)) return false
        }
        if (call.tool.hasRecipient) AssistantRuntime.rateLimiter.record()
        val result = try {
            executor.execute(call)
        } catch (e: Exception) {
            Audit.log(context, "failed:${call.tool.id}")
            speak("That action failed")
            return false
        }
        Audit.log(context, "done:${call.tool.id}")
        speak(result)
        return true
    }

    /** Shows the app-built readback, then verifies a one-time token for this exact action. */
    private suspend fun approve(call: ToolCall, readback: String, foreground: String?): Boolean {
        val authority: ConfirmationAuthority = AssistantRuntime.authority ?: return false
        val token = authority.issue(call, foreground)
        val pending = CompletableDeferred<Boolean>()
        AssistantRuntime.pendingApproval = pending
        speak("Please confirm: $readback")
        context.startActivity(
            Intent(context, ConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ConfirmActivity.EXTRA_READBACK, readback),
        )
        val approved = withTimeoutOrNull(30_000) { pending.await() } == true
        AssistantRuntime.pendingApproval = null
        if (!approved) {
            Audit.log(context, "not_approved:${call.tool.id}")
            speak("Cancelled")
            return false
        }
        // Screen actions must land on the same app that was open when you were asked.
        val current = if (call.tool.category == Category.SCREEN) {
            delay(500)
            AutomationService.foregroundPackage
        } else {
            foreground
        }
        val valid = authority.verify(token, call, current)
        if (!valid) speak("That approval is no longer valid, so I cancelled it")
        return valid
    }
}
