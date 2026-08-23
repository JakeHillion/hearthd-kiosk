package dev.hearthd.android.kiosk.ui

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearthd.android.kiosk.BuildConfig
import dev.hearthd.android.kiosk.R
import dev.hearthd.android.kiosk.audio.AudioPolicy
import dev.hearthd.android.kiosk.dashboard.DashboardController
import dev.hearthd.android.kiosk.dashboard.DashboardStatus
import dev.hearthd.android.kiosk.dashboard.DashboardUiState
import dev.hearthd.android.kiosk.settings.AudioSettings
import dev.hearthd.android.kiosk.settings.Channel
import dev.hearthd.android.kiosk.settings.DashboardSettings
import dev.hearthd.android.kiosk.settings.HearthdSettings
import dev.hearthd.android.kiosk.settings.INTERVAL_STOPS
import dev.hearthd.android.kiosk.settings.ManagedSettingsController
import dev.hearthd.android.kiosk.settings.ManagedStatus
import dev.hearthd.android.kiosk.settings.ManagedUiState
import dev.hearthd.android.kiosk.settings.SettingsRepository
import dev.hearthd.android.kiosk.settings.SnapcastSettings
import dev.hearthd.android.kiosk.snapcast.SnapcastController
import dev.hearthd.android.kiosk.snapcast.SnapcastStatus
import dev.hearthd.android.kiosk.snapcast.SnapcastUiState
import dev.hearthd.android.kiosk.settings.THRESHOLD_RANGE
import dev.hearthd.android.kiosk.settings.UpdateSettings
import dev.hearthd.android.kiosk.settings.VoiceSettings
import dev.hearthd.android.kiosk.settings.WakeWordSettings
import dev.hearthd.android.kiosk.settings.formatInterval
import dev.hearthd.android.kiosk.update.UpdateController
import dev.hearthd.android.kiosk.update.UpdateStatus
import dev.hearthd.android.kiosk.update.UpdateUiState
import dev.hearthd.android.kiosk.wakeword.WakeWordDetector
import dev.hearthd.android.kiosk.wakeword.WakeWordModel
import dev.hearthd.android.kiosk.wakeword.WakeWordStatus
import dev.hearthd.android.kiosk.wakeword.WakeWordUiState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt

/** Sections shown in the settings navigation rail. */
private enum class SettingsSection { MANAGED, DISPLAY, AUDIO, UPDATES, ASSISTANT, DEVICE_INFO, SENSORS }

