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
import com.vanskis.liquidpocket.chat.LiveVoiceModel
import com.vanskis.liquidpocket.chat.Skill
import com.vanskis.liquidpocket.chat.Skills
import com.vanskis.liquidpocket.chat.SystemPrompt
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
    /** The app's look ([AppTheme]); Wallpaper takes Material You colors from the wallpaper. */
    val theme: AppTheme = AppTheme.WALLPAPER,
    /** Read replies aloud: in the model's own voice when it can speak, otherwise Android's. */
    val speakReplies: Boolean = false,
    /** Who hears voice messages and speaks replies; [VoiceEngine.MODEL] falls back to Android when it cannot. */
    val voiceEngine: VoiceEngine = VoiceEngine.MODEL,
    /** Offer the model web_search and fetch_page; their queries are the only data that leaves the phone. */
    val webSearch: Boolean = true,
    /** Free text added to the system prompt of every new chat; empty adds nothing. */
    val customInstructions: String = "",
    /** Let the model reason before it answers (the composer's Think chip). */
    val think: Boolean = true,
    /** Tokens a reply may think before its answer is forced ([ThinkBudget]). */
    val thinkBudget: Int = ThinkBudget.DEFAULT,
    /** Ask the engine for the NPU (experimental); it falls back to the CPU when it cannot use it. */
    val useNpu: Boolean = false,
    /** Who live voice talks to; null until chosen, which means Omni when installed ([LiveVoiceModel.resolve]). */
    val liveVoiceModel: LiveVoiceModel? = null,
    /** The user's own skills, listed after the built-in ones when "/" is typed. */
    val userSkills: List<Skill> = emptyList(),
) {
    companion object {
        const val DEFAULT_TEMPERATURE = 0.3f
        const val MAX_TEMPERATURE = 1.5f
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
                theme = AppTheme.from(prefs[THEME], prefs[DYNAMIC_COLOR]),
                speakReplies = prefs[SPEAK_REPLIES] ?: false,
                voiceEngine = VoiceEngine.entries.firstOrNull { it.name == prefs[VOICE_ENGINE] } ?: VoiceEngine.MODEL,
                webSearch = prefs[WEB_SEARCH] ?: true,
                customInstructions = prefs[CUSTOM_INSTRUCTIONS] ?: "",
                think = prefs[THINK] ?: true,
                thinkBudget = prefs[THINK_BUDGET]?.let(ThinkBudget::clamp) ?: ThinkBudget.DEFAULT,
                useNpu = prefs[USE_NPU] ?: false,
                liveVoiceModel = LiveVoiceModel.entries.firstOrNull { it.name == prefs[LIVE_VOICE_MODEL] },
                userSkills = Skills.decode(prefs[USER_SKILLS]),
            )
        }
        .distinctUntilChanged()

    suspend fun setContextSize(value: Int) {
        store.edit { it[CONTEXT_SIZE] = ContextSize.clamp(value) }
    }

    suspend fun setTemperature(value: Float) {
        store.edit { it[TEMPERATURE] = value.coerceIn(0f, AppSettings.MAX_TEMPERATURE) }
    }

    /** Stores every setting in [value] at once (the Settings screen's Save). */
    suspend fun save(value: AppSettings) {
        store.edit {
            it[CONTEXT_SIZE] = ContextSize.clamp(value.contextSize)
            it[TEMPERATURE] = value.temperature.coerceIn(0f, AppSettings.MAX_TEMPERATURE)
            it[THEME] = value.theme.name
            it[SPEAK_REPLIES] = value.speakReplies
            it[VOICE_ENGINE] = value.voiceEngine.name
            it[WEB_SEARCH] = value.webSearch
            it[CUSTOM_INSTRUCTIONS] = value.customInstructions.take(SystemPrompt.MAX_CUSTOM_CHARS)
            it[THINK] = value.think
            it[THINK_BUDGET] = ThinkBudget.clamp(value.thinkBudget)
            it[USE_NPU] = value.useNpu
            value.liveVoiceModel?.let { model -> it[LIVE_VOICE_MODEL] = model.name }
            it[USER_SKILLS] = Skills.encode(value.userSkills)
        }
    }

    suspend fun setSpeakReplies(value: Boolean) {
        store.edit { it[SPEAK_REPLIES] = value }
    }

    suspend fun setVoiceEngine(value: VoiceEngine) {
        store.edit { it[VOICE_ENGINE] = value.name }
    }

    suspend fun setWebSearch(value: Boolean) {
        store.edit { it[WEB_SEARCH] = value }
    }

    suspend fun setCustomInstructions(value: String) {
        store.edit { it[CUSTOM_INSTRUCTIONS] = value.take(SystemPrompt.MAX_CUSTOM_CHARS) }
    }

    suspend fun setThink(value: Boolean) {
        store.edit { it[THINK] = value }
    }

    suspend fun setThinkBudget(value: Int) {
        store.edit { it[THINK_BUDGET] = ThinkBudget.clamp(value) }
    }

    suspend fun setUseNpu(value: Boolean) {
        store.edit { it[USE_NPU] = value }
    }

    suspend fun setLiveVoiceModel(value: LiveVoiceModel) {
        store.edit { it[LIVE_VOICE_MODEL] = value.name }
    }

    private companion object {
        val CONTEXT_SIZE = intPreferencesKey("context_size")
        val TEMPERATURE = floatPreferencesKey("temperature")
        /** Read only, to pick a theme for settings stored before themes existed ([AppTheme.from]). */
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme")
        val SPEAK_REPLIES = booleanPreferencesKey("speak_replies")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val WEB_SEARCH = booleanPreferencesKey("web_search")
        val CUSTOM_INSTRUCTIONS = stringPreferencesKey("custom_instructions")
        val USER_SKILLS = stringPreferencesKey("user_skills")
        val THINK = booleanPreferencesKey("think")
        val THINK_BUDGET = intPreferencesKey("think_budget")
        val USE_NPU = booleanPreferencesKey("use_npu")
        val LIVE_VOICE_MODEL = stringPreferencesKey("live_voice_model")
    }
}
