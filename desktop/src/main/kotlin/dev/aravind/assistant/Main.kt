package dev.aravind.assistant

import com.formdev.flatlaf.FlatDarkLaf
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.aravind.assistant.policy.AuditChain
import dev.aravind.assistant.policy.Category
import dev.aravind.assistant.policy.VoiceMatch
import dev.aravind.assistant.policy.WakeName
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.system.exitProcess

private val categoryNames = mapOf(
    Category.APPS to "Open apps",
    Category.SYSTEM to "Alarms, device settings, lock",
    Category.SCREEN to "Screen control",
    Category.NOTIFICATIONS to "Notifications (not available on Windows)",
    Category.MESSAGES to "WhatsApp messages",
    Category.CALLS to "Calls through Phone Link",
    Category.WEB to "Web search",
)

private fun background(block: () -> Unit) {
    Thread(block).also { it.isDaemon = true }.start()
}

private class Ui {
    val frame = JFrame("Pocket Assistant")
    val status = JLabel("Off")
    val master = JCheckBox("Assistant on")
    val talk = JButton("Talk")
    val log = JTextArea(14, 60)
    val note = JLabel(" ")
    private var syncing = false

    fun show() {
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                if (Runtime.armed) frame.isVisible = false else exit()
            }
        })
        val tabs = JTabbedPane()
        tabs.addTab("Assistant", home())
        tabs.addTab("Control Center", JScrollPane(control()).also { it.verticalScrollBar.unitIncrement = 16 })
        frame.contentPane.add(tabs, BorderLayout.CENTER)
        frame.contentPane.add(note, BorderLayout.SOUTH)
        frame.size = Dimension(760, 720)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        tray()
        Runtime.onChange = { SwingUtilities.invokeLater(::refresh) }
    }

    fun exit() {
        if (Runtime.armed) Runtime.stopAll("exit")
        exitProcess(0)
    }

    private fun refresh() {
        status.text = Runtime.status
        syncing = true
        master.isSelected = Runtime.armed
        syncing = false
        talk.isEnabled = Runtime.armed
        log.text = Runtime.transcript.takeLast(16).joinToString("\n")
    }

    private fun tray() {
        if (!SystemTray.isSupported()) return
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also {
            val g = it.createGraphics()
            g.color = Color(0x1F6FEB)
            g.fillOval(0, 0, 16, 16)
            g.dispose()
        }
        val menu = PopupMenu()
        fun item(label: String, action: () -> Unit) = MenuItem(label).also {
            it.addActionListener { action() }
            menu.add(it)
        }
        item("Open") { frame.isVisible = true }
        item("Talk") { Runtime.service?.loop?.requestListen() }
        item("Stop assistant") { Runtime.stopAll("tray") }
        item("Exit") { exit() }
        SystemTray.getSystemTray().add(TrayIcon(image, "Pocket Assistant", menu).also { it.isImageAutoSize = true })
    }

    private fun row(vararg items: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).also { panel ->
        items.forEach { panel.add(it) }
        panel.alignmentX = 0f
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height + 8)
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).also { it.addActionListener { action() } }

    private fun home(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        status.font = status.font.deriveFont(Font.BOLD, 16f)
        master.addActionListener {
            if (syncing) return@addActionListener
            if (!master.isSelected) {
                Runtime.stopAll("master_switch")
            } else {
                master.isSelected = false
                background {
                    if (Auth.verify("Turn on Pocket Assistant")) runCatching { Runtime.arm() }.onFailure { error ->
                        Audit.log("start_failed:${error.javaClass.simpleName}")
                        Runtime.updateStatus("Failed to start")
                    }
                }
            }
        }
        talk.isEnabled = false
        talk.addActionListener { Runtime.service?.loop?.requestListen() }
        panel.add(
            row(
                status, master, talk,
                button("Pause 15 min") { Runtime.pausedUntil = System.currentTimeMillis() + 900_000 },
                button("Resume") { Runtime.pausedUntil = 0 },
            ),
            BorderLayout.NORTH,
        )
        log.isEditable = false
        log.lineWrap = true
        log.wrapStyleWord = true
        panel.add(JScrollPane(log), BorderLayout.CENTER)
        return panel
    }

    private fun title(text: String) = JLabel(text).also {
        it.font = it.font.deriveFont(Font.BOLD, 14f)
        it.alignmentX = 0f
        it.border = BorderFactory.createEmptyBorder(14, 8, 4, 0)
    }

    private fun control(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        panel.add(title("Gemma model"))
        val path = JTextField(Gemma.path().orEmpty(), 44)
        val modelState = JLabel(Gemma.problem() ?: "Ready")
        panel.add(row(modelState))
        panel.add(row(path))
        panel.add(
            row(
                button("Browse") {
                    val chooser = JFileChooser()
                    if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) path.text = chooser.selectedFile.path
                },
                button("Use this path") {
                    val typed = path.text
                    background {
                        runCatching { Gemma.use(typed) { bytes -> note("Checking model: ${bytes / (1 shl 20)} MB") } }
                            .onSuccess {
                                note("Model selected. SHA-256: ${it.take(16)}...")
                                SwingUtilities.invokeLater { modelState.text = "Ready" }
                            }
                            .onFailure { note(it.message ?: "That model can't be used") }
                    }
                },
                button("Test model") {
                    note("Loading the model. This can take a minute...")
                    background {
                        runCatching { Gemma.test() }
                            .onSuccess { note("Model works: ${it.take(80)}") }
                            .onFailure { note("Model failed: ${it.message ?: it.javaClass.simpleName}") }
                    }
                },
            ),
        )

        panel.add(title("Tool switches"))
        Category.entries.filter { it != Category.NOTIFICATIONS }.forEach { category ->
            val used = Features.lastUsed(category)
            val suffix = if (used > 0) "  (last used ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(used))})" else ""
            val box = JCheckBox(categoryNames.getValue(category) + suffix, Features.gate(category))
            box.addActionListener {
                if (!box.isSelected) {
                    Features.setGate(category, false)
                } else {
                    box.isSelected = false
                    background {
                        if (Auth.verify("Turn on ${categoryNames.getValue(category)}")) {
                            Features.setGate(category, true)
                            SwingUtilities.invokeLater { box.isSelected = true }
                        }
                    }
                }
            }
            panel.add(row(box))
        }

        panel.add(title("Voice"))
        val name = JTextField(Features.wakeName(), 16)
        panel.add(
            row(
                JLabel("Assistant name"), name,
                button("Save name") {
                    val problem = WakeName.problem(name.text)
                    if (problem != null) {
                        note(problem)
                    } else {
                        val value = name.text
                        background {
                            if (Auth.verify("Change assistant name")) {
                                Features.setWakeName(value)
                                Runtime.service?.loop?.reloadWakeName()
                                note("Name saved. Say: ${WakeName.phrase(value).lowercase()}")
                            }
                        }
                    }
                },
            ),
        )
        val wake = JCheckBox("Wake word (the mic stays open while this is on)", Features.wakeWord())
        wake.addActionListener {
            if (!wake.isSelected) {
                Features.setWakeWord(false)
            } else {
                wake.isSelected = false
                background {
                    if (Auth.verify("Turn on wake word")) {
                        Features.setWakeWord(true)
                        SwingUtilities.invokeLater { wake.isSelected = true }
                    }
                }
            }
        }
        panel.add(row(wake))
        val languages = linkedMapOf("English" to "en", "Hindi" to "hi", "Telugu" to "te", "Auto" to "auto")
        val language = JComboBox(languages.keys.toTypedArray())
        language.selectedItem = languages.entries.first { it.value == Features.language() }.key
        language.addActionListener { Features.setLanguage(languages.getValue(language.selectedItem as String)) }
        panel.add(row(JLabel("Speaking language"), language))
        panel.add(
            row(
                button("Set up Voice ID") {
                    val core = Runtime.service
                    if (core == null) {
                        note("Turn on the assistant first")
                    } else {
                        background { if (Auth.verify("Set up Voice ID")) core.enrollVoice() }
                    }
                },
                button("Delete Voice ID") {
                    VoiceId.delete()
                    note("Voice ID deleted")
                },
            ),
        )

        panel.add(title("Contacts for WhatsApp and calls"))
        val contacts = JTextArea(Contacts.text(), 4, 40)
        panel.add(row(JScrollPane(contacts)))
        panel.add(
            row(
                JLabel("One per line, like Ravi=+919876543210"),
                button("Save contacts") {
                    val value = contacts.text
                    background {
                        if (Auth.verify("Save contacts")) {
                            Contacts.save(value)
                            note("Contacts saved")
                        }
                    }
                },
            ),
        )

        panel.add(title("Windows permissions"))
        fun link(label: String, target: String) = button(label) { Win.open(target) }
        panel.add(row(link("Microphone privacy", "ms-settings:privacy-microphone"), link("Startup apps", "ms-settings:startupapps")))
        panel.add(row(link("Notifications", "ms-settings:notifications"), link("Battery", "ms-settings:batterysaver")))

        panel.add(title("Security"))
        panel.add(
            row(
                button("Lockdown: stop everything") {
                    Runtime.stopAll("lockdown")
                    Category.entries.forEach { Features.setGate(it, false) }
                    Features.setWakeWord(false)
                    wake.isSelected = false
                    note("Lockdown on. All tools and the wake word are off.")
                },
                button("View audit log") {
                    val (entries, intact) = Audit.view()
                    val header = if (intact) "Audit log intact" else "WARNING: audit log failed its check"
                    JOptionPane.showMessageDialog(frame, JScrollPane(JTextArea((listOf(header) + entries.take(30)).joinToString("\n"), 18, 60)))
                },
                button("Delete all data") {
                    background {
                        if (Auth.verify("Delete all assistant data")) {
                            Runtime.deleteEverything()
                            note("All assistant data was deleted. Your Gemma model file wasn't touched.")
                        }
                    }
                },
            ),
        )
        panel.add(Box.createVerticalGlue())
        return panel
    }

    fun note(text: String) = SwingUtilities.invokeLater { note.text = " $text" }
}