/** Root settings screen: a navigation rail with sections. */
@Composable
fun SettingsScreen(
    settingsRepo: SettingsRepository,
    controller: UpdateController,
    wakeWord: WakeWordDetector,
    dashboard: DashboardController,
    snapcast: SnapcastController,
    audioPolicy: AudioPolicy,
    managed: ManagedSettingsController,
    onRequestMicPermission: () -> Unit,
    onTestVoice: suspend (VoiceSettings) -> String,
    onClose: () -> Unit,
) {
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = UpdateSettings())
    val ui by controller.state.collectAsStateWithLifecycle()
    val wakeSettings by settingsRepo.wakeWord.collectAsStateWithLifecycle(initialValue = WakeWordSettings())
    val wakeUi by wakeWord.state.collectAsStateWithLifecycle()
    val voiceSettings by settingsRepo.voice.collectAsStateWithLifecycle(initialValue = VoiceSettings())
    val dashboardSettings by settingsRepo.dashboard.collectAsStateWithLifecycle(initialValue = DashboardSettings())
    val dashboardUi by dashboard.state.collectAsStateWithLifecycle()
    val hearthdSettings by settingsRepo.hearthd.collectAsStateWithLifecycle(initialValue = HearthdSettings())
    val snapcastSettings by settingsRepo.snapcast.collectAsStateWithLifecycle(initialValue = SnapcastSettings())
    val snapcastUi by snapcast.state.collectAsStateWithLifecycle()
    val audioSettings by settingsRepo.audio.collectAsStateWithLifecycle(initialValue = AudioSettings())
    val musicVolume by audioPolicy.music.collectAsStateWithLifecycle()
    val musicMuted by audioPolicy.muted.collectAsStateWithLifecycle()
    val managedEnabled by settingsRepo.managedEnabled.collectAsStateWithLifecycle(initialValue = false)
    val managedUi by managed.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(SettingsSection.DISPLAY) }

    // With template control on, the other panes are template-driven and render
    // read-only — except the dashboard State URL, which is the template's own
    // address (see the `locked` flag threaded into each pane).
    val locked = managedEnabled

    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail {
            // Back to the kiosk surface. With the system bars hidden in kiosk
            // mode this is the way out of settings.
            NavigationRailItem(
                selected = false,
                onClick = onClose,
                icon = { Text("←") },
                label = { Text(stringResource(R.string.settings_close)) },
            )
            NavigationRailItem(
                selected = section == SettingsSection.MANAGED,
                onClick = { section = SettingsSection.MANAGED },
                icon = { Text("☁") },
                label = { Text(stringResource(R.string.settings_managed)) },
            )
            NavigationRailItem(
                selected = section == SettingsSection.DISPLAY,
                onClick = { section = SettingsSection.DISPLAY },
                icon = { Text("▦") },
                label = { Text(stringResource(R.string.settings_display)) },
            )
            NavigationRailItem(
                selected = section == SettingsSection.AUDIO,
                onClick = { section = SettingsSection.AUDIO },
                icon = { Text("🔊") },
                label = { Text(stringResource(R.string.settings_audio)) },
            )
            NavigationRailItem(
                selected = section == SettingsSection.UPDATES,
                onClick = { section = SettingsSection.UPDATES },
                icon = { Text("↻") },
                label = { Text(stringResource(R.string.settings_updates)) },
            )
            NavigationRailItem(
                selected = section == SettingsSection.ASSISTANT,
                onClick = { section = SettingsSection.ASSISTANT },
                icon = { Text("🎙") },
                label = { Text(stringResource(R.string.settings_assistant)) },
            )
            NavigationRailItem(
                selected = section == SettingsSection.DEVICE_INFO,
                onClick = { section = SettingsSection.DEVICE_INFO },
                icon = { Text("ⓘ") },
                label = { Text(stringResource(R.string.settings_device_info)) },
            )
            // Debug-only: sensor probe. Not user-facing polish — kept for
            // capturing what this hardware exposes (esp. proximity, which we
            // want to drive display-off in an empty room).
            NavigationRailItem(
                selected = section == SettingsSection.SENSORS,
                onClick = { section = SettingsSection.SENSORS },
                icon = { Text("◎") },
                label = { Text("Sensors") },
            )
        }
        when (section) {
            SettingsSection.MANAGED -> ManagedPane(
                enabled = managedEnabled,
                stateUrl = dashboardSettings.stateUrl,
                ui = managedUi,
                onEnabledChange = { scope.launch { settingsRepo.setManagedEnabled(it) } },
                onStateUrlChange = { scope.launch { settingsRepo.setDashboardStateUrl(it) } },
            )
            SettingsSection.DISPLAY -> DisplayPane(
                settings = dashboardSettings,
                ui = dashboardUi,
                hearthdSettings = hearthdSettings,
                locked = locked,
                onEnabledChange = { scope.launch { settingsRepo.setDashboardEnabled(it) } },
                onStateUrlChange = { scope.launch { settingsRepo.setDashboardStateUrl(it) } },
                onRefreshNow = { url -> scope.launch { dashboard.poll(url) } },
                onHearthdEnabledChange = { scope.launch { settingsRepo.setHearthdEnabled(it) } },
                onHearthdBaseUrlChange = { scope.launch { settingsRepo.setHearthdBaseUrl(it) } },
            )
            SettingsSection.AUDIO -> AudioPane(
                settings = snapcastSettings,
                ui = snapcastUi,
                audio = audioSettings,
                musicVolume = musicVolume,
                musicMuted = musicMuted,
                locked = locked,
                onEnabledChange = { scope.launch { settingsRepo.setSnapcastEnabled(it) } },
                onHostChange = { scope.launch { settingsRepo.setSnapcastHost(it) } },
                onPortChange = { scope.launch { settingsRepo.setSnapcastPort(it) } },
                onControlPortChange = { scope.launch { settingsRepo.setSnapcastControlPort(it) } },
                onAssistantVolumeChange = { scope.launch { settingsRepo.setAudioAssistantVolume(it) } },
                onDuckPercentChange = { scope.launch { settingsRepo.setAudioDuckPercent(it) } },
            )
            SettingsSection.UPDATES -> UpdatesPane(
                settings = settings,
                ui = ui,
                onEnabledChange = { scope.launch { settingsRepo.setEnabled(it) } },
                onChannelChange = { scope.launch { settingsRepo.setChannel(it) } },
                onIntervalChange = { scope.launch { settingsRepo.setIntervalMinutes(it) } },
                onCheckNow = { scope.launch { controller.check(settings.channel) } },
            )
            SettingsSection.ASSISTANT -> AssistantPane(
                wakeSettings = wakeSettings,
                wakeUi = wakeUi,
                voiceSettings = voiceSettings,
                locked = locked,
                onWakeEnabledChange = { scope.launch { settingsRepo.setWakeEnabled(it) } },
                onWakeModelChange = { scope.launch { settingsRepo.setWakeModel(it) } },
                onWakeThresholdChange = { scope.launch { settingsRepo.setWakeThreshold(it) } },
                onRequestPermission = onRequestMicPermission,
                onVoiceEnabledChange = { scope.launch { settingsRepo.setVoiceEnabled(it) } },
                onBaseUrlChange = { scope.launch { settingsRepo.setVoiceBaseUrl(it) } },
                onPipelineChange = { scope.launch { settingsRepo.setVoicePipeline(it) } },
                onTest = onTestVoice,
            )
            SettingsSection.DEVICE_INFO -> DeviceInfoPane()
            SettingsSection.SENSORS -> SensorsPane()
        }
    }
}

