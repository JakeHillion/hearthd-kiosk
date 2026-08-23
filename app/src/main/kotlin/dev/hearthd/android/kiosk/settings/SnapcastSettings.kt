package dev.hearthd.android.kiosk.settings

/**
 * Snapcast client preferences. Off by default and inert until a server host is
 * set: with [enabled] false or no host, the app never opens a socket and
 * never spawns the snapclient binary.
 *
 * Plain TCP only — the bundled snapclient is built without TLS, so no `wss://`
 * or server auth (see snapclient-android.nix).
 */
data class SnapcastSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    /**
     * Snapserver's JSON-RPC control port. Same host as the stream, used to sync
     * this client's volume both ways (push local key presses, accept remote
     * changes). Defaults to snapserver's own default.
     */
    val controlPort: Int = DEFAULT_CONTROL_PORT,
) {
    val configured: Boolean get() = host.isNotBlank()

    companion object {
        const val DEFAULT_PORT = 1704
        const val DEFAULT_CONTROL_PORT = 1705
    }
}
