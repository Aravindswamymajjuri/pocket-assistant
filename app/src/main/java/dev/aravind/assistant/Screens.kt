package dev.aravind.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.app.KeyguardManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.aravind.assistant.policy.Category
import dev.aravind.assistant.policy.WakeName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun FragmentActivity.protectWindow() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    window.setHideOverlayWindows(true)
    window.decorView.filterTouchesWhenObscured = true
}

private fun FragmentActivity.authenticate(title: String, subtitle: String, done: (Boolean) -> Unit) {
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build(),
    )
}

/** Approval screen for one action. Screenshots and overlays are blocked here. */
class ConfirmActivity : FragmentActivity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectWindow()
        val readback = intent.getStringExtra(EXTRA_READBACK).orEmpty()
        val biometric = intent.getBooleanExtra(EXTRA_BIOMETRIC, true)
        setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.statusBarsPadding())
                    Text("Confirm this action", style = MaterialTheme.typography.headlineSmall)
                    Text(readback, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { if (biometric) approve(readback) else finishWith(true) }, Modifier.fillMaxWidth()) {
                        Text(if (biometric) "Approve with fingerprint" else "Send")
                    }
                    OutlinedButton(onClick = { finishWith(false) }, Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
    }

    private fun approve(readback: String) {
        authenticate("Approve action", readback.take(120)) { ok -> finishWith(ok) }
    }

    private fun finishWith(approved: Boolean) {
        if (answered) return
        answered = true
        AssistantRuntime.pendingApproval?.complete(approved)
        finish()
    }

    override fun onDestroy() {
        if (!answered && isFinishing) AssistantRuntime.pendingApproval?.complete(false)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_READBACK = "readback"
        const val EXTRA_BIOMETRIC = "biometric"
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectWindow()
        setContent { AppTheme { HomeScreen(this) } }
    }
}

private fun startIfPossible(activity: FragmentActivity) {
    val micGranted = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    if (micGranted && !AssistantRuntime.armed.value) AssistantRuntime.arm(activity)
}

/** Opens the wake screen over other apps, and over the lock screen when Android allows it. */
object WakeUi {
    private const val NOTIFICATION = 2
    private const val CHANNEL = "wake"
    val closeRequests = MutableStateFlow(0)

    @Volatile
    var visible = false

    fun open(context: Context) {
        if (visible) return
        val intent = Intent(context, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }
        // Without the Accessibility Service, Android only allows this through a notification.
        if (AutomationService.instance == null) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Wake screen", NotificationManager.IMPORTANCE_HIGH))
            val pending = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE)
            manager.notify(
                NOTIFICATION,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_assistant)
                    .setContentTitle("Listening")
                    .setFullScreenIntent(pending, true)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    fun clearNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }

    fun close() {
        closeRequests.value++
    }
}

/** The screen shown when the wake word is heard. */
class WakeActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        WakeUi.visible = true
        WakeUi.clearNotification(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { WakeScreen(onClose = ::finish) }
        }
    }

    override fun onDestroy() {
        WakeUi.visible = false
        super.onDestroy()
    }
}

