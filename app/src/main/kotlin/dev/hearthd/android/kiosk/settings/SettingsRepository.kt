package dev.hearthd.android.kiosk.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hearthd.android.kiosk.wakeword.WakeWordModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persists [UpdateSettings] via a Preferences DataStore. */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val enabled = booleanPreferencesKey("auto_update_enabled")
        val channel = stringPreferencesKey("channel")
        val interval = intPreferencesKey("interval_minutes")
        val wakeEnabled = booleanPreferencesKey("wake_enabled")
        val wakeModel = stringPreferencesKey("wake_model")
        val wakeThreshold = floatPreferencesKey("wake_threshold")
        val voiceEnabled = booleanPreferencesKey("voice_enabled")
        val voiceBaseUrl = stringPreferencesKey("voice_base_url")
        val voicePipeline = stringPreferencesKey("voice_pipeline")
        val dashboardEnabled = booleanPreferencesKey("dashboard_enabled")
        val dashboardStateUrl = stringPreferencesKey("dashboard_state_url")
        val hearthdEnabled = booleanPreferencesKey("hearthd_enabled")
        val hearthdBaseUrl = stringPreferencesKey("hearthd_base_url")
        val snapcastEnabled = booleanPreferencesKey("snapcast_enabled")
        val snapcastHost = stringPreferencesKey("snapcast_host")
        val snapcastPort = intPreferencesKey("snapcast_port")
    }

    val settings: Flow<UpdateSettings> = context.dataStore.data.map { prefs ->
        UpdateSettings(
            enabled = prefs[Keys.enabled] ?: false,
            channel = Channel.fromId(prefs[Keys.channel]),
            intervalMinutes = prefs[Keys.interval] ?: 360,
        )
    }

    val wakeWord: Flow<WakeWordSettings> = context.dataStore.data.map { prefs ->
        val model = WakeWordModel.fromId(prefs[Keys.wakeModel])
        WakeWordSettings(
            enabled = prefs[Keys.wakeEnabled] ?: false,
            model = model,
            // Fall back to the model's tuned default until the user moves the slider.
            threshold = prefs[Keys.wakeThreshold] ?: model.defaultThreshold,
        )
    }

    suspend fun setEnabled(value: Boolean) = context.dataStore.edit { it[Keys.enabled] = value }

    suspend fun setChannel(channel: Channel) =
        context.dataStore.edit { it[Keys.channel] = channel.id }

    suspend fun setIntervalMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.interval] = minutes }

    suspend fun setWakeEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.wakeEnabled] = value }

    suspend fun setWakeModel(model: WakeWordModel) =
        context.dataStore.edit { it[Keys.wakeModel] = model.id }

    suspend fun setWakeThreshold(threshold: Float) =
        context.dataStore.edit { it[Keys.wakeThreshold] = threshold }

    val voice: Flow<VoiceSettings> = context.dataStore.data.map { prefs ->
        VoiceSettings(
            enabled = prefs[Keys.voiceEnabled] ?: false,
            baseUrl = prefs[Keys.voiceBaseUrl] ?: "",
            pipelineId = prefs[Keys.voicePipeline] ?: "",
        )
    }

    suspend fun setVoiceEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.voiceEnabled] = value }

    suspend fun setVoiceBaseUrl(url: String) =
        context.dataStore.edit { it[Keys.voiceBaseUrl] = url.trim() }

    suspend fun setVoicePipeline(id: String) =
        context.dataStore.edit { it[Keys.voicePipeline] = id.trim() }

    val dashboard: Flow<DashboardSettings> = context.dataStore.data.map { prefs ->
        DashboardSettings(
            enabled = prefs[Keys.dashboardEnabled] ?: false,
            stateUrl = prefs[Keys.dashboardStateUrl] ?: "",
        )
    }

    suspend fun setDashboardEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.dashboardEnabled] = value }

    suspend fun setDashboardStateUrl(url: String) =
        context.dataStore.edit { it[Keys.dashboardStateUrl] = url.trim() }

    val hearthd: Flow<HearthdSettings> = context.dataStore.data.map { prefs ->
        HearthdSettings(
            enabled = prefs[Keys.hearthdEnabled] ?: false,
            baseUrl = prefs[Keys.hearthdBaseUrl] ?: "",
        )
    }

    suspend fun setHearthdEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.hearthdEnabled] = value }

    suspend fun setHearthdBaseUrl(url: String) =
        context.dataStore.edit { it[Keys.hearthdBaseUrl] = url.trim() }

    val snapcast: Flow<SnapcastSettings> = context.dataStore.data.map { prefs ->
        SnapcastSettings(
            enabled = prefs[Keys.snapcastEnabled] ?: false,
            host = prefs[Keys.snapcastHost] ?: "",
            port = prefs[Keys.snapcastPort] ?: SnapcastSettings.DEFAULT_PORT,
        )
    }

    suspend fun setSnapcastEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.snapcastEnabled] = value }

    suspend fun setSnapcastHost(host: String) =
        context.dataStore.edit { it[Keys.snapcastHost] = host.trim() }

    suspend fun setSnapcastPort(port: Int) =
        context.dataStore.edit { it[Keys.snapcastPort] = port }
}
