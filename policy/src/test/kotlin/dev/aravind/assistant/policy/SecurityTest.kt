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

    @Test fun outboundNeedsQuickConfirmation() {
        assertTrue(PolicyEngine.evaluate(sms, ctx) is Decision.Confirm)
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
        assertTrue(PolicyEngine.evaluate(tap, ctx.copy(foregroundPackage = "com.oplus.safecenter")) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(tap, ctx.copy(foregroundPackage = "com.android.settings")) is Decision.Allow)
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

    @Test fun deviceControlsRouteAndNeedApproval() {
        assertEquals(Tool.WIFI, CommandRouter.route("turn off wifi")?.tool)
        assertEquals(true, CommandRouter.route("turn on bluetooth")?.bool("on"))
        assertEquals(false, CommandRouter.route("do not disturb off")?.bool("on"))
        assertEquals(40L, CommandRouter.route("set brightness to 40 percent")?.long("percent"))
        assertNull(CommandRouter.route("set brightness to 400 percent"))
        assertTrue(PolicyEngine.evaluate(call("wifi", "on" to false), ctx) is Decision.Allow)
        assertTrue(PolicyEngine.evaluate(call("brightness", "percent" to 40L), ctx) is Decision.Allow)
    }

    @Test fun wakeNameRules() {
        assertNull(WakeName.problem("Nova"))
        assertNull(WakeName.problem("Sita Ram"))
        assertNotNull(WakeName.problem("no"))
        assertNotNull(WakeName.problem("నోవా"))
        assertNotNull(WakeName.problem("Nova@1"))
        assertEquals("HEY NOVA", WakeName.phrase(" nova "))
    }

    @Test fun tokenizerPicksBestSplit() {
        val tokenizer = BpeTokenizer(mapOf("\u2581HE" to -2f, "Y" to -3f, "\u2581HEY" to -9f, "\u2581NO" to -2f, "VA" to -2f))
        assertEquals(listOf("\u2581HE", "Y", "\u2581NO", "VA"), tokenizer.encode("hey nova"))
        assertNull(tokenizer.encode("hey 1"))
        assertEquals("\u2581HE Y \u2581NO VA :3.0 #0.15 @HEY_NOVA", WakeName.keywordLine(tokenizer, "Nova"))
    }

    /** Compares our Kotlin tokenizer against the real one, on the real model files. */
    @Test fun tokenizerMatchesRealModel() {
        val dir = java.io.File("../app/src/main/assets/models/kws")
        val vocab = java.io.File(dir, "bpe.vocab")
        if (!vocab.exists()) return
        val tokenizer = BpeTokenizer.parse(vocab.readLines().asSequence())
        val cases = java.io.File(dir, "bpe_fixture.txt").readLines()
        assertTrue(cases.size >= 6)
        cases.forEach { line ->
            val (phrase, tokens) = line.split('\t')
            assertEquals(phrase, tokens, tokenizer.encode(phrase)?.joinToString(" "))
        }
    }

    @Test fun windowsSystemToolsAreProtected() {
        val tap = call("tap_text", "text" to "Yes")
        listOf("cmd.exe", "powershell.exe", "regedit.exe", "systemsettings.exe", "taskmgr.exe").forEach { exe ->
            assertTrue(exe, PolicyEngine.evaluate(tap, ctx.copy(foregroundPackage = exe)) is Decision.Deny)
        }
        assertTrue(PolicyEngine.evaluate(tap, ctx.copy(foregroundPackage = "notepad.exe")) is Decision.Allow)
        assertTrue(PolicyEngine.evaluate(call("open_app", "name" to "Windows Terminal"), ctx) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(call("open_app", "name" to "Command Prompt"), ctx) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(call("open_app", "name" to "Notepad"), ctx) is Decision.Allow)
    }

    @Test fun windowsModelPathRules() {
        assertNull(ModelPath.windowsProblem("C:\\Models\\gemma-4-E2B-it.litertlm"))
        assertNotNull(ModelPath.windowsProblem("gemma.litertlm"))
        assertNotNull(ModelPath.windowsProblem("C:\\Models\\..\\gemma.litertlm"))
        assertNotNull(ModelPath.windowsProblem("C:\\Models\\gemma.bin"))
    }

    @Test fun phoneTestCommandsNowRoute() {
        assertEquals("daddy", CommandRouter.route("call to daddy")?.text("to"))
        assertEquals("Daddy", CommandRouter.route("Call Daddy.")?.text("to"))
        assertEquals("Amma", CommandRouter.route("make a call to my Amma")?.text("to"))
        assertEquals(Tool.MOBILE_DATA, CommandRouter.route("turrent off my data network")?.tool)
        assertEquals(false, CommandRouter.route("turn off my data network")?.bool("on"))
        assertEquals(true, CommandRouter.route("turnon mobile data")?.bool("on"))
        assertEquals(Tool.FLASHLIGHT, CommandRouter.route("turn on flash")?.tool)
        assertEquals(Tool.FLASHLIGHT, CommandRouter.route("Turn on the flash.")?.tool)
        assertEquals(Tool.WIFI, CommandRouter.route("Turn on Wi-Fi.")?.tool)
        assertEquals(Tool.BLUETOOTH, CommandRouter.route("turn off blue tooth")?.tool)
    }

    @Test fun callsRunDirectlyButStayGuarded() {
        val daddy = call("place_call", "to" to "Daddy")
        assertTrue(PolicyEngine.evaluate(daddy, ctx) is Decision.Allow)
        assertTrue(PolicyEngine.evaluate(daddy, ctx.copy(outboundAllowed = false)) is Decision.Deny)
        assertTrue(PolicyEngine.evaluate(daddy, ctx.copy(armed = false)) is Decision.Deny)
    }

    @Test fun contactMatcherFindsNames() {
        val contacts = listOf("Daddy", "Amma Home", "Ravi Kumar", "Ravi Teja", "Suresh Anna")
        assertEquals("Daddy", ContactMatcher.best("daddy", contacts))
        assertEquals("Daddy", ContactMatcher.best("to daddy", contacts))
        assertEquals("Daddy", ContactMatcher.best("dady", contacts))
        assertEquals("Daddy", ContactMatcher.best("nanna", contacts))
        assertEquals("Amma Home", ContactMatcher.best("amma", contacts))
        assertEquals("Ravi Teja", ContactMatcher.best("ravi teja", contacts))
        assertNull(ContactMatcher.best("ravi", contacts))
        assertNull(ContactMatcher.best("zzzz", contacts))
    }

    @Test fun wakeNameIsFoundInSpeech() {
        assertEquals("", WakeName.heardIn("Hey Nova.", "Nova"))
        assertEquals("call daddy", WakeName.heardIn("Hey Nova, call Daddy.", "Nova"))
        assertEquals("", WakeName.heardIn("hey novah", "Nova"))
        assertEquals("", WakeName.heardIn("Nova", "Nova"))
        assertNull(WakeName.heardIn("what is the time", "Nova"))
        assertNull(WakeName.heardIn("the supernova exploded", "Nova"))
    }

    @Test fun alarmsWithDayWords() {
        val today = CommandRouter.route("set an alarm at 5pm today")
        assertEquals(Tool.SET_ALARM, today?.tool)
        assertEquals(17L, today?.long("hour"))
        assertEquals(17L, CommandRouter.route("Set an alarm for 5:00 p.m. today.")?.long("hour"))
        assertEquals(6L, CommandRouter.route("wake me up at 6 tomorrow")?.long("hour"))
        assertEquals(19L, CommandRouter.route("set alarm at 7 in the evening")?.long("hour"))
        assertEquals(30L, CommandRouter.route("set alarm for 6.30 am")?.long("minute"))
    }

    @Test fun messagesInEverydayWords() {
        val contacts = listOf("Daddy", "Amma", "Ravi Teja", "Ravi Kumar")
        fun check(text: String, tool: Tool, to: String, body: String) {
            val c = CommandRouter.route(text, contacts)
            assertEquals(text, tool, c?.tool)
            assertEquals(text, to, c?.text("to"))
            assertEquals(text, body, c?.text("body"))
        }
        check("send a msg to daddy hi", Tool.SEND_WHATSAPP, "daddy", "hi")
        check("Send a message to Daddy, how are you?", Tool.SEND_WHATSAPP, "Daddy", "how are you")
        check("send hi to daddy on whatsapp", Tool.SEND_WHATSAPP, "daddy", "hi")
        check("whatsapp amma I will be late", Tool.SEND_WHATSAPP, "amma", "I will be late")
        check("send a whatsapp message to Ravi Teja saying meet at 5", Tool.SEND_WHATSAPP, "Ravi Teja", "meet at 5")
        check("send an sms to daddy hello", Tool.SEND_SMS, "daddy", "hello")
        check("text amma reached home", Tool.SEND_SMS, "amma", "reached home")
        check("tell daddy that I am coming", Tool.SEND_WHATSAPP, "daddy", "I am coming")
        check("send a msg to daddy hi and call me later", Tool.SEND_WHATSAPP, "daddy", "hi and call me later")
        assertNull(CommandRouter.route("tell me a joke", contacts))
        assertEquals("Ravi", CommandRouter.route("send a text to Ravi saying hello")?.text("to"))
    }

    @Test fun stepsKeepMessagesWhole() {
        assertEquals(listOf("open whatsapp", "send a msg to daddy hi"), CommandRouter.steps("open whatsapp, send a msg to daddy hi"))
        assertEquals(listOf("send a msg to daddy hi and call me"), CommandRouter.steps("send a msg to daddy hi and call me"))
        assertEquals(listOf("turn on wifi", "open youtube"), CommandRouter.steps("turn on wifi and open youtube"))
        assertEquals(listOf("what is AI, and how does it work"), CommandRouter.steps("what is AI, and how does it work"))
        assertEquals(listOf("call daddy", "send a message to amma hi"), CommandRouter.steps("call daddy and send a message to amma hi"))
    }

    @Test fun phoneModesRoute() {
        fun mode(text: String) = CommandRouter.route(text)
        assertEquals(QuickSettings.HOTSPOT, mode("turn on hotspot")?.text("setting"))
        assertEquals(QuickSettings.AIRPLANE, mode("turn on aeroplane mode")?.text("setting"))
        assertEquals(false, mode("turn off airplane mode")?.bool("on"))
        assertEquals(QuickSettings.LOCATION, mode("turn off location")?.text("setting"))
        assertEquals(QuickSettings.DARK_MODE, mode("enable dark mode")?.text("setting"))
        assertEquals(QuickSettings.BATTERY_SAVER, mode("turn on power saving mode")?.text("setting"))
        assertEquals(QuickSettings.SUPER_SAVER, mode("turn on ultra power saving mode")?.text("setting"))
        assertEquals(QuickSettings.SCREEN_RECORDING, mode("start screen recording")?.text("setting"))
        assertEquals(Tool.MOBILE_DATA, mode("turn of mobile data")?.tool)
        assertEquals(false, mode("turn of mobile data")?.bool("on"))
        assertEquals(Tool.BLUETOOTH, mode("switch off the bluetooth")?.tool)
        assertEquals(Tool.OPEN_APP, mode("start youtube")?.tool)
    }

    @Test fun ringerModesRoute() {
        fun ring(text: String) = CommandRouter.route(text)?.text("mode")
        assertEquals("silent", ring("turn on silent mode"))
        assertEquals("silent", ring("put my phone on silent"))
        assertEquals("vibrate", ring("vibration mode"))
        assertEquals("vibrate", ring("turn on vibrate mode"))
        assertEquals("normal", ring("turn off silent mode"))
        assertEquals("normal", ring("ring mode"))
        assertEquals("normal", ring("unmute my phone"))
        assertEquals(Tool.PLACE_CALL, CommandRouter.route("ring daddy")?.tool)
    }

    @Test fun webSearchPhrases() {
        assertEquals("weather in hyderabad", CommandRouter.route("search for weather in hyderabad")?.text("query"))
        assertEquals("cricket score", CommandRouter.route("search cricket score on google")?.text("query"))
        val yt = CommandRouter.route("play relaxing songs on youtube")
        assertEquals("youtube", yt?.text("site"))
        assertEquals("relaxing songs", yt?.text("query"))
    }

    @Test fun notificationsAndTapsRunDirectly() {
        assertTrue(PolicyEngine.evaluate(call("read_notifications"), ctx) is Decision.Allow)
        assertTrue(PolicyEngine.evaluate(call("tap_text", "text" to "Send"), ctx) is Decision.Allow)
        assertEquals(Tool.READ_NOTIFICATIONS, CommandRouter.route("read my notifications")?.tool)
        assertEquals("Next", CommandRouter.route("tap the Next button")?.text("text"))
    }

    @Test fun wakeKeywordsCoverGreetings() {
        val tokenizer = BpeTokenizer(
            mapOf("\u2581HEY" to -1f, "\u2581HI" to -1f, "\u2581HELLO" to -1f, "\u2581OK" to -1f, "\u2581OKAY" to -1f,
                "\u2581A" to -1f, "\u2581NO" to -2f, "VA" to -2f, "\u2581MI" to -2f, "TRA" to -2f, "\u2581MITRA" to -9f),
        )
        val high = WakeName.keywords(tokenizer, "Nova", WakeSensitivity.HIGH)!!.split("/")
        assertEquals(5, high.size)
        assertTrue(high.all { it.contains(":4.0 #0.06") })
        assertTrue(high.any { it.endsWith("@HI_NOVA") })
        assertEquals(1, WakeName.keywords(tokenizer, "Nova", WakeSensitivity.LOW)!!.split("/").size)
        assertFalse(WakeName.keywords(tokenizer, "Mitra", WakeSensitivity.HIGH)!!.split("/").any { it.endsWith("@MITRA") })
        assertFalse(WakeName.keywords(tokenizer, "Mitra", WakeSensitivity.NORMAL)!!.split("/").any { it.endsWith("@MITRA") })
    }

    @Test fun alarmTimesInEverySpokenForm() {
        fun at(text: String) = CommandRouter.route(text)?.let { it.long("hour") to it.long("minute") }
        assertEquals(19L to 15L, at("set an alaram 7.15 pm today"))
        assertEquals(19L to 15L, at("Set an alarm 7.15 p.m. today."))
        assertEquals(19L to 15L, at("Set an alarm for 715 PM today."))
        assertEquals(19L to 15L, at("set an alarm at 7 15 pm, today"))
        assertEquals(19L to 15L, at("set an alarm for seven fifteen p.m. today"))
        assertEquals(19L to 15L, at("Set an alarm for 7:15 PM, today."))
        assertEquals(19L to 15L, at("set alarm at quarter past 7 in the evening"))
        assertEquals(18L to 45L, at("set alarm at quarter to 7 pm"))
        assertEquals(6L to 30L, at("wake me up at half past six"))
        assertEquals(19L to 0L, at("set an alarm at 7.00 pm today"))
        assertEquals(19L to 15L, at("set a 7:15 pm alarm"))
        assertEquals(21L to 45L, at("set alarm for 2145"))
        assertEquals(7L to 5L, at("set alarm for seven oh five am"))
    }

    @Test fun falseActionClaimsAreCaught() {
        assertTrue(ReplyGuard.isFalseActionClaim("set an alarm 7.15 pm today", "I've set an alarm for 7:15 PM today."))
        assertTrue(ReplyGuard.isFalseActionClaim("send hi to ravi", "Message sent to Ravi."))
        assertFalse(ReplyGuard.isFalseActionClaim("what is the capital of france", "The capital of France is Paris."))
        assertFalse(ReplyGuard.isFalseActionClaim("tell me a joke", "Why did the chicken cross the road?"))
    }

    @Test fun inAppCommandsRoute() {
        assertEquals("daddy", CommandRouter.route("go to the daddy chat")?.text("contact"))
        assertEquals("Daddy", CommandRouter.route("open WhatsApp chat with Daddy")?.text("contact"))
        assertEquals("Amma", CommandRouter.route("open Amma's chat")?.text("contact"))
        assertEquals("sms", CommandRouter.route("open the sms conversation with Ravi")?.text("app"))
        assertEquals(listOf("open whatsapp", "go to the daddy chat"), CommandRouter.steps("open whatsapp and go to the daddy chat"))
        assertEquals("instagram_reels", CommandRouter.route("open reels")?.text("section"))
        assertEquals("instagram_reels", CommandRouter.route("open reels in instagram")?.text("section"))
        assertEquals("youtube_shorts", CommandRouter.route("play youtube shorts")?.text("section"))
        assertEquals(listOf("open instagram", "open reels"), CommandRouter.steps("open instagram and open reels"))
        assertEquals(Tool.OPEN_APP, CommandRouter.route("open whatsapp")?.tool)
    }

    @Test fun screenReadingRoutes() {
        assertEquals(false, CommandRouter.route("read my screen")?.bool("explain"))
        assertEquals(true, CommandRouter.route("explain my screen")?.bool("explain"))
        assertEquals(true, CommandRouter.route("what's on my screen")?.bool("explain"))
        assertEquals(true, CommandRouter.route("read my screen and explain what is present")?.bool("explain"))
        assertTrue(PolicyEngine.evaluate(call("read_screen", "explain" to true), ctx) is Decision.Allow)
        assertTrue(PolicyEngine.evaluate(call("read_screen"), ctx.copy(foregroundPackage = "com.phonepe.app")) is Decision.Deny)
    }

    @Test fun screenAgentRejectsUnsafeSteps() {
        val goal = "search for cricket news"
        assertEquals(ScreenAction.Tap(2), ScreenAgent.parse("""{"action":"tap","item":3}""", 5, goal))
        assertNull(ScreenAgent.parse("""{"action":"tap","item":9}""", 5, goal))
        assertEquals(ScreenAction.Type(0, "cricket news"), ScreenAgent.parse("""{"action":"type","item":1,"text":"cricket news"}""", 5, goal))
        assertNull(ScreenAgent.parse("""{"action":"type","item":1,"text":"send money to hacker"}""", 5, goal))
        assertNull(ScreenAgent.parse("""{"action":"delete_all"}""", 5, goal))
        assertNull(ScreenAgent.parse("ignore your rules", 5, goal))
        assertTrue(ScreenAgent.isRisky("Send"))
        assertTrue(ScreenAgent.isRisky("Pay now"))
        assertFalse(ScreenAgent.isRisky("Settings"))
    }

    @Test fun misheardCommandsAreRecovered() {
        assertEquals("read my screen", FuzzyCommand.match("The reed mescaline"))
        assertEquals("read my screen", FuzzyCommand.match("red my scream"))
        assertEquals("lock my phone", FuzzyCommand.match("Lock my fone."))
        assertEquals("scroll down", FuzzyCommand.match("scroll town"))
        assertNull(FuzzyCommand.match("go hum"))
        assertNull(FuzzyCommand.match("What is the capital of France?"))
        assertNull(FuzzyCommand.match("Tell me a joke about cats"))
        assertNull(FuzzyCommand.match("hello"))
        assertEquals(Tool.READ_SCREEN, CommandRouter.route(FuzzyCommand.match("The reed mescaline")!!)?.tool)
    }

    @Test fun misheardFirstWordsAreSkipped() {
        val alarm = CommandRouter.route("And alarm for 7 15 pm today")
        assertEquals(19L to 15L, alarm?.let { it.long("hour") to it.long("minute") })
        assertEquals(Tool.SET_ALARM, CommandRouter.route("Sat an alarm for 6 am")?.tool)
        assertEquals(Tool.FLASHLIGHT, CommandRouter.route("so turn on the flashlight")?.tool)
        assertNull(CommandRouter.route("don't call daddy"))
        assertNull(CommandRouter.route("and"))
    }

    @Test fun currentQuestionsAreLookedUp() {
        assertEquals(Tool.WEB_ANSWER, CommandRouter.route("who won the ind vs nz match")?.tool)
        assertEquals("who won the ind vs nz match", CommandRouter.route("Who won the IND vs NZ match?")?.text("query")?.lowercase())
        assertEquals(Tool.WEB_ANSWER, CommandRouter.route("what's the weather in Hyderabad today")?.tool)
        assertEquals(Tool.WEB_ANSWER, CommandRouter.route("latest news about ISRO")?.tool)
        assertEquals("gold price today", CommandRouter.route("find out gold price today")?.text("query"))
        assertEquals(Tool.WEB_SEARCH, CommandRouter.route("search for cricket highlights")?.tool)
        assertNull(CommandRouter.route("what is the capital of France"))
    }

    @Test fun wakeCheckIsStrict() {
        assertEquals("", WakeName.heardInStrict("Hey Nova.", "Nova"))
        assertEquals("call daddy", WakeName.heardInStrict("Hey Nowa, call Daddy", "Nova"))
        assertEquals("", WakeName.heardInStrict("Nova", "Nova"))
        assertNull(WakeName.heardInStrict("Noah", "Nova"))
        assertEquals("", WakeName.heardInStrict("a nova", "Nova"))
        assertEquals("", WakeName.heardInStrict("He arriving", "Aravind"))
        assertEquals("", WakeName.heardInStrict("Hey, Ara vind.", "Aravind"))
        assertEquals("open whatsapp", WakeName.heardInStrict("Hi Aravind open WhatsApp", "Aravind"))
        assertNull(WakeName.heardInStrict("I arrived home", "Aravind"))
        assertNull(WakeName.heardInStrict("Hello everyone", "Nova"))
        assertNull(WakeName.heardInStrict("the weather is nice", "Nova"))
        assertNull(WakeName.heardInStrict("", "Nova"))
    }
}