@Composable
private fun WakeScreen(onClose: () -> Unit) {
    val status by AssistantRuntime.status.collectAsState()
    val transcript by AssistantRuntime.transcript.collectAsState()
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.82f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "size",
    )
    val startCount = remember { WakeUi.closeRequests.value }
    LaunchedEffect(Unit) {
        WakeUi.closeRequests.first { it != startCount }
        onClose()
    }
    LaunchedEffect(status) {
        if (status == "Ready" || status == "Off") {
            delay(4500)
            onClose()
        }
    }
    val heard = transcript.lastOrNull { it.startsWith("You: ") }?.removePrefix("You: ").orEmpty()
    val reply = transcript.lastOrNull { it.startsWith("Assistant: ") }?.removePrefix("Assistant: ").orEmpty()
    // A card at the bottom, so the app you're using stays visible above it.
    Box(Modifier.fillMaxSize().clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Card(Modifier.fillMaxWidth().padding(12.dp).clickable(enabled = false) {}) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(64.dp)
                        .graphicsLayer {
                            scaleX = pulse.value
                            scaleY = pulse.value
                        }
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Text(if (status == "Ready") "Done" else status, style = MaterialTheme.typography.titleLarge)
                if (heard.isNotEmpty()) Text(heard, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (reply.isNotEmpty()) Text(reply, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                OutlinedButton(
                    onClick = {
                        AssistantService.instance?.let { it.startService(Intent(it, AssistantService::class.java).setAction(AssistantService.ACTION_LISTEN)) }
                    },
                ) { Text("Talk again") }
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

/** Android 12 lets apps post notifications without this permission. */
private val notificationPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf("Notifications" to Manifest.permission.POST_NOTIFICATIONS) else emptyList()

private val runtimePermissions = notificationPermission + listOf(
    "Microphone" to Manifest.permission.RECORD_AUDIO,
    "Nearby devices (Bluetooth)" to Manifest.permission.BLUETOOTH_CONNECT,
    "Contacts" to Manifest.permission.READ_CONTACTS,
    "Phone calls" to Manifest.permission.CALL_PHONE,
    "SMS" to Manifest.permission.SEND_SMS,
    "Phone state (AI calls)" to Manifest.permission.READ_PHONE_STATE,
    "Caller number (AI calls)" to Manifest.permission.READ_CALL_LOG,
    "Answer calls (AI calls)" to Manifest.permission.ANSWER_PHONE_CALLS,
)

private val categoryNames = mapOf(
    Category.APPS to "Open apps",
    Category.SYSTEM to "Alarms, device settings, lock",
    Category.SCREEN to "Screen control",
    Category.NOTIFICATIONS to "Read notifications",
    Category.MESSAGES to "SMS and WhatsApp",
    Category.CALLS to "Phone calls",
    Category.WEB to "Web search",
)

@Composable
private fun HomeScreen(activity: FragmentActivity) {
    val armed by AssistantRuntime.armed.collectAsState()
    val status by AssistantRuntime.status.collectAsState()
    val transcript by AssistantRuntime.transcript.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val refreshKey = refresh

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    // Asks once for what the assistant needs, then starts it so the wake word works right away.
    val startupPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        startIfPossible(activity)
    }
    LaunchedEffect(Unit) {
        val missing = runtimePermissions.map { it.second }
            .filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startIfPossible(activity) else startupPermissions.launch(missing.toTypedArray())
    }
    var modelPath by remember { mutableStateOf(ModelStore.path(activity).orEmpty()) }
    var wakeName by remember { mutableStateOf(Features.wakeName(activity)) }
    var allowedNumbers by remember { mutableStateOf(CallRules.allowedNumbers(activity)) }
    val paused by AssistantRuntime.pausedUntil.collectAsState()
    val modelBrowser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val path = ModelStore.pathFromPicker(uri)
            if (path != null) {
                modelPath = path
                message = "Path filled in. Tap Use this path."
            } else {
                scope.launch {
                    runCatching {
                        val sha = ModelStore.copyIn(activity, uri) { bytes -> message = "Copying model: ${bytes / (1 shl 20)} MB" }
                        message = "Model copied. SHA-256: ${sha.take(16)}..."
                        modelPath = ModelStore.path(activity).orEmpty()
                    }.onFailure { message = it.message ?: "Model copy failed" }
                    refresh++
                }
            }
        }
    }
    val modelCopier = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val sha = ModelStore.copyIn(activity, uri) { bytes -> message = "Copying model: ${bytes / (1 shl 20)} MB" }
                    message = "Model copied. SHA-256: ${sha.take(16)}..."
                    modelPath = ModelStore.path(activity).orEmpty()
                }.onFailure { message = it.message ?: "Model copy failed" }
                refresh++
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pocket Assistant", style = MaterialTheme.typography.headlineMedium)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Assistant") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") })
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)

        if (tab == 0) {
        Text(
            "Say \"${WakeName.phrase(Features.wakeName(activity)).lowercase()}\" or tap Talk",
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow("Assistant", status, armed) { on ->
                    if (!on) {
                        AssistantRuntime.stopAll(activity, "master_switch")
                    } else {
                        val micGranted = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (!micGranted) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            AssistantRuntime.arm(activity)
                        }
                    }
                }
                if (armed) {
                    Button(
                        onClick = {
                            activity.startService(
                                Intent(activity, AssistantService::class.java).setAction(AssistantService.ACTION_LISTEN),
                            )
                        },
                        Modifier.fillMaxWidth(),
                    ) { Text("Talk") }
                    if (paused > System.currentTimeMillis()) {
                        OutlinedButton(onClick = { AssistantRuntime.resume(activity) }, Modifier.fillMaxWidth()) { Text("Resume") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { AssistantRuntime.pause(activity, 15) }) { Text("15 min") }
                            OutlinedButton(onClick = { AssistantRuntime.pause(activity, 60) }) { Text("1 hour") }
                            OutlinedButton(onClick = { AssistantRuntime.pause(activity, null) }) { Text("Pause") }
                        }
                    }
                }
            }
        }

        if (transcript.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    transcript.takeLast(8).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        } else {
        Section("Control Center")
        Text("Tool switches", style = MaterialTheme.typography.titleSmall)
        Category.entries.forEach { category ->
            val enabled = remember(refreshKey) { Gates.enabled(activity, category) }
            val used = remember(refreshKey) { Gates.lastUsed(activity, category) }
            val usedText = if (used > 0) "Last used ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(used))}" else "Not used yet"
            ToggleRow(categoryNames.getValue(category), usedText, enabled) { on ->
                Gates.set(activity, category, on)
                refresh++
            }
        }

        HorizontalDivider()
        Text("Android permissions", style = MaterialTheme.typography.titleSmall)
        runtimePermissions.forEach { (label, permission) ->
            val granted = remember(refreshKey) { activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
            StateRow(label, if (granted) "On" else "Off", if (granted) "Turn off" else "Allow") {
                if (granted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        activity.revokeSelfPermissionOnKill(permission)
                        message = "$label will turn off when the app closes. Use Lockdown or close it from Recents."
                    } else {
                        openAppInfo(activity)
                        message = "Android 12 can't remove this from inside the app. Open Permissions and turn off $label."
                    }
                } else {
                    permissionLauncher.launch(permission)
                }
            }
        }

        val packageName = activity.packageName
        val accessibilityOn = remember(refreshKey) { AutomationService.instance != null }
        StateRow("Accessibility Service", if (accessibilityOn) "On" else "Off", if (accessibilityOn) "Turn off" else "Open settings") {
            val service = AutomationService.instance
            if (service != null) service.disableSelf() else activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            refresh++
        }
        val notificationsOn = remember(refreshKey) {
            activity.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(activity, NotificationsService::class.java))
        }
        StateRow("Notification access", if (notificationsOn) "On" else "Off", "Open settings") {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val batteryExempt = remember(refreshKey) {
            activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }
        StateRow("Unrestricted battery", if (batteryExempt) "On" else "Off", "Open settings") {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        StateRow("Realme auto-start and background", "Android can't report this", "Open app info") {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        val allFiles = remember(refreshKey) { Environment.isExternalStorageManager() }
        StateRow("All files access (model path)", if (allFiles) "On" else "Off", "Open settings") {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)),
            )
        }
        val canWriteSettings = remember(refreshKey) { Settings.System.canWrite(activity) }
        StateRow("Modify system settings (brightness)", if (canWriteSettings) "On" else "Off", "Open settings") {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        val dndAccess = remember(refreshKey) { activity.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }
        StateRow("Do Not Disturb access", if (dndAccess) "On" else "Off", "Open settings") {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        Button(onClick = { refresh++ }, Modifier.fillMaxWidth()) { Text("Refresh status") }

        HorizontalDivider()
        Text("Voice", style = MaterialTheme.typography.titleSmall)
        val wakeOn = remember(refreshKey) { Features.wakeWord(activity) }
        OutlinedTextField(
            value = wakeName,
            onValueChange = { wakeName = it },
            label = { Text("Assistant name") },
            supportingText = { Text("You'll say: ${WakeName.phrase(wakeName).lowercase()}") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                val problem = WakeName.problem(wakeName)
                if (problem != null) {
                    message = problem
                } else {
                    Features.setWakeName(activity, wakeName)
                    Features.setWakeWord(activity, true)
                    AssistantService.instance?.onWakeNameChanged()
                    refresh++
                    message = "Name saved. Say: ${WakeName.phrase(wakeName).lowercase()}"
                }
            },
            Modifier.fillMaxWidth(),
        ) { Text("Save name") }
        val language = remember(refreshKey) { Features.language(activity) }
        Text("Speaking language", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("en" to "English", "hi" to "Hindi", "te" to "Telugu", "auto" to "Auto").forEach { (code, label) ->
                val onClick = {
                    Features.setLanguage(activity, code)
                    refresh++
                    Unit
                }
                if (code == language) {
                    Button(onClick = onClick) { Text(label) }
                } else {
                    OutlinedButton(onClick = onClick) { Text(label) }
                }
            }
        }
        ToggleRow("Wake word", "The mic stays open while this is on", wakeOn) { on ->
            Features.setWakeWord(activity, on)
            refresh++
        }
        val autoPause = remember(refreshKey) { Features.autoPause(activity) }
        ToggleRow("Auto-pause wake word", "Below 15% battery, or when the phone is too hot", autoPause) { on ->
            Features.setAutoPause(activity, on)
            refresh++
        }
        val enrolled = remember(refreshKey) { VoiceId.isEnrolled(activity) }
        StateRow("Voice ID", if (enrolled) "Saved" else "Not set up, so fingerprint is required", if (enrolled) "Delete" else "Set up") {
            if (enrolled) {
                VoiceId.delete(activity)
                refresh++
            } else if (!armed) {
                message = "Turn on the assistant first"
            } else {
                AssistantService.instance?.enrollVoice()
            }
        }

        HorizontalDivider()
        Text("AI call answering (experimental)", style = MaterialTheme.typography.titleSmall)
        val callsOn = remember(refreshKey) { Features.aiCalls(activity) }
        ToggleRow("Answer allowed callers", "Uses speakerphone and may not work on this phone", callsOn) { on ->
            Features.setAiCalls(activity, on)
            refresh++
        }
        OutlinedTextField(
            value = allowedNumbers,
            onValueChange = { allowedNumbers = it },
            label = { Text("Allowed numbers, separated by commas") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                CallRules.setAllowedNumbers(activity, allowedNumbers)
                message = "Allowed callers saved"
            },
            Modifier.fillMaxWidth(),
        ) { Text("Save allowed numbers") }
        OutlinedButton(
            onClick = {
                activity.authenticate("View call messages", "Confirm it's you") { ok ->
                    if (ok) message = CallMessages.list(activity).ifEmpty { listOf("No call messages") }.joinToString("\n")
                }
            },
            Modifier.fillMaxWidth(),
        ) { Text("View call messages") }

        HorizontalDivider()
        Text("Gemma model", style = MaterialTheme.typography.titleSmall)
        val modelHash = remember(refreshKey) { ModelStore.hash(activity) }
        val modelProblem = remember(refreshKey) { ModelStore.problem(activity) }
        Text(
            modelProblem ?: "Ready. SHA-256: ${modelHash.orEmpty().take(16)}...",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = modelPath,
            onValueChange = { modelPath = it },
            label = { Text("Model path") },
            placeholder = { Text("/storage/emulated/0/Download/gemma-4-E2B-it.litertlm") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { modelBrowser.launch(arrayOf("*/*")) }) { Text("Browse") }
            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            val sha = ModelStore.usePath(activity, modelPath) { bytes -> message = "Checking model: ${bytes / (1 shl 20)} MB" }
                            message = "Model selected. SHA-256: ${sha.take(16)}..."
                        }.onFailure { message = it.message ?: "That model can't be used" }
                        refresh++
                    }
                },
            ) { Text("Use this path") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { modelCopier.launch(arrayOf("*/*")) }) { Text("Copy into app") }
            OutlinedButton(
                onClick = {
                    message = "Loading the model. This can take a minute..."
                    scope.launch {
                        runCatching { GemmaEngine.test(activity) }
                            .onSuccess { message = "Model works: $it" }
                            .onFailure { message = "Model failed: ${it.message ?: it.javaClass.simpleName}" }
                    }
                },
            ) { Text("Test model") }
        }
        val gpu = remember(refreshKey) { ModelStore.useGpu(activity) }
        ToggleRow("Use GPU", "Off uses the CPU, which is slower but more reliable", gpu) { on ->
            ModelStore.setUseGpu(activity, on)
            refresh++
        }

        HorizontalDivider()
        Text("Security checks", style = MaterialTheme.typography.titleSmall)
        remember(refreshKey) { securityWarnings(activity) }.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }

        HorizontalDivider()
        Text("Emergency", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = {
                AssistantRuntime.stopAll(activity, "lockdown")
                Category.entries.forEach { Gates.set(activity, it, false) }
                Features.setWakeWord(activity, false)
                Features.setAiCalls(activity, false)
                AutomationService.instance?.disableSelf()
                NotificationsService.instance?.requestUnbind()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.revokeSelfPermissionsOnKill(runtimePermissions.map { it.second })
                    message = "Lockdown on. Runtime permissions turn off when the app closes. Remove notification and battery access in Android Settings."
                } else {
                    openAppInfo(activity)
                    message = "Lockdown on. The assistant and all tools are off. Android 12 needs you to remove permissions here in App info."
                }
                refresh++
            },
            Modifier.fillMaxWidth(),
        ) { Text("Lockdown: stop and revoke everything") }
        OutlinedButton(
            onClick = {
                val (entries, intact) = Audit.view(activity)
                message = (if (intact) "Audit log intact\n" else "WARNING: audit log failed its check\n") + entries.take(15).joinToString("\n")
            },
            Modifier.fillMaxWidth(),
        ) { Text("View audit log") }
        OutlinedButton(
            onClick = {
                activity.authenticate("Delete all assistant data", "This can't be undone") { ok ->
                    if (ok) {
                        DataWipe.deleteEverything(activity)
                        message = "All assistant data was deleted"
                        refresh++
                    }
                }
            },
            Modifier.fillMaxWidth(),
        ) { Text("Delete all assistant data") }
        }
    }
}

private fun openAppInfo(activity: FragmentActivity) {
    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
}

private fun securityWarnings(activity: FragmentActivity): List<String> {
    val warnings = ArrayList<String>()
    if (!activity.getSystemService(KeyguardManager::class.java).isDeviceSecure) warnings += "Warning: set a screen lock"
    if (Settings.Global.getInt(activity.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) warnings += "Warning: USB debugging is on"
    val services = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val others = services.split(':').filter { it.isNotBlank() && !it.startsWith(activity.packageName) }
    if (others.isNotEmpty()) warnings += "Check these other accessibility services: ${others.joinToString { it.substringBefore('/') }}"
    warnings += "Android security patch: ${Build.VERSION.SECURITY_PATCH}"
    return warnings
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StateRow(title: String, state: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(state, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}
