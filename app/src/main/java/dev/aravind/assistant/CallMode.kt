package dev.aravind.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.aravind.assistant.policy.SecretRedactor
import kotlinx.coroutines.delay

/** Only fires for numbers you allowed. Nothing happens unless AI call mode is on. */
class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return
        if (!AssistantRuntime.isActive() || !Features.aiCalls(context)) return
        if (!CallRules.isAllowed(context, number)) return
        AssistantService.instance?.onAllowedIncomingCall(number)
    }
}

object CallRules {
    private fun prefs(context: Context) = context.getSharedPreferences("calls", Context.MODE_PRIVATE)

    private fun lastTen(number: String) = number.filter { it.isDigit() }.takeLast(10)

    fun allowedNumbers(context: Context): String = prefs(context).getString("allowed", "").orEmpty()

    fun setAllowedNumbers(context: Context, value: String) = prefs(context).edit().putString("allowed", value).apply()

    fun isAllowed(context: Context, number: String): Boolean {
        val wanted = lastTen(number)
        if (wanted.length < 10) return false
        return allowedNumbers(context).split(',', ' ', '\n').any { lastTen(it) == wanted }
    }
}

/** Caller messages are text only and stored in the encrypted database. Call audio is never saved. */
object CallMessages {
    fun add(context: Context, text: String) {
        Db.get(context).callMessages().insert(CallMessageRow(time = System.currentTimeMillis(), text = text))
    }

    fun list(context: Context): List<String> = Db.get(context).callMessages().latest().map { it.text }
}

/**
 * Experimental. Android gives the microphone to the call, so this relies on speakerphone
 * and on the accessibility exception. It runs with no phone tools, so a caller can't control anything.
 */
class CallSession(
    private val context: Context,
    private val speak: suspend (String) -> Unit,
    // Google listens for the caller's words through the speakerphone.
    private val listen: suspend () -> String?,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)

    private fun inCall() = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun useSpeaker() {
        audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { audio.setCommunicationDevice(it) }
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = true
    }

    suspend fun run(number: String) {
        delay(RING_BEFORE_ANSWER_MILLIS)
        if (!AssistantRuntime.isActive() || audio.mode != AudioManager.MODE_RINGTONE) return

        @Suppress("DEPRECATION")
        telecom.acceptRingingCall()
        Audit.log(context, "ai_call_answered")
        delay(1500)
        if (!inCall()) return
        useSpeaker()
        delay(500)

        val notes = ArrayList<String>()
        try {
            speak("Hello, this is an AI assistant answering for the owner of this phone. Who is calling, and what's your message?")
            repeat(MAX_TURNS) {
                if (!inCall() || !AssistantRuntime.isActive()) return@repeat
                val text = listen().orEmpty()
                if (text.isBlank()) return@repeat
                notes += SecretRedactor.redact(text)
                val reply = runCatching { GemmaEngine.replyToCaller(context, text) }
                    .getOrDefault("Thank you. I'll pass on your message.")
                speak(SecretRedactor.redact(reply).take(240))
            }
            if (inCall()) speak("Thank you. I'll pass this on. Goodbye.")
        } finally {
            if (inCall()) {
                @Suppress("DEPRECATION")
                telecom.endCall()
            }
            audio.clearCommunicationDevice()
            // Shows whether this phone really let the app hear the caller.
            Audit.log(context, "ai_call_finished:turns=${notes.size}")
            val summary = if (notes.isEmpty()) "No words were heard" else notes.joinToString(" | ")
            CallMessages.add(context, "${number.takeLast(10)}: $summary")
            AssistantRuntime.say("AI call from ${number.takeLast(10)} finished. ${notes.size} replies heard.")
        }
    }

    private companion object {
        const val RING_BEFORE_ANSWER_MILLIS = 12_000L
        const val MAX_TURNS = 4
    }
}
