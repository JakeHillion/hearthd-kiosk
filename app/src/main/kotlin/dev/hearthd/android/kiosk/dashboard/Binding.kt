package dev.hearthd.android.kiosk.dashboard

import org.json.JSONObject

/**
 * A template field value that is either a literal (baked into the template) or a
 * slot bound to the live [state] blob. Slots are written as a single-key object
 * `{"$": "dotted.path"}`; anything else is a literal.
 *
 * This is what lets a state-only change re-render in place: the template tree is
 * parsed once, and each [Ref] is re-resolved against the current state on every
 * recomposition without touching the tree.
 */
sealed interface Binding {
    /** A constant value carried by the template itself. */
    data class Literal(val value: Any?) : Binding

    /** A slot resolved from state by dotted path, e.g. `weather.here`. */
    data class Ref(val path: String) : Binding

    /** Resolve to the underlying value, or null if a [Ref] path isn't present. */
    fun resolve(state: JSONObject): Any? = when (this) {
        is Literal -> value.takeUnless { it == JSONObject.NULL }
        is Ref -> resolvePath(state, path)
    }

    companion object {
        /**
         * Interpret a raw JSON field value: a lone `{"$": "path"}` object is a
         * [Ref]; everything else (including other objects) is a [Literal].
         */
        fun of(value: Any?): Binding {
            if (value is JSONObject && value.length() == 1) {
                val ref = value.opt("\$")
                if (ref is String) return Ref(ref)
            }
            return Literal(value)
        }
    }
}

/** Resolve and coerce to a String, or null when absent. */
fun Binding.resolveString(state: JSONObject): String? = when (val v = resolve(state)) {
    null, JSONObject.NULL -> null
    is String -> v
    else -> v.toString()
}

/** Resolve to a JSON object (the shape widgets like weather read fields from), or null. */
fun Binding.resolveObject(state: JSONObject): JSONObject? = resolve(state) as? JSONObject

/** Resolve and coerce to a Double, or null when absent or non-numeric. */
fun Binding.resolveDouble(state: JSONObject): Double? = when (val v = resolve(state)) {
    null, JSONObject.NULL -> null
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

/** Walk a dotted path through nested objects. Returns null on any missing segment. */
private fun resolvePath(state: JSONObject, path: String): Any? {
    var current: Any? = state
    for (segment in path.split('.')) {
        val obj = current as? JSONObject ?: return null
        current = obj.opt(segment)
        if (current == null || current == JSONObject.NULL) return null
    }
    return current
}
