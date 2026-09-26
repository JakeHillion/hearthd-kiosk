package dev.hearthd.android.kiosk

import android.Manifest
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.hearthd.android.kiosk.dashboard.LightController
import dev.hearthd.android.kiosk.dashboard.LocalLightCommander
import dev.hearthd.android.kiosk.nowplaying.LocalNowPlaying
import dev.hearthd.android.kiosk.nowplaying.NowPlayingService
import dev.hearthd.android.kiosk.settings.HearthdSettings
import dev.hearthd.android.kiosk.service.KioskService
import dev.hearthd.android.kiosk.settings.VoiceSettings
import dev.hearthd.android.kiosk.snapcast.SnapcastNowPlaying
import dev.hearthd.android.kiosk.ui.KioskScreen
import dev.hearthd.android.kiosk.ui.SettingsScreen
import dev.hearthd.android.kiosk.ui.theme.kioskTypography
import dev.hearthd.android.kiosk.ui.theme.robotoFlexFamily
import dev.hearthd.android.kiosk.update.UpdateController
import dev.hearthd.android.kiosk.voice.HomeAssistantAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    // The grant lands in onResume, which runs as the dialog closes and is where
    // both the permission and the service's hold on the mic are refreshed.
    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val app: KioskApp get() = application as KioskApp

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

        // Shared with KioskService, which keeps them running while the panel is
        // dark, so they must be the same instances the service drives.
        val settingsRepo = app.settings
        val dashboard = app.dashboard
        val managed = app.managed
        val snapcast = app.snapcast
        val volumeSync = app.volumeSync
        val wakeWord = app.wakeWord
        val voice = app.voice

        val controller = UpdateController(applicationContext)
        // What's playing, read from the same Snapcast server. Demand-driven
        // rather than run from the service like the client and volume sync: it
        // connects only while something on screen is actually showing a track,
        // so it needs no wiring here beyond being handed to the things that
        // read it.
        val snapcastNowPlaying =
            SnapcastNowPlaying(applicationContext, lifecycleScope, settingsRepo.snapcast)
        val nowPlaying = NowPlayingService(lifecycleScope, listOf(snapcastNowPlaying))
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
                            volumeSync = volumeSync,
                            nowPlaying = snapcastNowPlaying,
                            managed = managed,
                            onRequestMicPermission = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                            onTestVoice = ::testVoiceConnection,
                            onClose = { showSettings = false },
                        )
                    } else {
                        val voiceSettings by settingsRepo.voice
                            .collectAsStateWithLifecycle(initialValue = VoiceSettings())
                        CompositionLocalProvider(
                            LocalLightCommander provides lightCommander,
                            LocalNowPlaying provides nowPlaying,
                        ) {
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
        app.refreshMicPermission()
        // Started from here rather than onCreate so a fresh mic grant reaches
        // the service: a foreground service can only take up the microphone
        // while the app is visible, so this is the moment it can claim it.
        KioskService.start(this)
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

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
