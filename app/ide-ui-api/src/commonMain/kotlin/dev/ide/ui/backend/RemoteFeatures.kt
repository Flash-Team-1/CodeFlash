package dev.ide.ui.backend

/**
 * Feature switches the server turned on for this install or account, read once when the app starts.
 * A feature missing from the map is off. Only the server turns a feature on, never the app itself.
 */
object RemoteFeatures {
    @Volatile
    private var flags: Map<String, Boolean> = emptyMap()

    /** Replaces every switch with what the server just said. */
    fun replace(values: Map<String, Boolean>) {
        flags = values
    }

    /** Whether [key] is on. Off when the server has not said so. */
    fun isOn(key: String): Boolean = flags[key] == true
}
