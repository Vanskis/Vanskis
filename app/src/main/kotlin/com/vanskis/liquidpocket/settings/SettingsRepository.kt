package com.vanskis.liquidpocket.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vanskis.liquidpocket.core.ContextSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val contextSize: Int = ContextSize.DEFAULT,
    val temperature: Float = DEFAULT_TEMPERATURE,
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
            )
        }
        .distinctUntilChanged()

    suspend fun setContextSize(value: Int) {
        store.edit { it[CONTEXT_SIZE] = ContextSize.clamp(value) }
    }

    suspend fun setTemperature(value: Float) {
        store.edit { it[TEMPERATURE] = value.coerceIn(0f, AppSettings.MAX_TEMPERATURE) }
    }

    private companion object {
        val CONTEXT_SIZE = intPreferencesKey("context_size")
        val TEMPERATURE = floatPreferencesKey("temperature")
    }
}
