package com.vanskis.liquidpocket.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vanskis.liquidpocket.chat.VoiceEngine
import com.vanskis.liquidpocket.core.ContextSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val contextSize: Int = ContextSize.DEFAULT,
    val temperature: Float = DEFAULT_TEMPERATURE,
    /** Material You colors from the wallpaper; off uses the app's own palette. */
    val dynamicColor: Boolean = true,
    /** Read replies aloud: in the model's own voice when it can speak, otherwise Android's. */
    val speakReplies: Boolean = false,
    /** Who hears voice messages and speaks replies; [VoiceEngine.MODEL] falls back to Android when it cannot. */
    val voiceEngine: VoiceEngine = VoiceEngine.MODEL,
) {
    companion object {
        const val DEFAULT_TEMPERATURE = 0.3f
        const val MAX_TEMPERATURE = 1.5f
        const val CONTEXT_STEP = 1024
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            AppSettings(
                contextSize = prefs[CONTEXT_SIZE] ?: ContextSize.DEFAULT,
                temperature = prefs[TEMPERATURE] ?: AppSettings.DEFAULT_TEMPERATURE,
                dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
                speakReplies = prefs[SPEAK_REPLIES] ?: false,
                voiceEngine = VoiceEngine.entries.firstOrNull { it.name == prefs[VOICE_ENGINE] } ?: VoiceEngine.MODEL,
            )
        }
        .distinctUntilChanged()

    suspend fun setContextSize(value: Int) {
        store.edit { it[CONTEXT_SIZE] = ContextSize.clamp(value) }
    }

    suspend fun setTemperature(value: Float) {
        store.edit { it[TEMPERATURE] = value.coerceIn(0f, AppSettings.MAX_TEMPERATURE) }
    }

    suspend fun setDynamicColor(value: Boolean) {
        store.edit { it[DYNAMIC_COLOR] = value }
    }

    suspend fun setSpeakReplies(value: Boolean) {
        store.edit { it[SPEAK_REPLIES] = value }
    }

    suspend fun setVoiceEngine(value: VoiceEngine) {
        store.edit { it[VOICE_ENGINE] = value.name }
    }

    private companion object {
        val CONTEXT_SIZE = intPreferencesKey("context_size")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SPEAK_REPLIES = booleanPreferencesKey("speak_replies")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
    }
}
