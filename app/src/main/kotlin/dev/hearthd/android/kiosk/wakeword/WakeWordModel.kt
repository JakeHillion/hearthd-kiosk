package dev.hearthd.android.kiosk.wakeword

/** The two shared feature models every wake word runs through. */
const val MELSPEC_ASSET = "wakeword/melspectrogram.onnx"
const val EMBEDDING_ASSET = "wakeword/embedding_model.onnx"

/**
 * A bundled openWakeWord classifier. openWakeWord ships only a handful of
 * pre-trained phrases; none say "hearth", so we default to Hey Jarvis and let
 * the operator switch. Assets are staged by the Nix build (see flake.nix), not
 * committed to the repo.
 */
enum class WakeWordModel(
    val id: String,
    val label: String,
    val asset: String,
    /** Score in 0..1 above which the default threshold fires; tuned by openWakeWord. */
    val defaultThreshold: Float,
) {
    HEY_JARVIS("hey_jarvis", "Hey Jarvis", "wakeword/hey_jarvis.onnx", 0.5f),
    ALEXA("alexa", "Alexa", "wakeword/alexa.onnx", 0.5f),
    HEY_MYCROFT("hey_mycroft", "Hey Mycroft", "wakeword/hey_mycroft.onnx", 0.5f),
    ;

    companion object {
        val DEFAULT = HEY_JARVIS

        fun fromId(id: String?): WakeWordModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
