package dev.hearthd.android.kiosk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hearthd.android.kiosk.KioskApp
import dev.hearthd.android.kiosk.MainActivity
import dev.hearthd.android.kiosk.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The kiosk's run loops, on a lifetime the screen can't interrupt.
 *
 * Everything the app does used to hang off `repeatOnLifecycle(STARTED)`, which
 * made the display's on/off state the de facto power switch for the whole app:
 * the panel sleeping stopped the dashboard poll, the audio client, and the mic
 * alike. That is tolerable while the display only ever sleeps on its own, and
 * untenable once the kiosk puts it out deliberately — the poll that would tell
 * us to wake back up is the first thing to die.
 *
 * So the loops live here instead, in a scope bounded by the process rather than
 * by visibility, and the service is a foreground one so the process survives
 * being off-screen for hours.
 *
 * Deliberately started once and never stopped. Starting a foreground service
 * from the background is restricted from API 31, so a service that stops while
 * dark may find it can't come back; and a screen wake lock is disabled outright
 * for a process the system considers cached, which a running foreground service
 * is what prevents. Both problems disappear if it simply always runs.
 */
class KioskService : Service() {
    // Bounded by the service, not by any UI. Main.immediate so collectors touch
    // the same dispatcher the UI reads them from; the work itself is already on
    // IO inside the clients.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val app: KioskApp get() = application as KioskApp

    override fun onCreate() {
        super.onCreate()
        startForeground()
        runStatePoll()
        runSnapcast()
        runVolumeSync()
    }

    // Restarted by the system if the process is reclaimed; redelivery isn't
    // wanted, since the loops rebuild their own state from settings on start.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The one `/state` poll. It serves the dashboard and template control alike,
     * since both read the same content-addressed document — so it runs while
     * either wants it, and only publishes a dashboard when the dashboard is on.
     *
     * The server dictates the cadence (poll() returns the seconds to wait) and
     * collectLatest restarts the loop when settings change, clearing whatever is
     * held when both consumers are switched off.
     */
    private fun runStatePoll() {
        scope.launch {
            combine(
                app.settings.dashboard,
                app.settings.managedEnabled,
            ) { dash, managed -> dash to managed }.collectLatest { (dash, managed) ->
                if (!dash.configured || (!dash.enabled && !managed)) {
                    app.dashboard.clear()
                    app.managed.clear()
                    return@collectLatest
                }
                if (!dash.enabled) app.dashboard.clear()
                if (!managed) app.managed.clear()
                while (true) {
                    val waitSeconds = app.dashboard.poll(dash.stateUrl, publish = dash.enabled)
                    delay(waitSeconds.toLong() * 1_000L)
                }
            }
        }
    }

    /**
     * The snapclient subprocess. Audio is the clearest case for this lifetime:
     * the client is one room of a sample-locked multi-room stream, so a panel
     * that sleeps used to drop its room out of the house until someone touched
     * it — the process is killed with the coroutine that spawned it.
     *
     * collectLatest is what stops and restarts the client across a settings
     * change, and the opt-in gate keeps an unconfigured device from spawning
     * anything at all.
     */
    private fun runSnapcast() {
        scope.launch {
            app.settings.snapcast.collectLatest { s ->
                if (!s.enabled || !s.configured) {
                    app.snapcast.markDisabled()
                    return@collectLatest
                }
                app.snapcast.run(s)
            }
        }
    }

    /**
     * Volume sync with the same server, on its own opt-in and its own socket.
     * It belongs on this lifetime for the same reason the client does, and more
     * sharply: with sync on, the client runs `--mixer none` and this link *is*
     * the mixer, so a dark panel used to ignore the server's volume entirely
     * while still playing at whatever level it was left at.
     */
    private fun runVolumeSync() {
        scope.launch {
            app.settings.snapcast.collectLatest { s ->
                if (!s.enabled || !s.configured || !s.volumeSync) {
                    app.volumeSync.markDisabled()
                    return@collectLatest
                }
                app.volumeSync.run(s)
            }
        }
    }

    /**
     * The notification the platform requires of a foreground service. Kept at
     * low importance and silent: on a kiosk with the system bars hidden it is
     * never seen, and it exists to satisfy the platform rather than to tell the
     * operator anything.
     *
     * `specialUse` rather than the tempting `dataSync`: from API 34 a dataSync
     * service is allowed six hours in any twenty-four, after which the system
     * kills it — and the budget only resets when someone brings the app to the
     * foreground, which on a wall panel never happens.
     */
    private fun startForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "kiosk_service"
        private const val NOTIFICATION_ID = 1

        /** Start the service if it isn't already running; safe to call repeatedly. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KioskService::class.java),
            )
        }
    }
}
