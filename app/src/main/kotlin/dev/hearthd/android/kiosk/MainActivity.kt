package dev.hearthd.android.kiosk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.hearthd.android.kiosk.dashboard.DashboardController
import dev.hearthd.android.kiosk.dashboard.LightController
import dev.hearthd.android.kiosk.dashboard.LocalLightCommander
import dev.hearthd.android.kiosk.settings.HearthdSettings
import dev.hearthd.android.kiosk.settings.ManagedSettingsController
import dev.hearthd.android.kiosk.settings.SettingsRepository
import dev.hearthd.android.kiosk.settings.VoiceSettings
import dev.hearthd.android.kiosk.snapcast.SnapcastController
import dev.hearthd.android.kiosk.ui.KioskScreen
import dev.hearthd.android.kiosk.ui.SettingsScreen
import dev.hearthd.android.kiosk.ui.theme.kioskTypography
import dev.hearthd.android.kiosk.ui.theme.robotoFlexFamily
import dev.hearthd.android.kiosk.update.UpdateController
import dev.hearthd.android.kiosk.voice.HomeAssistantAssist
import dev.hearthd.android.kiosk.voice.HomeAssistantAuth
import dev.hearthd.android.kiosk.voice.VoiceController
import dev.hearthd.android.kiosk.wakeword.WakeWordDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    // Whether RECORD_AUDIO is currently granted. Re-checked in onResume so the
    // wake-word loop reacts as soon as the operator returns from the permission
    // dialog or the system settings.
    private val micPermission = MutableStateFlow(false)

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micPermission.value = granted
        }

    // Latest hearthd control settings, tracked so the light commander always
    // sends to the current URL (or drops the command when unconfigured).
    @Volatile
    private var hearthdSettings = HearthdSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kiosk: draw edge to edge and hide the status and navigation bars so
        // the native back/home bar never intrudes. Bars stay hidden until an
        // operator swipes from an edge, then auto-hide again.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        micPermission.value = hasMicPermission()

        val settingsRepo = SettingsRepository(applicationContext)
        val controller = UpdateController(applicationContext)
        val wakeWord = WakeWordDetector(applicationContext)
        val voice = VoiceController(lifecycleScope)
        val dashboard = DashboardController()
        val snapcast = SnapcastController(applicationContext)
        // Template control: polls a remote template and persists its `settings`
        // blob, which SettingsRepository overlays over the local domains.
        val managed = ManagedSettingsController(
            saveConfig = { settingsRepo.setManagedCache(it) },
        )
        // Light control (write path): commands go straight to hearthd, then nudge
        // a dashboard re-poll so the change is confirmed without waiting a cycle.
        val lightCommander = LightController(
            scope = lifecycleScope,
            settings = { hearthdSettings },
            onCommandSent = { dashboard.refreshNow() },
        )
        lifecycleScope.launch {
            settingsRepo.hearthd.collect { hearthdSettings = it }
        }

        // The update loop lives here, scoped to the foreground: it only runs
        // while the app is at least STARTED and the user has opted in. Off
        // screen or disabled, it does nothing and never touches the network.
        // collectLatest restarts the loop whenever settings change.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.settings.collectLatest { settings ->
                    if (!settings.enabled) return@collectLatest
                    while (true) {
                        controller.check(settings.channel)
                        delay(settings.intervalMinutes.toLong() * 60_000L)
                    }
                }
            }
        }

        // The wake-word loop, on the same foreground-only footing as updates:
        // the mic is opened only while opted in, permitted, and on screen.
        // collectLatest cancels the listening session (releasing the mic) the
        // instant settings or the permission change.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(settingsRepo.wakeWord, micPermission) { s, granted -> s to granted }
                    .collectLatest { (s, granted) ->
                        when {
                            !s.enabled -> wakeWord.markDisabled()
                            !granted -> wakeWord.markNoPermission()
                            else -> wakeWord.run(s.model, s.threshold)
                        }
                    }
            }
        }

        // The dashboard poll loop, on the same foreground-only, opt-in footing as
        // updates: it fetches nothing until enabled and a URL is set. The server
        // dictates the cadence (poll() returns the seconds to wait); collectLatest
        // restarts the loop on settings change, and clears the surface when off.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.dashboard.collectLatest { s ->
                    if (!s.enabled || !s.configured) {
                        dashboard.clear()
                        return@collectLatest
                    }
                    while (true) {
                        val waitSeconds = dashboard.poll(s.stateUrl)
                        delay(waitSeconds.toLong() * 1_000L)
                    }
                }
            }
        }

        // Template control: when enabled and the dashboard `/state` URL is set,
        // poll that same URL and persist the template's `settings` blob. The
        // dashboard URL is the address the device is pointed at, so it's the
        // template source too — no separate URL. Same foreground-only, opt-in,
        // collectLatest footing as the loops above; the server dictates the
        // cadence. Off, it resets status and the persisted blob is no longer
        // overlaid.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(settingsRepo.managedEnabled, settingsRepo.dashboard) { enabled, dash ->
                    enabled to dash.stateUrl
                }.collectLatest { (enabled, stateUrl) ->
                    if (!enabled || stateUrl.isBlank()) {
                        managed.clear()
                        return@collectLatest
                    }
                    while (true) {
                        val waitSeconds = managed.poll(stateUrl)
                        delay(waitSeconds.toLong() * 1_000L)
                    }
                }
            }
        }

        // Snapcast client: when enabled + configured, spawn the
        // bundled snapclient against the server. Foreground-only and opt-in like
        // the loops above — collectLatest tears the process down (releasing audio)
        // the instant settings change or the app leaves the screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.snapcast.collectLatest { s ->
                    if (!s.enabled || !s.configured) {
                        snapcast.markDisabled()
                        return@collectLatest
                    }
                    snapcast.run(s)
                }
            }
        }

        // Voice (Alpha): when enabled + configured, a wake-word detection starts
        // a Home Assistant turn, streaming the mic frames the detector publishes.
        // collectLatest rebuilds the assistant when the HA settings change.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.voice.collectLatest { v ->
                    if (!v.enabled || !v.configured) return@collectLatest
                    val assistant = HomeAssistantAssist(v.baseUrl, v.pipelineId)
                    wakeWord.events.collect {
                        voice.startTurn(assistant, wakeWord.audioFrames)
                    }
                }
            }
        }

        setContent {
            // Roboto Flex from assets, on a kiosk-scaled type scale (see
            // ui/theme/Typography.kt). Built once; falls back to the system font
            // if the asset wasn't staged (local builds without the Nix step).
            val assets = LocalContext.current.assets
            val typography = remember { kioskTypography(robotoFlexFamily(assets)) }
            MaterialTheme(typography = typography) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // The kiosk surface is the root; Settings is reachable from
                    // its swipe-up tray and returns here on close.
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    if (showSettings) {
                        SettingsScreen(
                            settingsRepo = settingsRepo,
                            controller = controller,
                            wakeWord = wakeWord,
                            dashboard = dashboard,
                            snapcast = snapcast,
                            managed = managed,
                            onRequestMicPermission = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                            onTestVoice = ::testVoiceConnection,
                            onClose = { showSettings = false },
                        )
                    } else {
                        val voiceSettings by settingsRepo.voice
                            .collectAsStateWithLifecycle(initialValue = VoiceSettings())
                        CompositionLocalProvider(LocalLightCommander provides lightCommander) {
                            KioskScreen(
                                detections = wakeWord.events,
                                voiceUi = voice.ui,
                                micLevel = voice.micLevel,
                                voiceEngaged = voiceSettings.enabled && voiceSettings.configured,
                                dashboard = dashboard.state,
                                onOpenSettings = { showSettings = true },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The permission may have changed while we were away (dialog, settings).
        micPermission.value = hasMicPermission()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system restores the bars after transient reveals, dialogs, or
        // returning to the foreground. Re-hide them whenever we regain focus.
        if (hasFocus) hideSystemBars()
    }

    /** Try to authenticate against HA (trusted_networks), returning a status line. */
    private suspend fun testVoiceConnection(settings: VoiceSettings): String =
        runCatching {
            HomeAssistantAuth(OkHttpClient(), settings.baseUrl).accessToken()
            "Connected — Home Assistant authorized this device"
        }.getOrElse { "Failed: ${it.message}" }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
