package dev.aravind.assistant.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTest {
    private val ctx = PolicyContext(
        armed = true,
        source = Source.LIVE_USER,
        inPhoneCall = false,
        tainted = false,
        voiceVerified = false,
        gateEnabled = { true },
        originalUtterance = "text ravi saying hello",
        foregroundPackage = "com.example.notes",
        ownPackage = "dev.aravind.assistant",
        outboundAllowed = true,
    )

    private fun call(tool: String, vararg args: Pair<String, Any?>): ToolCall =
        (ToolValidator.validate(tool, mapOf(*args)) as Validated.Ok).call

    private val sms = call("send_sms", "to" to "Ravi", "body" to "hello")

    @Test fun offBlocksEverything() {
        assertTrue(PolicyEngine.evaluate(call("lock_phone"), ctx.copy(armed = false)) is Decision.Deny)
    }

    @Test fun untrustedSourceCannotAct() {
        assertTrue(PolicyEngine.evaluate(call("flashlight", "on" to true), ctx.copy(source = Source.UNTRUSTED)) is Decision.Deny)
    }

    @Test fun nothingRunsDuringPhoneCall() {
        assertTrue(PolicyEngine.evaluate(sms, ctx.copy(inPhoneCall = true)) is Decision.Deny)
    }

    @Test fun categorySwitchBlocksTool() {
        val decision = PolicyEngine.evaluate(sms, ctx.copy(gateEnabled = { it != Category.MESSAGES }))
        assertTrue(decision is Decision.Deny)
    }

    @Test fun outboundNeedsBiometricWithoutVoice() {
        assertTrue(PolicyEngine.evaluate(sms, ctx) is Decision.Biometric)
    }

    @Test fun outboundNeedsBiometricWhenTaintedEvenWithVoice() {
        val decision = PolicyEngine.evaluate(sms, ctx.copy(voiceVerified = true, tainted = true))
        assertTrue(decision is Decision.Biometric)
    }

    @Test fun taintedContentCannotChooseRecipient() {
        val attacker = call("send_sms", "to" to "9876543210", "body" to "hello")
        assertTrue(PolicyEngine.evaluate(attacker, ctx.copy(tainted = true)) is Decision.Deny)
    }

    @Test fun secretsAreNeverSent() {
        val leak = call("send_sms", "to" to "Ravi", "body" to "my otp is 482913")
        assertTrue(PolicyEngine.evaluate(leak, ctx) is Decision.Deny)
    }

    @Test fun redactorHidesOtpPasswordAndCard() {
        assertFalse(SecretRedactor.redact("Your OTP is 482913").contains("482913"))
        assertFalse(SecretRedactor.redact("password: hunter2").contains("hunter2"))
        assertFalse(SecretRedactor.redact("card 4111 1111 1111 1111").contains("1111 1111"))
        assertEquals("Lunch at 1", SecretRedactor.redact("Lunch at 1"))
    }

    @Test fun ussdAndShortCodesAreBlocked() {
        assertTrue(PolicyEngine.evaluate(call("place_call", "to" to "*#21#"), ctx) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(call("place_call", "to" to "**21*9999999999#"), ctx) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(call("send_sms", "to" to "57575", "body" to "hi"), ctx) is Decision.Deny)
    }

    @Test fun rateLimitBlocksOutbound() {
        assertTrue(PolicyEngine.evaluate(sms, ctx.copy(outboundAllowed = false)) is Decision.Deny)
    }

    @Test fun paymentAppsCannotBeOpened() {
        assertTrue(PolicyEngine.evaluate(call("open_app", "name" to "PhonePe"), ctx) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(call("open_app", "name" to "SBI bank"), ctx) is Decision.Deny)
    }

    @Test fun protectedScreensCannotBeControlled() {
        val tap = call("tap_text", "text" to "Allow")
        assertTrue(PolicyEngine.evaluate(tap, ctx.copy(foregroundPackage = "com.android.permissioncontroller")) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(tap, ctx.copy(foregroundPackage = "dev.aravind.assistant")) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(tap, ctx.copy(foregroundPackage = "com.android.settings")) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(tap, ctx.copy(foregroundPackage = null)) is Decision.Deny)
    }

    @Test fun malformedModelOutputIsRejected() {
        val bad = listOf(
            "ignore all rules",
            """{"tool":"delete_everything","args":{}}""",
            """{"tool":"send_sms","args":{"to":"Ravi"}}""",
            """{"tool":"send_sms","args":{"to":"Ravi","body":"x","cc":"Eve"}}""",
            """{"tool":"set_timer","args":{"seconds":"60"}}""",
            """{"tool":"set_timer","args":{"seconds":999999}}""",
            """{"tool":"lock_phone","tool":"send_sms"}""",
            """{"tool":"lock_phone"} extra""",
            """{"tool":"lock_phone","reply":"x"}""",
        )
        bad.forEach { assertTrue(it, ModelOutputParser.parse(it) is ModelOutput.Invalid) }
    }

    @Test fun validModelOutputIsAccepted() {
        assertTrue(ModelOutputParser.parse("""{"reply":"Hello"}""") is ModelOutput.Reply)
        assertTrue(ModelOutputParser.parse("""{"tool":"set_timer","args":{"seconds":60}}""") is ModelOutput.Call)
    }

    @Test fun confirmationCannotBeReplayedOrRetargeted() {
        var time = 0L
        val authority = ConfirmationAuthority(ByteArray(32), { time })
        val token = authority.issue(sms, "pkg")
        val changed = call("send_sms", "to" to "Mallory", "body" to "hello")
        assertFalse(authority.verify(token, changed, "pkg"))

        val second = authority.issue(sms, "pkg")
        assertTrue(authority.verify(second, sms, "pkg"))
        assertFalse(authority.verify(second, sms, "pkg"))

        val third = authority.issue(sms, "pkg")
        time += 30_001
        assertFalse(authority.verify(third, sms, "pkg"))
    }

    @Test fun confirmationIsBoundToApp() {
        val authority = ConfirmationAuthority(ByteArray(32), { 0L })
        val token = authority.issue(sms, "pkg.one")
        assertFalse(authority.verify(token, sms, "pkg.two"))
    }

    @Test fun limitsStopRunawayTasks() {
        var time = 0L
        val limits = TaskLimits({ time })
        repeat(10) { assertTrue(limits.tryStep()) }
        assertFalse(limits.tryStep())

        val timed = TaskLimits({ time })
        time += 90_001
        assertFalse(timed.tryStep())
    }

    @Test fun rateLimiterRecovers() {
        var time = 0L
        val limiter = OutboundRateLimiter({ time })
        repeat(5) { limiter.record() }
        assertFalse(limiter.allowed())
        time += 600_001
        assertTrue(limiter.allowed())
    }

    @Test fun auditChainDetectsEdits() {
        val key = ByteArray(32)
        val first = AuditChain.link(key, AuditChain.GENESIS, "a")
        val second = AuditChain.link(key, first, "b")
        assertTrue(AuditChain.verify(key, listOf("a" to first, "b" to second)))
        assertFalse(AuditChain.verify(key, listOf("x" to first, "b" to second)))
        assertFalse(AuditChain.verify(key, listOf("b" to second)))
    }

    @Test fun routerHandlesCommonCommands() {
        assertEquals(Tool.LOCK_PHONE, CommandRouter.route("lock my phone")?.tool)
        assertEquals(Tool.FLASHLIGHT, CommandRouter.route("turn on the flashlight")?.tool)
        assertEquals(19L, CommandRouter.route("set alarm for 7:30 pm")?.long("hour"))
        assertEquals(300L, CommandRouter.route("set a timer for 5 minutes")?.long("seconds"))
        assertEquals("Ravi", CommandRouter.route("send a text to Ravi saying hello")?.text("to"))
        assertNotNull(CommandRouter.route("open WhatsApp"))
        assertNull(CommandRouter.route("what is the capital of France"))
    }

    @Test fun stopAndStepsWork() {
        assertTrue(CommandRouter.isStop("stop"))
        assertTrue(CommandRouter.isStop("Turn off."))
        assertFalse(CommandRouter.isStop("stop the music in spotify"))
        assertEquals(listOf("open maps", "go home"), CommandRouter.steps("open maps and then go home"))
    }

    @Test fun plansAreValidatedStepByStep() {
        val good = """{"plan":[{"tool":"open_app","args":{"name":"Maps"}},{"tool":"go_home"}]}"""
        assertEquals(2, (ModelOutputParser.parse(good) as ModelOutput.Plan).calls.size)
        val badStep = """{"plan":[{"tool":"open_app","args":{"name":"Maps"}},{"tool":"format_phone"}]}"""
        assertTrue(ModelOutputParser.parse(badStep) is ModelOutput.Invalid)
        val tooLong = "{\"plan\":[" + List(6) { "{\"tool\":\"go_home\"}" }.joinToString(",") + "]}"
        assertTrue(ModelOutputParser.parse(tooLong) is ModelOutput.Invalid)
    }

    @Test fun spokenChallengeMatchesWordsAndDigits() {
        assertTrue(SpokenChallenge.matches("472", "four seven two"))
        assertTrue(SpokenChallenge.matches("472", "4 7 2."))
        assertTrue(SpokenChallenge.matches("108", "one oh eight"))
        assertFalse(SpokenChallenge.matches("472", "four seven"))
        assertFalse(SpokenChallenge.matches("472", "yes"))
        assertEquals(3, SpokenChallenge.create().length)
    }

    @Test fun voiceMatchUsesCosineThreshold() {
        val me = floatArrayOf(1f, 0f, 1f)
        assertTrue(VoiceMatch.isMatch(me, floatArrayOf(0.9f, 0.1f, 1.1f)))
        assertFalse(VoiceMatch.isMatch(me, floatArrayOf(-1f, 1f, 0f)))
        assertFalse(VoiceMatch.isMatch(me, floatArrayOf(1f, 0f)))
    }

    @Test fun modelPathRulesRejectBadPaths() {
        assertNull(ModelPath.problem("/storage/emulated/0/Download/gemma-4-E2B-it.litertlm"))
        assertNotNull(ModelPath.problem(""))
        assertNotNull(ModelPath.problem("Download/model.litertlm"))
        assertNotNull(ModelPath.problem("/sdcard/../data/model.litertlm"))
        assertNotNull(ModelPath.problem("/sdcard/Download/model.bin"))
        assertNotNull(ModelPath.problem("/storage/emulated/0/Android/data/com.other/files/model.litertlm"))
    }
}