/**
 * Template control: the switch that turns the mode on and the template's address.
 * The address is the dashboard's `/state` URL — the one endpoint the device is
 * pointed at, whose template carries a top-level `settings` blob. Turning the
 * mode on makes that blob drive every other domain (those panes render read-only).
 */
@Composable
private fun ManagedPane(
    enabled: Boolean,
    stateUrl: String,
    ui: ManagedUiState,
    onEnabledChange: (Boolean) -> Unit,
    onStateUrlChange: (String) -> Unit,
) {
    // Local field state so typing doesn't fight DataStore round-trips; each edit
    // is still persisted immediately. This is the same setting as the Display
    // State URL, so mirror its persisted value when not actively editing here.
    var url by rememberSaveable { mutableStateOf(stateUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.settings_managed), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // Opt-in toggle.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.managed_enable), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.managed_enable_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(24.dp))

        // The template address = the dashboard `/state` URL. Stays editable even
        // with the mode on: it's how the device reaches the template.
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; onStateUrlChange(it) },
            label = { Text(stringResource(R.string.managed_url)) },
            placeholder = { Text("https://home.example.com/state") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.managed_url_summary),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))

        // Live status of the poller — only meaningful once enabled.
        if (enabled) {
            Text(managedStatusLine(ui), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Banner shown atop every template-driven pane while template control is on. */
@Composable
private fun ManagedLockNotice() {
    Text(
        stringResource(R.string.managed_locked_notice),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DisplayPane(
    settings: DashboardSettings,
    ui: DashboardUiState,
    hearthdSettings: HearthdSettings,
    locked: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onStateUrlChange: (String) -> Unit,
    onRefreshNow: (String) -> Unit,
    onHearthdEnabledChange: (Boolean) -> Unit,
    onHearthdBaseUrlChange: (String) -> Unit,
) {
    // Local field state so typing doesn't fight DataStore round-trips; each edit
    // is still persisted immediately. When locked the fields are template-driven,
    // so show the effective value from the flow rather than the stale local buffer.
    var url by rememberSaveable { mutableStateOf(settings.stateUrl) }
    var hearthdUrl by rememberSaveable { mutableStateOf(hearthdSettings.baseUrl) }
    val shownUrl = if (locked) settings.stateUrl else url
    val shownHearthdUrl = if (locked) hearthdSettings.baseUrl else hearthdUrl

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.settings_display), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (locked) {
            ManagedLockNotice()
            Spacer(Modifier.height(20.dp))
        }

        // Opt-in toggle.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dashboard_enable), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.dashboard_enable_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = settings.enabled, onCheckedChange = onEnabledChange, enabled = !locked)
        }
        Spacer(Modifier.height(24.dp))

        // The /state endpoint. The template endpoint is derived from it, so the
        // operator only ever configures this one URL.
        OutlinedTextField(
            value = shownUrl,
            onValueChange = { url = it; onStateUrlChange(it) },
            label = { Text(stringResource(R.string.dashboard_state_url)) },
            placeholder = { Text("https://home.example.com/state") },
            singleLine = true,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        // Live status of the poller.
        Text(dashboardStatusLine(ui), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = { onRefreshNow(shownUrl) }, enabled = !locked && shownUrl.isNotBlank()) {
            Text(stringResource(R.string.dashboard_refresh_now))
        }

        // ── Light control (hearthd) ───────────────────────────────────────
        // The dashboard's /state feeds light state into the UI; this is the
        // separate write path — where to send toggle/brightness commands.
        Spacer(Modifier.height(40.dp))
        SectionHeading(stringResource(R.string.settings_light_control))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.hearthd_enable), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.hearthd_enable_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = hearthdSettings.enabled, onCheckedChange = onHearthdEnabledChange, enabled = !locked)
        }
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = shownHearthdUrl,
            onValueChange = { hearthdUrl = it; onHearthdBaseUrlChange(it) },
            label = { Text(stringResource(R.string.hearthd_base_url)) },
            placeholder = { Text("https://hearthd.example.com") },
            singleLine = true,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AudioPane(
    settings: SnapcastSettings,
    ui: SnapcastUiState,
    audio: AudioSettings,
    musicVolume: Int,
    musicMuted: Boolean,
    locked: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (Int) -> Unit,
    onControlPortChange: (Int) -> Unit,
    onAssistantVolumeChange: (Int) -> Unit,
    onDuckPercentChange: (Int) -> Unit,
) {
    // Local field state so typing doesn't fight DataStore round-trips; each valid
    // edit is still persisted immediately. When locked the fields are
    // template-driven, so show the effective value rather than the local buffer.
    var host by rememberSaveable { mutableStateOf(settings.host) }
    var port by rememberSaveable { mutableStateOf(settings.port.toString()) }
    var controlPort by rememberSaveable { mutableStateOf(settings.controlPort.toString()) }
    val shownHost = if (locked) settings.host else host
    val shownPort = if (locked) settings.port.toString() else port
    val shownControlPort = if (locked) settings.controlPort.toString() else controlPort

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.settings_audio), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (locked) {
            ManagedLockNotice()
            Spacer(Modifier.height(20.dp))
        }

        // Opt-in toggle.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.snapcast_enable), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.snapcast_enable_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = settings.enabled, onCheckedChange = onEnabledChange, enabled = !locked)
        }
        Spacer(Modifier.height(24.dp))

        // Server host + port. Nothing connects until a host is set.
        OutlinedTextField(
            value = shownHost,
            onValueChange = { host = it; onHostChange(it) },
            label = { Text(stringResource(R.string.snapcast_host)) },
            placeholder = { Text("192.168.1.10") },
            singleLine = true,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = shownPort,
            onValueChange = { new ->
                // Keep only digits; persist when it's a plausible port.
                port = new.filter { it.isDigit() }.take(5)
                port.toIntOrNull()?.let { if (it in 1..65535) onPortChange(it) }
            },
            label = { Text(stringResource(R.string.snapcast_port)) },
            placeholder = { Text(SnapcastSettings.DEFAULT_PORT.toString()) },
            singleLine = true,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = shownControlPort,
            onValueChange = { new ->
                controlPort = new.filter { it.isDigit() }.take(5)
                controlPort.toIntOrNull()?.let { if (it in 1..65535) onControlPortChange(it) }
            },
            label = { Text(stringResource(R.string.snapcast_control_port)) },
            placeholder = { Text(SnapcastSettings.DEFAULT_CONTROL_PORT.toString()) },
            singleLine = true,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        // Live status of the client subprocess.
        Text(snapcastStatusLine(ui), style = MaterialTheme.typography.bodyMedium)
        ui.lastLine?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.snapcast_note),
            style = MaterialTheme.typography.bodySmall,
        )

        // ── Volume ────────────────────────────────────────────────────────
        // Music volume is live (server- or key-driven), shown read-only here; the
        // assistant's loudness and the duck depth are the stored/template settings.
        Spacer(Modifier.height(40.dp))
        SectionHeading(stringResource(R.string.settings_volume))

        val musicLine = if (musicMuted) {
            stringResource(R.string.volume_music_muted)
        } else {
            stringResource(R.string.volume_music_value, musicVolume)
        }
        Text(musicLine, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.volume_music_summary),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(R.string.volume_assistant, audio.assistantVolume),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = audio.assistantVolume.toFloat(),
            onValueChange = { onAssistantVolumeChange(it.roundToInt()) },
            valueRange = 0f..100f,
            enabled = !locked,
        )
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.volume_duck, audio.duckPercent),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.volume_duck_summary),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = audio.duckPercent.toFloat(),
            onValueChange = { onDuckPercentChange(it.roundToInt()) },
            valueRange = 0f..100f,
            enabled = !locked,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesPane(
    settings: UpdateSettings,
    ui: UpdateUiState,
    onEnabledChange: (Boolean) -> Unit,
    onChannelChange: (Channel) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onCheckNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // Opt-in toggle.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.update_auto), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.update_auto_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = settings.enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(24.dp))

        // Channel.
        Text(stringResource(R.string.update_channel), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Channel.entries.forEach { channel ->
                FilterChip(
                    selected = settings.channel == channel,
                    onClick = { onChannelChange(channel) },
                    label = { Text(channel.label) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        // Interval slider over the discrete stops.
        Text(
            "${stringResource(R.string.update_interval)} ${formatInterval(settings.intervalMinutes)}",
            style = MaterialTheme.typography.titleMedium,
        )
        val index = INTERVAL_STOPS.indexOf(settings.intervalMinutes).coerceAtLeast(0)
        Slider(
            value = index.toFloat(),
            onValueChange = {
                onIntervalChange(INTERVAL_STOPS[it.roundToInt().coerceIn(0, INTERVAL_STOPS.lastIndex)])
            },
            valueRange = 0f..INTERVAL_STOPS.lastIndex.toFloat(),
            steps = (INTERVAL_STOPS.size - 2).coerceAtLeast(0),
        )
        Spacer(Modifier.height(24.dp))

        // Current build + last check + status.
        Text(
            stringResource(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(statusLine(ui), style = MaterialTheme.typography.bodySmall)
        if (!ui.deviceOwner) {
            Text(
                "Installs need on-screen confirmation. Set device owner via ADB for silent updates.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = onCheckNow) {
            Text(stringResource(R.string.update_check_now))
        }
    }
}

/**
 * The Assistant section: one scrolling pane with a permanent "Wake word"
 * subsection on top and the Alpha "Home Assistant" voice integration below it.
 * They're grouped because the wake word is what triggers a voice request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantPane(
    wakeSettings: WakeWordSettings,
    wakeUi: WakeWordUiState,
    voiceSettings: VoiceSettings,
    locked: Boolean,
    onWakeEnabledChange: (Boolean) -> Unit,
    onWakeModelChange: (WakeWordModel) -> Unit,
    onWakeThresholdChange: (Float) -> Unit,
    onRequestPermission: () -> Unit,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onPipelineChange: (String) -> Unit,
    onTest: suspend (VoiceSettings) -> String,
) {
    val scope = rememberCoroutineScope()
    // Local field state so typing doesn't fight DataStore round-trips; each edit
    // is still persisted immediately. When locked the fields are template-driven,
    // so show the effective value rather than the local buffer.
    var url by rememberSaveable { mutableStateOf(voiceSettings.baseUrl) }
    var pipeline by rememberSaveable { mutableStateOf(voiceSettings.pipelineId) }
    var testStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var testing by rememberSaveable { mutableStateOf(false) }
    val shownUrl = if (locked) voiceSettings.baseUrl else url
    val shownPipeline = if (locked) voiceSettings.pipelineId else pipeline

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        if (locked) {
            ManagedLockNotice()
            Spacer(Modifier.height(20.dp))
        }

        // ── Wake word (here to stay) ──────────────────────────────────────
        SectionHeading("🎤  ${stringResource(R.string.settings_wake_word)}")

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.wake_enable), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.wake_enable_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = wakeSettings.enabled, onCheckedChange = onWakeEnabledChange, enabled = !locked)
        }
        Spacer(Modifier.height(24.dp))

        // Permission prompt, shown only when enabled but the mic isn't granted.
        if (wakeUi.status == WakeWordStatus.NO_PERMISSION) {
            Text(
                stringResource(R.string.wake_permission_needed),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRequestPermission) {
                Text(stringResource(R.string.wake_grant_permission))
            }
            Spacer(Modifier.height(24.dp))
        }

        // Wake-word model.
        Text(stringResource(R.string.wake_model), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WakeWordModel.entries.forEach { model ->
                FilterChip(
                    selected = wakeSettings.model == model,
                    onClick = { onWakeModelChange(model) },
                    label = { Text(model.label) },
                    enabled = !locked,
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        // Sensitivity (detection threshold).
        Text(
            "${stringResource(R.string.wake_sensitivity)} ${"%.2f".format(wakeSettings.threshold)}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.wake_sensitivity_summary),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = wakeSettings.threshold,
            onValueChange = onWakeThresholdChange,
            valueRange = THRESHOLD_RANGE,
            enabled = !locked,
        )
        Spacer(Modifier.height(24.dp))

        // Live status: what the detector is doing plus the current score.
        Text(wakeStatusLine(wakeUi), style = MaterialTheme.typography.bodyMedium)
        if (wakeUi.status == WakeWordStatus.LISTENING) {
            Text(
                "Score: ${"%.2f".format(wakeUi.lastScore)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        wakeUi.lastDetectionEpochMs?.let {
            Text(
                "Last detected ${DateUtils.getRelativeTimeSpanString(it)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ── Home Assistant (Alpha) ────────────────────────────────────────
        Spacer(Modifier.height(40.dp))
        SectionHeading("🏠  Home Assistant (Alpha)")
        Text(
            "This behaviour is likely to be removed in a future release.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))

        // Opt-in toggle.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Voice assistant", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Off by default. Uses the wake word above to start each request.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = voiceSettings.enabled, onCheckedChange = onVoiceEnabledChange, enabled = !locked)
        }
        if (voiceSettings.enabled && !wakeSettings.enabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Turn on the wake word above too — it's what starts a request.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(24.dp))

        // Home Assistant location.
        OutlinedTextField(
            value = shownUrl,
            onValueChange = { url = it; onBaseUrlChange(it) },
            label = { Text("Home Assistant URL") },
            placeholder = { Text("https://homeassistant.example.com") },
            singleLine = true,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = shownPipeline,
            onValueChange = { pipeline = it; onPipelineChange(it) },
            label = { Text("Pipeline ID (optional)") },
            singleLine = true,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Leave blank to use Home Assistant's preferred pipeline.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedButton(
            onClick = {
                scope.launch {
                    testing = true
                    testStatus = null
                    testStatus = onTest(voiceSettings.copy(baseUrl = shownUrl, pipelineId = shownPipeline))
                    testing = false
                }
            },
            enabled = !locked && !testing && shownUrl.isNotBlank(),
        ) {
            Text(if (testing) "Testing…" else "Test connection")
        }
        testStatus?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Sign-in uses Home Assistant's trusted_networks provider — no token to " +
                "enter; the kiosk is trusted by its network address.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** A body heading that separates the scrolling subsections of a settings pane. */
@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun DeviceInfoPane() {
    // The licenses page lives under Device (the "About" section), the way most
    // apps nest their open-source notices. A local flag swaps it in and back.
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    if (showLicenses) {
        LicensesPane(onBack = { showLicenses = false })
        return
    }

    val context = LocalContext.current
    // Screen geometry, straight off the display the app is running on. In the
    // kiosk we're always full-screen, so these pixels are the panel's pixels.
    val metrics = context.resources.displayMetrics
    // Physical dots-per-inch reported by the panel, not the density bucket.
    // x and y are near-identical on real hardware; average and round to one.
    val physicalDpi = ((metrics.xdpi + metrics.ydpi) / 2f).roundToInt()
    val displayValue = stringResource(
        R.string.device_display_value,
        metrics.widthPixels,
        metrics.heightPixels,
        physicalDpi,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.settings_device_info), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        InfoRow(stringResource(R.string.device_model), "${Build.MANUFACTURER} ${Build.MODEL}")
        InfoRow(stringResource(R.string.device_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        InfoRow(stringResource(R.string.device_display), displayValue)
        InfoRow(
            stringResource(R.string.device_app_version),
            stringResource(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        )
        Spacer(Modifier.height(24.dp))

        // Open-source notices.
        Text(
            stringResource(R.string.licenses_summary),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showLicenses = true }) {
            Text(stringResource(R.string.settings_licenses))
        }
        Spacer(Modifier.height(24.dp))

        // Escape hatch out of the kiosk into the native Settings app — the only
        // way to reach Developer options and re-enable ADB on a fresh machine.
        Text(
            stringResource(R.string.device_settings_summary),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }) {
            Text(stringResource(R.string.device_open_settings))
        }
    }
}

/**
 * Open-source licenses / attribution — the "third-party software" page every
 * app tucks under About. One block per component, showing its copyright and
 * license; the notices are legal text so they stay verbatim in code, not in
 * translatable resources.
 */
@Composable
private fun LicensesPane(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text("←  ${stringResource(R.string.licenses_back)}")
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium)

        val resources = LocalContext.current.resources
        THIRD_PARTY_LICENSES.forEach { entry ->
            Spacer(Modifier.height(24.dp))
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            Text(entry.copyright, style = MaterialTheme.typography.bodySmall)
            Text(
                entry.license,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(entry.notice, style = MaterialTheme.typography.bodySmall)
            entry.fullTextRes?.let { res ->
                val fullText = remember(res) {
                    resources.openRawResource(res).bufferedReader().use { it.readText() }
                }
                Spacer(Modifier.height(8.dp))
                Text(fullText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Debug sensor probe. Lists every sensor the platform exposes and shows live
 * readings for proximity and ambient light. The proximity listener is the same
 * plumbing we'd use to switch the display off in an empty room — this pane is
 * where we confirm the sensor exists and see what it actually reports.
 */
@Composable
private fun SensorsPane() {
    val context = LocalContext.current
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val all = remember { sm.getSensorList(Sensor.TYPE_ALL) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Sensors (debug)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("Live readings", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LiveSensorRow(sm, Sensor.TYPE_PROXIMITY, "Proximity")
        LiveSensorRow(sm, Sensor.TYPE_LIGHT, "Ambient light")
        Spacer(Modifier.height(24.dp))

        Text("All sensors (${all.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (all.isEmpty()) {
            Text("No sensors reported by this device.", style = MaterialTheme.typography.bodySmall)
        }
        all.forEach { s ->
            Text(
                "${s.name}\n  type=${s.type} vendor=${s.vendor} max=${s.maximumRange} res=${s.resolution}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * Registers a listener on [type]'s default sensor while composed and renders its
 * latest value. Unregisters on dispose so we don't hold the sensor open once the
 * pane leaves the screen.
 */
@Composable
private fun LiveSensorRow(sm: SensorManager, type: Int, label: String) {
    val sensor = remember(type) { sm.getDefaultSensor(type) }
    var values by remember(type) { mutableStateOf<FloatArray?>(null) }

    DisposableEffect(sensor) {
        if (sensor == null) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                values = event.values.copyOf()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    val text = when {
        sensor == null -> "not present"
        values == null -> "waiting…"
        else -> {
            val v = values!!
            val raw = v.joinToString(", ") { it.toString() }
            // Proximity is typically binary: values[0] < maxRange means "near".
            if (type == Sensor.TYPE_PROXIMITY && v.isNotEmpty()) {
                val near = v[0] < sensor.maximumRange
                "$raw  (${if (near) "NEAR" else "FAR"}, max ${sensor.maximumRange})"
            } else {
                "$raw  (max ${sensor.maximumRange})"
            }
        }
    }
    InfoRow(label, text)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun statusLine(ui: UpdateUiState): String {
    val status = when (ui.status) {
        UpdateStatus.IDLE -> "Idle"
        UpdateStatus.CHECKING -> "Checking…"
        UpdateStatus.DOWNLOADING -> "Downloading…"
        UpdateStatus.INSTALLING -> "Installing…"
        UpdateStatus.UP_TO_DATE -> "Up to date"
        UpdateStatus.FAILED -> "Failed: ${ui.message ?: "unknown error"}"
    }
    val checked = ui.lastCheckedEpochMs?.let {
        " · checked ${DateUtils.getRelativeTimeSpanString(it)}"
    } ?: ""
    return status + checked
}

private fun dashboardStatusLine(ui: DashboardUiState): String {
    val status = when (ui.status) {
        DashboardStatus.IDLE -> "Idle"
        DashboardStatus.LOADING -> "Loading…"
        DashboardStatus.LIVE -> "Live · every ${ui.refreshIntervalSeconds}s"
        DashboardStatus.ERROR -> "Error: ${ui.message ?: "unknown error"}"
    }
    val updated = ui.lastUpdatedEpochMs?.let {
        " · updated ${DateUtils.getRelativeTimeSpanString(it)}"
    } ?: ""
    return status + updated
}

private fun managedStatusLine(ui: ManagedUiState): String {
    val status = when (ui.status) {
        ManagedStatus.IDLE -> "Idle"
        ManagedStatus.LOADING -> "Loading…"
        ManagedStatus.LIVE -> "Live · every ${ui.refreshIntervalSeconds}s"
        ManagedStatus.ERROR -> "Error: ${ui.message ?: "unknown error"}"
    }
    val updated = ui.lastUpdatedEpochMs?.let {
        " · updated ${DateUtils.getRelativeTimeSpanString(it)}"
    } ?: ""
    return status + updated
}

private fun snapcastStatusLine(ui: SnapcastUiState): String = when (ui.status) {
    SnapcastStatus.DISABLED -> "Disabled"
    SnapcastStatus.STARTING -> "Starting… (${ui.server})"
    SnapcastStatus.RUNNING -> "Playing from ${ui.server}"
    SnapcastStatus.ERROR -> "Error: ${ui.message ?: "unknown error"}"
}

private fun wakeStatusLine(ui: WakeWordUiState): String = when (ui.status) {
    WakeWordStatus.DISABLED -> "Disabled"
    WakeWordStatus.NO_PERMISSION -> "Microphone permission needed"
    WakeWordStatus.STARTING -> "Starting…"
    WakeWordStatus.LISTENING -> "Listening for \"${ui.model?.label ?: ""}\""
    WakeWordStatus.ERROR -> "Error: ${ui.message ?: "unknown error"}"
}

/** One third-party component shown on the licenses page. */
private data class LicenseEntry(
    val name: String,
    val copyright: String,
    val license: String,
    val notice: String,
    // Raw resource holding the component's full license text, rendered verbatim
    // below the notice. Used for copyleft licenses (GPL) that require a complete
    // copy to travel with the binary, not just a summary.
    val fullTextRes: Int? = null,
)

/**
 * Third-party components bundled into the app. Two are not permissive: the
 * openWakeWord models (CC BY-NC-SA 4.0, used unmodified and non-commercially) and
 * the Snapcast client (GPL-3.0-or-later). The Snapcast binary runs as a separate
 * subprocess and ships with its full license text and Corresponding Source, so
 * the copyleft stays contained to that binary and doesn't reach the app.
 */
private val THIRD_PARTY_LICENSES = listOf(
    LicenseEntry(
        name = "openWakeWord — wake-word models",
        copyright = "© David Scripka and the openWakeWord contributors",
        license = "CC BY-NC-SA 4.0",
        notice = "The bundled melspectrogram, embedding, and wake-word models are " +
            "distributed under the Creative Commons Attribution-NonCommercial-" +
            "ShareAlike 4.0 International license, used unmodified for non-commercial, " +
            "on-device detection. The shared speech-embedding backbone derives from " +
            "Google's speech_embedding model (Apache-2.0). openWakeWord's own source " +
            "code is Apache-2.0; the detection pipeline here is an independent " +
            "reimplementation.\n\n" +
            "License: https://creativecommons.org/licenses/by-nc-sa/4.0/\n" +
            "Project: https://github.com/dscripka/openWakeWord",
    ),
    LicenseEntry(
        name = "ONNX Runtime",
        copyright = "© Microsoft Corporation",
        license = "MIT License",
        notice = "Permission is hereby granted, free of charge, to any person obtaining " +
            "a copy of this software and associated documentation files (the " +
            "\"Software\"), to deal in the Software without restriction, including " +
            "without limitation the rights to use, copy, modify, merge, publish, " +
            "distribute, sublicense, and/or sell copies of the Software, and to permit " +
            "persons to whom the Software is furnished to do so, subject to the " +
            "following conditions:\n\n" +
            "The above copyright notice and this permission notice shall be included in " +
            "all copies or substantial portions of the Software.\n\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
            "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, " +
            "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
            "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER " +
            "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING " +
            "FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER " +
            "DEALINGS IN THE SOFTWARE.",
    ),
    LicenseEntry(
        name = "Snapcast (snapclient)",
        copyright = "© 2014–2025 Johannes Pohl and the Snapcast contributors",
        license = "GPL-3.0-or-later",
        notice = "The Snapcast client is bundled as a native binary and run as a " +
            "separate subprocess for multi-room audio. It is free software: you may " +
            "redistribute it and/or modify it under the terms of the GNU General " +
            "Public License, version 3, or (at your option) any later version, with " +
            "NO WARRANTY.\n\n" +
            "It is cross-built unmodified from the upstream source pinned in this " +
            "app's Nix build (see snapclient-android.nix and flake.nix); that pinned " +
            "source together with those build scripts is its Corresponding Source, " +
            "and this app's build is publicly reproducible from it.\n\n" +
            "Upstream project: https://github.com/snapcast/snapcast\n\n" +
            "The complete license text follows.",
        fullTextRes = R.raw.gpl_3_0,
    ),
    LicenseEntry(
        name = "Snapcast client — bundled libraries",
        copyright = "© the Xiph.Org Foundation, Google LLC, the libsoxr authors, " +
            "and the Boost authors",
        license = "BSD-3-Clause · Apache-2.0 · LGPL-2.1-or-later · BSL-1.0",
        notice = "The snapclient binary statically links several open-source " +
            "libraries:\n\n" +
            "• FLAC, libogg, Opus, and the Tremor Vorbis decoder — BSD-3-Clause " +
            "(© the Xiph.Org Foundation)\n" +
            "• oboe — Apache License 2.0 (© Google LLC)\n" +
            "• libsoxr (SoX Resampler) — LGPL-2.1-or-later\n" +
            "• Boost (Asio, header-only) — Boost Software License 1.0\n\n" +
            "Each library's own source and license travels in the reproducible " +
            "Corresponding Source described above. Because that complete source is " +
            "available, the LGPL relink requirement for libsoxr is satisfied even " +
            "though it is statically linked.",
    ),
    LicenseEntry(
        name = "Jetpack Compose & AndroidX",
        copyright = "© The Android Open Source Project",
        license = "Apache License 2.0",
        notice = "Licensed under the Apache License, Version 2.0.\n" +
            "License: https://www.apache.org/licenses/LICENSE-2.0",
    ),
    LicenseEntry(
        name = "Kotlin & kotlinx.coroutines",
        copyright = "© JetBrains s.r.o. and the Kotlin contributors",
        license = "Apache License 2.0",
        notice = "Licensed under the Apache License, Version 2.0.\n" +
            "License: https://www.apache.org/licenses/LICENSE-2.0",
    ),
)
