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
import com.vanskis.liquidpocket.ACTIVE_MODEL
import com.vanskis.liquidpocket.MEDIA_MODEL
import com.vanskis.liquidpocket.chat.LiveVoiceModel
import com.vanskis.liquidpocket.chat.Skill
import com.vanskis.liquidpocket.chat.Skills
import com.vanskis.liquidpocket.chat.SystemPrompt
import com.vanskis.liquidpocket.chat.VoiceEngine
import com.vanskis.liquidpocket.core.ContextSize
import com.vanskis.liquidpocket.core.CustomModel
import com.vanskis.liquidpocket.core.Heretic
import com.vanskis.liquidpocket.core.ModelSpec
import com.vanskis.liquidpocket.ui.components.Indicators
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
    /** Which reply numbers the chat shows: speeds, token count, elapsed time. */
    val indicators: Indicators = Indicators(),
    /** Free text added to the system prompt of every new chat; empty adds nothing. */
    val customInstructions: String = "",
    /** Let the model reason before it answers (the composer's Think chip). */
    val think: Boolean = true,
    /** Tokens a reply may think before its answer is forced ([ThinkBudget]). */
    val thinkBudget: Int = ThinkBudget.DEFAULT,
    /** The model id picked in the composer's menu; empty (or no longer offered) for the default text model. */
    val chatModel: String = "",
    /** Ask the engine for the NPU (experimental); it falls back to the CPU when it cannot use it. */
    val useNpu: Boolean = false,
    /** Who live voice talks to; null until chosen, which means Omni when installed ([LiveVoiceModel.resolve]). */
    val liveVoiceModel: LiveVoiceModel? = null,
    /** The user's own skills, listed after the built-in ones when "/" is typed. */
    val userSkills: List<Skill> = emptyList(),
    /** Models the user added from Hugging Face or the phone ([CustomModel]). */
    val customModels: List<CustomModel> = emptyList(),
    /** Each model's own sampling, reply length and instructions, by model id ([ModelTuning]). */
    val tunings: Map<String, ModelTuning> = emptyMap(),
    /** Use a Heretic build of LFM3-1.7B from [hereticTextRepo] instead of the standard one. */
    val hereticText: Boolean = false,
    val hereticTextRepo: String = "",
    /** Use a Heretic build of Omni from [hereticMediaRepo] instead of the standard one. */
    val hereticMedia: Boolean = false,
    val hereticMediaRepo: String = "",
) {
    /** The text model chosen in Settings: Heretic when on with a valid repo, else standard. */
    val textModel: ModelSpec get() = Heretic.chosen(ACTIVE_MODEL, hereticText, hereticTextRepo)

    val mediaModel: ModelSpec get() = Heretic.chosen(MEDIA_MODEL, hereticMedia, hereticMediaRepo)

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
                indicators = Indicators(
                    thinkingSpeed = prefs[SHOW_THINKING_SPEED] ?: true,
                    answerSpeed = prefs[SHOW_ANSWER_SPEED] ?: true,
                    tokenCount = prefs[SHOW_TOKEN_COUNT] ?: false,
                    elapsed = prefs[SHOW_ELAPSED] ?: true,
                ),
                customInstructions = prefs[CUSTOM_INSTRUCTIONS] ?: "",
                think = prefs[THINK] ?: true,
                chatModel = prefs[CHAT_MODEL] ?: "",
                thinkBudget = prefs[THINK_BUDGET]?.let(ThinkBudget::clamp) ?: ThinkBudget.DEFAULT,
                useNpu = prefs[USE_NPU] ?: false,
                liveVoiceModel = LiveVoiceModel.entries.firstOrNull { it.name == prefs[LIVE_VOICE_MODEL] },
                userSkills = Skills.decode(prefs[USER_SKILLS]),
                customModels = CustomModel.decode(prefs[CUSTOM_MODELS]),
                tunings = ModelTuning.decode(prefs[MODEL_TUNING]),
                hereticText = prefs[HERETIC_TEXT] ?: false,
                hereticTextRepo = prefs[HERETIC_TEXT_REPO] ?: "",
                hereticMedia = prefs[HERETIC_MEDIA] ?: false,
                hereticMediaRepo = prefs[HERETIC_MEDIA_REPO] ?: "",
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
            it[SHOW_THINKING_SPEED] = value.indicators.thinkingSpeed
            it[SHOW_ANSWER_SPEED] = value.indicators.answerSpeed
            it[SHOW_TOKEN_COUNT] = value.indicators.tokenCount
            it[SHOW_ELAPSED] = value.indicators.elapsed
            it[CUSTOM_INSTRUCTIONS] = value.customInstructions.take(SystemPrompt.MAX_CUSTOM_CHARS)
            it[THINK] = value.think
            it[CHAT_MODEL] = value.chatModel
            it[THINK_BUDGET] = ThinkBudget.clamp(value.thinkBudget)
            it[USE_NPU] = value.useNpu
            value.liveVoiceModel?.let { model -> it[LIVE_VOICE_MODEL] = model.name }
            it[USER_SKILLS] = Skills.encode(value.userSkills)
            it[CUSTOM_MODELS] = CustomModel.encode(value.customModels)
            it[MODEL_TUNING] = ModelTuning.encode(value.tunings)
            it[HERETIC_TEXT] = value.hereticText
            it[HERETIC_TEXT_REPO] = value.hereticTextRepo.trim()
            it[HERETIC_MEDIA] = value.hereticMedia
            it[HERETIC_MEDIA_REPO] = value.hereticMediaRepo.trim()
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

    /** The composer menu's thinking level: whether to think and, when it does, how much. */
    suspend fun setThinking(on: Boolean, budget: Int) {
        store.edit {
            it[THINK] = on
            it[THINK_BUDGET] = ThinkBudget.clamp(budget)
        }
    }

    /** Replaces the custom model with [model]'s id, or adds it; stored at once, not with [save]. */
    suspend fun putCustomModel(model: CustomModel) {
        store.edit { prefs ->
            val list = CustomModel.decode(prefs[CUSTOM_MODELS])
            val next = if (list.any { it.id == model.id }) list.map { if (it.id == model.id) model else it } else list + model
            prefs[CUSTOM_MODELS] = CustomModel.encode(next)
        }
    }

    suspend fun removeCustomModel(id: String) {
        store.edit { prefs -> prefs[CUSTOM_MODELS] = CustomModel.encode(CustomModel.decode(prefs[CUSTOM_MODELS]).filter { it.id != id }) }
    }

    suspend fun setChatModel(id: String) {
        store.edit { it[CHAT_MODEL] = id }
    }

    suspend fun setThinkBudget(value: Int) {
        store.edit { it[THINK_BUDGET] = ThinkBudget.clamp(value) }
    }

    suspend fun setUseNpu(value: Boolean) {
        store.edit { it[USE_NPU] = value }
    }

    /** Whether the one quiet benchmark after the first model load has run (or started). */
    val npuBenchmarked: Flow<Boolean> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[NPU_BENCHMARKED] ?: false }

    suspend fun setNpuBenchmarked() {
        store.edit { it[NPU_BENCHMARKED] = true }
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
        val SHOW_THINKING_SPEED = booleanPreferencesKey("show_thinking_speed")
        val SHOW_ANSWER_SPEED = booleanPreferencesKey("show_answer_speed")
        val SHOW_TOKEN_COUNT = booleanPreferencesKey("show_token_count")
        val SHOW_ELAPSED = booleanPreferencesKey("show_elapsed")
        val CUSTOM_INSTRUCTIONS = stringPreferencesKey("custom_instructions")
        val USER_SKILLS = stringPreferencesKey("user_skills")
        val CUSTOM_MODELS = stringPreferencesKey("custom_models")
        val MODEL_TUNING = stringPreferencesKey("model_tuning")
        val HERETIC_TEXT = booleanPreferencesKey("heretic_text")
        val HERETIC_TEXT_REPO = stringPreferencesKey("heretic_text_repo")
        val HERETIC_MEDIA = booleanPreferencesKey("heretic_media")
        val HERETIC_MEDIA_REPO = stringPreferencesKey("heretic_media_repo")
        val THINK = booleanPreferencesKey("think")
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val THINK_BUDGET = intPreferencesKey("think_budget")
        val USE_NPU = booleanPreferencesKey("use_npu")
        val NPU_BENCHMARKED = booleanPreferencesKey("npu_benchmarked")
        val LIVE_VOICE_MODEL = stringPreferencesKey("live_voice_model")
    }
}
