package dev.hearthd.android.kiosk.snapcast

import android.content.Context
import android.provider.Settings
import dev.hearthd.android.kiosk.settings.SnapcastSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File

enum class SnapcastStatus { DISABLED, STARTING, RUNNING, ERROR }

/**
 * Live status of the snapclient subprocess, enough for the Audio settings pane.
 * [lastLine] is the most recent log line the binary emitted, surfaced verbatim
 * so a misconfiguration (wrong host, refused connection) is visible without a
 * device shell.
 */
data class SnapcastUiState(
    val status: SnapcastStatus = SnapcastStatus.DISABLED,
    val server: String = "",
    val message: String? = null,
    val lastLine: String? = null,
)

/**
 * Runs the bundled `libsnapclient.so` — a full snapclient executable cross-built
 * by the Nix flake (see snapclient-android.nix) and shipped in jniLibs — as a
 * child process. Reusing upstream's client is what keeps this device sample-locked
 * to the other rooms: it's the same implementation, not a re-derivation.
 *
 * Holds state and runs nothing until [run]; mirrors the other controllers. One
 * process at a time, its lifetime bound to the [run] call: when the caller
 * cancels (settings change, app leaves the foreground) the process is destroyed,
 * which closes its stdout and unwinds the reader below.
 */
class SnapcastController(private val context: Context) {

    private val _state = MutableStateFlow(SnapcastUiState())
    val state: StateFlow<SnapcastUiState> = _state.asStateFlow()

    /** Reflect the "off" state without spawning anything. */
    fun markDisabled() {
        _state.value = SnapcastUiState(SnapcastStatus.DISABLED)
    }

    /**
     * Spawn snapclient against [settings] and stream its log until this coroutine
     * is cancelled or the process exits. Suspends for the process's lifetime.
     */
    suspend fun run(settings: SnapcastSettings) {
        val server = "${settings.host}:${settings.port}"
        _state.value = SnapcastUiState(SnapcastStatus.STARTING, server = server)

        val exe = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
        if (!exe.exists()) {
            _state.value = SnapcastUiState(
                SnapcastStatus.ERROR, server, message = "snapclient binary not found",
            )
            return
        }

        // A stable id so the server recognises this client across reconnects.
        // hostID defaults to a MAC address, which Android no longer exposes.
        @Suppress("HardwareIds")
        val hostId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "hearthd-kiosk"

        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(
                exe.absolutePath,
                "-h", settings.host,
                "-p", settings.port.toString(),
                "--player", "oboe",
                "--hostID", hostId,
                "--logsink", "stdout",
            ).redirectErrorStream(true).start()
        }

        // Blocking process I/O isn't interruptible, so cancellation can't unblock
        // the reader on its own. Destroying the process closes stdout, which makes
        // the reader hit EOF and return — so tie the process's life to this job.
        val killer = currentCoroutineContext().job.invokeOnCompletion { process.destroyForcibly() }

        try {
            _state.update { it.copy(status = SnapcastStatus.RUNNING) }
            withContext(Dispatchers.IO) {
                process.inputStream.bufferedReader().forEachLine { line ->
                    _state.update { it.copy(lastLine = line) }
                }
            }
            // Reader hit EOF: the process exited (or was killed by cancellation).
            val code = process.waitFor()
            if (currentCoroutineContext().isActive && code != 0) {
                _state.update {
                    it.copy(status = SnapcastStatus.ERROR, message = "exited with code $code")
                }
            }
        } finally {
            killer.dispose()
            process.destroyForcibly()
        }
    }

    private companion object {
        // The APK ships the snapclient executable under this jniLibs name (the
        // trick Android uses to run a native binary from an app).
        const val LIB_NAME = "libsnapclient.so"
    }
}
