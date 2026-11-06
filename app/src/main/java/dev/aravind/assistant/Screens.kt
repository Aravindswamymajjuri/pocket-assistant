package dev.aravind.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.app.KeyguardManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.aravind.assistant.policy.Category
import kotlinx.coroutines.launch

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
        setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.statusBarsPadding())
                    Text("Confirm this action", style = MaterialTheme.typography.headlineSmall)
                    Text(readback, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { approve(readback) }, Modifier.fillMaxWidth()) { Text("Approve") }
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
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectWindow()
        setContent { AppTheme { HomeScreen(this) } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

private val runtimePermissions = listOf(
    "Microphone" to Manifest.permission.RECORD_AUDIO,
    "Notifications" to Manifest.permission.POST_NOTIFICATIONS,
    "Contacts" to Manifest.permission.READ_CONTACTS,
    "Phone calls" to Manifest.permission.CALL_PHONE,
    "SMS" to Manifest.permission.SEND_SMS,
)

private val categoryNames = mapOf(
    Category.APPS to "Open apps",
    Category.SYSTEM to "Alarms, flashlight, volume, lock",
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
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val refreshKey = refresh

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val sha = ModelStore.import(activity, uri) { bytes -> message = "Copying model: ${bytes / (1 shl 20)} MB" }
                    message = "Model imported. SHA-256: ${sha.take(16)}..."
                }.onFailure { message = "Model import failed" }
                refresh++
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pocket Assistant", style = MaterialTheme.typography.headlineMedium)

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
                            activity.authenticate("Turn on assistant", "Confirm it's you") { ok ->
                                if (ok) AssistantRuntime.arm(activity)
                            }
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
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)

        Section("Control Center")
        Text("Tool switches", style = MaterialTheme.typography.titleSmall)
        Category.entries.forEach { category ->
            val enabled = remember(refreshKey) { Gates.enabled(activity, category) }
            ToggleRow(categoryNames.getValue(category), null, enabled) { on ->
                if (!on) {
                    Gates.set(activity, category, false)
                    refresh++
                } else {
                    activity.authenticate("Turn on tool", categoryNames.getValue(category)) { ok ->
                        if (ok) Gates.set(activity, category, true)
                        refresh++
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Android permissions", style = MaterialTheme.typography.titleSmall)
        runtimePermissions.forEach { (label, permission) ->
            val granted = remember(refreshKey) { activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
            StateRow(label, if (granted) "On" else "Off", if (granted) "Revoke" else "Allow") {
                if (granted) {
                    activity.revokeSelfPermissionOnKill(permission)
                    message = "$label will turn off when the app closes. Use Lockdown or close it from Recents."
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
        Button(onClick = { refresh++ }, Modifier.fillMaxWidth()) { Text("Refresh status") }

        HorizontalDivider()
        Text("Gemma model", style = MaterialTheme.typography.titleSmall)
        val modelHash = remember(refreshKey) { ModelStore.hash(activity) }
        StateRow("Local model", modelHash?.let { "Imported: ${it.take(12)}..." } ?: "Not imported", "Import file") {
            modelPicker.launch(arrayOf("*/*"))
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
                AutomationService.instance?.disableSelf()
                NotificationsService.instance?.requestUnbind()
                activity.revokeSelfPermissionsOnKill(runtimePermissions.map { it.second })
                message = "Lockdown on. Runtime permissions turn off when the app closes. Remove notification and battery access in Android Settings."
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
