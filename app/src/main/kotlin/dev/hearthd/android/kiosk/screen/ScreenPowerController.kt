package dev.hearthd.android.kiosk.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import dev.hearthd.android.kiosk.dashboard.DashboardUiState
import dev.hearthd.android.kiosk.dashboard.resolveBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asStateFlow

/** Live status of remote display control, enough for the Display settings pane. */
data class ScreenPowerUiState(
    val adminActive: Boolean = false,
    val adminSupported: Boolean = true,
    val driving: Boolean = false,
    val desiredOn: Boolean? = null,
)

/**
 * Remote control of the display's on/off state.
 *
 * Lives with the service rather than the activity, because the interesting half
 * of its job happens with nothing on screen: having put the panel out, the thing
 * that decides to bring it back has to still be running. The activity's only
 * part is keeping the screen awake while [desiredOn] is true — a window flag,
 * and so necessarily its own.
 *
 * A request is only honoured while the operator has opted in; a device that has
 * never been told to accept remote screen control ignores the widget entirely.
 */
class ScreenPowerController(
    private val context: Context,
    private val dashboard: StateFlow<DashboardUiState>,
    private val enabled: Flow<Boolean>,
    val admin: DeviceAdmin = DeviceAdmin(context),
) : ScreenPowerHandle {

    private val request = MutableStateFlow<ScreenPowerRequest?>(null)

    // Set while the operator is in Settings. Without it a template that says
    // "off" would black out the very screen holding the switch to turn this
    // feature back off, which is not a state anyone should be able to reach
    // from a server.
    private val suspended = MutableStateFlow(false)

    // When a person woke the panel themselves, the remote "off" is overridden
    // until this moment. A flow rather than a bare field so that extending it
    // cancels a pending sleep and restarts the wait, instead of the sleep firing
    // on the old deadline.
    private val graceUntilMs = MutableStateFlow(0L)

    // Set around our own wake so the screen-on broadcast it causes isn't mistaken
    // for someone walking up and touching the panel.
    @Volatile
    private var selfWakeUntilMs = 0L

    private val _desiredOn = MutableStateFlow<Boolean?>(null)

    private val _state = MutableStateFlow(ScreenPowerUiState())
    val state: StateFlow<ScreenPowerUiState> = _state.asStateFlow()

    private val powerManager: PowerManager
        get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * What the template wants, or null when nothing is driving the screen —
     * either no widget is mounted, the operator hasn't opted in, or the bound
     * slot is missing from state. Null means "leave the display alone", never
     * "turn it off": a template that loses its slot should give the panel back,
     * not black it out.
     */
    val desiredOn: StateFlow<Boolean?> = _desiredOn.asStateFlow()

    // Published into _desiredOn by run(), so the activity can read the current
    // value without owning a subscription of its own. The service always runs,
    // so there is no window in which this goes stale.
    private val computed: Flow<Boolean?> = combine(
        request,
        dashboard,
        enabled,
        suspended,
    ) { req, dash, on, held ->
        when {
            !on || req == null -> null
            held -> true
            else -> req.on.resolveBoolean(dash.state)
        }
    }.distinctUntilChanged()

    override fun request(request: ScreenPowerRequest?) {
        this.request.value = request
    }

    /** Suspend screen-off while the operator is looking at the device. */
    fun setSuspended(value: Boolean) {
        suspended.value = value
    }

    /**
     * Apply [desiredOn] to the hardware until cancelled, and keep [state] fresh
     * for the settings pane. Suspends for the caller's lifetime.
     */
    suspend fun run() {
        val receiver = screenOnReceiver()
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        try {
            computed.collectLatest { want ->
                _desiredOn.value = want
                _state.value = ScreenPowerUiState(
                    adminActive = admin.active,
                    adminSupported = admin.supported,
                    driving = want != null,
                    desiredOn = want,
                )
                when (want) {
                    null -> Unit
                    true -> wake()
                    // Stays subscribed for as long as "off" is wanted, so a
                    // person waking the panel only borrows it: each touch pushes
                    // the deadline out, and the sleep lands once they stop.
                    false -> graceUntilMs.collectLatest { until ->
                        val waitMs = until - System.currentTimeMillis()
                        if (waitMs > 0) delay(waitMs)
                        sleep()
                    }
                }
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Light the panel. Fires the deprecated wake lock and a [WakeActivity]
     * launch together: neither is reliable alone across devices, they don't
     * conflict, and the cost of the redundant one is a few milliseconds.
     */
    private fun wake() {
        if (powerManager.isInteractive) return
        selfWakeUntilMs = System.currentTimeMillis() + SELF_WAKE_WINDOW_MS

        @Suppress("DEPRECATION")
        powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            WAKE_LOCK_TAG,
        ).acquire(WAKE_LOCK_MS)

        context.startActivity(
            Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun sleep() {
        if (!powerManager.isInteractive) return
        admin.sleep()
    }

    /**
     * A screen-on we didn't cause means a person is standing at the panel. Give
     * them the device for [ScreenPowerRequest.wakeSeconds] before the remote
     * "off" is allowed to take it back, or the screen dies in their hand.
     */
    private fun screenOnReceiver() = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            if (now < selfWakeUntilMs) return
            val seconds = request.value?.wakeSeconds ?: return
            graceUntilMs.value = now + seconds * 1_000L
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "hearthd:screen-power"
        const val WAKE_LOCK_MS = 3_000L
        const val SELF_WAKE_WINDOW_MS = 2_000L
    }
}