/** Runs the real voice models and security storage, then reports each result. */
private fun selfTest(report: File): Int {
    val lines = ArrayList<String>()
    var failed = 0
    fun step(name: String, block: () -> String) {
        val result = runCatching(block)
        val text = result.fold({ "PASS $name: $it" }, { failed++; "FAIL $name: ${it.javaClass.simpleName} ${it.message}" })
        lines += text
        report.writeText(lines.joinToString("\n"))
    }
    val engines = VoiceEngines()
    var spoken = FloatArray(0)
    step("encryption and audit") {
        check(String(Keys.decrypt(Keys.encrypt("secret".toByteArray()))) == "secret")
        val key = Keys.secret("selftest")
        val mac = AuditChain.link(key, AuditChain.GENESIS, "a")
        check(AuditChain.verify(key, listOf("a" to mac)))
        "DPAPI-protected keys work"
    }
    step("piper english") {
        val audio = engines.synthesize("Please open the calendar and set a timer.", false)
        spoken = resample(audio.samples, audio.sampleRate)
        check(spoken.size > SAMPLE_RATE)
        "${spoken.size} samples"
    }
    step("piper hindi") {
        check(engines.synthesize("नमस्ते, आप कैसे हैं", true).samples.isNotEmpty())
        "voice generated"
    }
    step("whisper.cpp") {
        val text = WhisperCpp.transcribe(spoken, "en")
        check(text.contains("timer", ignoreCase = true) || text.contains("calendar", ignoreCase = true)) { "heard: $text" }
        text
    }
    step("wake word") {
        val stream = engines.spotter.createStream(engines.wakeKeywords())
        stream.acceptWaveform(spoken, SAMPLE_RATE)
        while (engines.spotter.isReady(stream)) engines.spotter.decode(stream)
        stream.release()
        "model and name tokens load"
    }
    step("vad") {
        val vad = engines.newVad()
        val window = 512
        var offset = 0
        while (offset + window <= spoken.size) {
            vad.acceptWaveform(spoken.copyOfRange(offset, offset + window))
            offset += window
        }
        vad.flush()
        check(!vad.empty()) { "no speech found" }
        vad.release()
        "speech found"
    }
    step("voice id") {
        val print = requireNotNull(engines.embedding(spoken)) { "no embedding" }
        check(VoiceMatch.isMatch(print, print))
        "${print.size} values"
    }
    step("gemma runtime loads") {
        val outcome = runCatching {
            Engine(EngineConfig(modelPath = "C:\\missing.litertlm", backend = Backend.CPU(), cacheDir = AppPaths.data.path)).initialize()
        }
        val error = outcome.exceptionOrNull()
        check(error !is LinkageError) { "native library failed: $error" }
        "native library loaded, and it rejected the missing file"
    }
    step("powershell tools") {
        check(Ps.run("Write-Output 'OK'").endsWith("OK"))
        "fixed scripts run"
    }
    report.writeText(lines.joinToString("\n"))
    return if (failed == 0) 0 else 1
}

private fun resample(samples: FloatArray, rate: Int): FloatArray {
    if (rate == SAMPLE_RATE) return samples
    val ratio = rate.toDouble() / SAMPLE_RATE
    return FloatArray((samples.size / ratio).toInt()) { samples[(it * ratio).toInt().coerceAtMost(samples.size - 1)] }
}

fun main(args: Array<String>) {
    if (args.size == 2 && args[0] == "--self-test") {
        val code = runCatching { selfTest(File(args[1])) }.getOrElse { error ->
            File(args[1]).appendText("\nFAIL self-test crashed: $error")
            2
        }
        exitProcess(code)
    }
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater { Ui().show() }
}
