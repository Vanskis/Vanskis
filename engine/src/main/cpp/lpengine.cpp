// JNI bridge between LlamaNative.kt and llama.cpp (pinned at b11960).
//
// Adapted from llama.cpp examples/llama.android/lib/src/main/cpp/ai_chat.cpp
// (MIT, Copyright (c) 2023-2026 The ggml authors; original by Han Yin, Arm).
//
// Every function here is called from the engine's single dedicated thread (LlamaEngine), so the
// global state needs no locking.
//
// llama.cpp / common signatures relied on, as declared at this pin:
//   include/llama.h
//     llama_model_params   llama_model_default_params(void);
//     llama_context_params llama_context_default_params(void);
//     void          llama_backend_init(void);
//     void          llama_log_set(ggml_log_callback log_callback, void * user_data);
//     llama_model * llama_model_load_from_file(const char * path_model, llama_model_params params);
//     void          llama_model_free(llama_model * model);
//     llama_context * llama_init_from_model(llama_model * model, llama_context_params params);
//     void          llama_free(llama_context * ctx);
//     uint32_t      llama_n_ctx(const llama_context * ctx);
//     int32_t       llama_model_n_ctx_train(const llama_model * model);
//     const llama_vocab * llama_model_get_vocab(const llama_model * model);
//     bool          llama_vocab_is_eog(const llama_vocab * vocab, llama_token token);
//     llama_memory_t llama_get_memory(const llama_context * ctx);
//     void          llama_memory_clear(llama_memory_t mem, bool data);
//     bool          llama_memory_seq_rm(llama_memory_t mem, llama_seq_id seq_id, llama_pos p0, llama_pos p1);
//                   // "Returns false if a partial sequence cannot be removed" (recurrent/hybrid, n_rs_seq = 0)
//     int32_t       llama_process(llama_context * ctx, llama_process_type type, llama_batch_ext * batch);
//     const char *  llama_print_system_info(void);
//   ggml/include/ggml-backend.h
//     void ggml_backend_load_all_from_path(const char * dir_path);
//   common/common.h
//     common_batch(llama_context * ctx); void clear();
//     int32_t add(llama_token id, llama_pos pos, llama_seq_id seq_id, bool output);
//     llama_batch_ext * get();
//     std::vector<llama_token> common_tokenize(const llama_vocab * vocab, const std::string & text,
//                                              bool add_special, bool parse_special = false);
//     std::string common_token_to_piece(const llama_context * ctx, llama_token token, bool special = true);
//   common/chat.h
//     common_chat_templates_ptr common_chat_templates_init(const llama_model * model,
//                                                          const std::string & chat_template_override, ...);
//     common_chat_params common_chat_templates_apply(const common_chat_templates * tmpls,
//                                                    const common_chat_templates_inputs & inputs);
//     common_chat_templates_inputs { messages, add_generation_prompt, use_jinja, enable_thinking, ... }
//     common_chat_params::prompt is a common_chat_prompt { parts: [{ text, is_input }] }; when the vocab
//     adds BOS, the template's leading bos_token text is already stripped from parts[0].
//   common/sampling.h
//     common_sampler * common_sampler_init(const llama_model * model, common_params_sampling & params);
//     void        common_sampler_free(common_sampler * gsmpl);
//     llama_token common_sampler_sample(common_sampler * gsmpl, llama_context * ctx, int idx, bool grammar_first = false);
//     void        common_sampler_accept(common_sampler * gsmpl, llama_token token, bool is_generated);

#include <jni.h>

#include <algorithm>
#include <exception>
#include <stdexcept>
#include <string>
#include <vector>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "sampling.h"
#include "llama.h"
#include "ggml-backend.h"

namespace {

// Mirrors com.vanskis.liquidpocket.core.ContextSize.
constexpr int CTX_MIN = 2048;
constexpr int CTX_MAX = 32768;
constexpr int BATCH_SIZE = 512;

// Role codes shared with LlamaNative.kt.
constexpr jint ROLE_SYSTEM = 0;
constexpr jint ROLE_USER = 1;
constexpr jint ROLE_ASSISTANT = 2;

llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
const llama_vocab * g_vocab = nullptr;
common_chat_templates_ptr g_templates;
common_sampler * g_sampler = nullptr;
common_batch g_batch;
int g_n_ctx = 0;

// Tokens held in memory for sequence 0, at positions 0..size-1.
llama_tokens g_cached;
// The text g_cached stands for (rendered prompt plus generated pieces); valid only after a
// completed prefill, so a new prompt that starts with it can skip re-tokenizing the history.
std::string g_cached_text;
bool g_cached_text_valid = false;

// Prompt tokens still to decode for the current reply, and the text they complete.
llama_tokens g_pending;
size_t g_pending_off = 0;
std::string g_pending_full_text;

void throw_java(JNIEnv * env, const std::string & message) {
    LOGe("%s", message.c_str());
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

std::string jstring_to_std(JNIEnv * env, jstring s) {
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Contents travel as UTF-8 byte arrays: GetStringUTFChars yields modified UTF-8, which encodes
// characters outside the BMP (emoji) as surrogate pairs the tokenizer would mangle.
std::string jbytes_to_std(JNIEnv * env, jbyteArray bytes) {
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

void reset_cache() {
    if (g_ctx != nullptr) llama_memory_clear(llama_get_memory(g_ctx), true);
    g_cached.clear();
    g_cached_text.clear();
    g_cached_text_valid = false;
    g_pending.clear();
    g_pending_off = 0;
    g_pending_full_text.clear();
}

void free_all() {
    reset_cache();
    if (g_sampler != nullptr) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    g_templates.reset();
    g_batch = common_batch();
    if (g_ctx != nullptr) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_n_ctx = 0;
}

// Renders the conversation with the model's jinja template, thinking disabled, ending with the
// assistant generation prompt. Throws std::exception on template errors.
common_chat_prompt render(JNIEnv * env, jintArray jroles, jobjectArray jcontents) {
    const jsize n = env->GetArrayLength(jroles);
    if (n != env->GetArrayLength(jcontents)) throw std::runtime_error("roles/contents size mismatch");
    std::vector<jint> roles(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(jroles, 0, n, roles.data());

    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = false;
    for (jsize i = 0; i < n; ++i) {
        common_chat_msg msg;
        switch (roles[i]) {
            case ROLE_SYSTEM: msg.role = "system"; break;
            case ROLE_USER: msg.role = "user"; break;
            case ROLE_ASSISTANT: msg.role = "assistant"; break;
            default: throw std::runtime_error("unknown role code " + std::to_string(roles[i]));
        }
        auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        msg.content = jbytes_to_std(env, content);
        env->DeleteLocalRef(content);
        inputs.messages.push_back(std::move(msg));
    }
    return common_chat_templates_apply(g_templates.get(), inputs).prompt;
}

// Tokenizes the prompt from byte offset `from` of its concatenated text. Template text is parsed
// for special tokens; message contents (is_input parts) are not, so a user typing "<|im_end|>"
// cannot inject control tokens. BOS is added only when tokenizing from the start.
llama_tokens tokenize_from(const common_chat_prompt & prompt, size_t from) {
    llama_tokens out;
    size_t offset = 0;
    bool first = true;
    for (const auto & part : prompt.parts) {
        const size_t end = offset + part.text.size();
        if (end > from) {
            const size_t skip = from > offset ? from - offset : 0;
            const std::string text = part.text.substr(skip);
            const bool add_special = first && from == 0;
            auto toks = common_tokenize(g_vocab, text, add_special, /* parse_special */ !part.is_input);
            out.insert(out.end(), toks.begin(), toks.end());
            first = false;
        }
        offset = end;
    }
    if (from == 0 && first) {
        // Empty prompt text: still let the tokenizer add BOS if the vocab wants it.
        auto toks = common_tokenize(g_vocab, "", true, true);
        out.insert(out.end(), toks.begin(), toks.end());
    }
    return out;
}

jbyteArray to_jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out != nullptr && !s.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return out;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeInit(JNIEnv * env, jobject /*thiz*/, jstring jlib_dir) {
    llama_log_set(lp_android_log_callback, nullptr);
    const std::string lib_dir = jstring_to_std(env, jlib_dir);
    LOGi("Loading ggml backends from %s", lib_dir.c_str());
    ggml_backend_load_all_from_path(lib_dir.c_str());
    llama_backend_init();
    LOGi("System info: %s", llama_print_system_info());
}

// Returns the context size actually allocated.
extern "C" JNIEXPORT jint JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeLoad(JNIEnv * env, jobject /*thiz*/, jstring jpath,
                                                           jint jn_ctx, jint jn_threads) {
    free_all();
    const std::string path = jstring_to_std(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (g_model == nullptr) {
        throw_java(env, "Could not load model (unsupported or corrupt GGUF?): " + path);
        return 0;
    }
    g_vocab = llama_model_get_vocab(g_model);

    // Same rule as core's ContextSize.clamp: 2048..32768, then the model's training context.
    int n_ctx = std::clamp(static_cast<int>(jn_ctx), CTX_MIN, CTX_MAX);
    const int n_train = llama_model_n_ctx_train(g_model);
    if (n_train > 0) n_ctx = std::min(n_ctx, n_train);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = BATCH_SIZE;
    cparams.n_ubatch = BATCH_SIZE;
    cparams.n_threads = jn_threads;
    cparams.n_threads_batch = jn_threads;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        free_all();
        throw_java(env, "Could not create a llama context of " + std::to_string(n_ctx) + " tokens (out of memory?)");
        return 0;
    }
    g_n_ctx = static_cast<int>(llama_n_ctx(g_ctx));
    g_batch = common_batch(g_ctx);
    try {
        g_templates = common_chat_templates_init(g_model, "");
    } catch (const std::exception & e) {
        free_all();
        throw_java(env, std::string("Could not parse the model's chat template: ") + e.what());
        return 0;
    }
    LOGi("Loaded %s: n_ctx=%d (requested %d, trained %d), threads=%d", path.c_str(), g_n_ctx,
         static_cast<int>(jn_ctx), n_train, static_cast<int>(jn_threads));
    return g_n_ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeUnload(JNIEnv * /*env*/, jobject /*thiz*/) {
    free_all();
}

// Number of tokens the rendered conversation (with generation prompt) takes.
extern "C" JNIEXPORT jint JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeCountPromptTokens(JNIEnv * env, jobject /*thiz*/,
                                                                        jintArray jroles, jobjectArray jcontents) {
    if (g_ctx == nullptr) { throw_java(env, "No model loaded"); return 0; }
    try {
        return static_cast<jint>(tokenize_from(render(env, jroles, jcontents), 0).size());
    } catch (const std::exception & e) {
        throw_java(env, std::string("Chat template failed: ") + e.what());
        return 0;
    }
}

// Prepares a reply: renders the conversation, reuses the cached prefix where possible and queues
// the remaining prompt tokens for nativePrefillStep. Returns [promptTokens, prefillTokens].
extern "C" JNIEXPORT jintArray JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeBeginReply(JNIEnv * env, jobject /*thiz*/, jintArray jroles,
                                                                 jobjectArray jcontents, jfloat temperature) {
    if (g_ctx == nullptr) { throw_java(env, "No model loaded"); return nullptr; }
    g_pending.clear();
    g_pending_off = 0;

    common_chat_prompt prompt;
    try {
        prompt = render(env, jroles, jcontents);
    } catch (const std::exception & e) {
        throw_java(env, std::string("Chat template failed: ") + e.what());
        return nullptr;
    }
    const std::string full_text = prompt.str();

    // Fast path: the new prompt extends the text already in memory (the usual next turn).
    if (g_cached_text_valid && !g_cached.empty() && full_text.size() > g_cached_text.size() &&
        full_text.compare(0, g_cached_text.size(), g_cached_text) == 0) {
        g_pending = tokenize_from(prompt, g_cached_text.size());
    }
    if (g_pending.empty()) {
        // Token path: keep the longest common token prefix, decode at least one token for logits.
        const llama_tokens full = tokenize_from(prompt, 0);
        if (full.empty()) { throw_java(env, "Empty prompt"); return nullptr; }
        size_t keep = 0;
        while (keep < g_cached.size() && keep < full.size() && g_cached[keep] == full[keep]) ++keep;
        keep = std::min(keep, full.size() - 1);
        if (keep < g_cached.size()) {
            // Hybrid/recurrent models (LFM) cannot drop a partial sequence: start over then.
            if (keep == 0 || !llama_memory_seq_rm(llama_get_memory(g_ctx), 0, static_cast<llama_pos>(keep), -1)) {
                llama_memory_clear(llama_get_memory(g_ctx), true);
                keep = 0;
            }
            g_cached.resize(keep);
        }
        g_pending.assign(full.begin() + static_cast<std::ptrdiff_t>(keep), full.end());
    }
    g_cached_text_valid = false;
    g_pending_full_text = full_text;

    const size_t prompt_tokens = g_cached.size() + g_pending.size();
    if (prompt_tokens >= static_cast<size_t>(g_n_ctx)) {
        g_pending.clear();
        throw_java(env, "Prompt of " + std::to_string(prompt_tokens) + " tokens does not fit the context of " +
                        std::to_string(g_n_ctx));
        return nullptr;
    }

    if (g_sampler != nullptr) common_sampler_free(g_sampler);
    common_params_sampling sparams;
    sparams.temp = temperature;
    g_sampler = common_sampler_init(g_model, sparams);
    if (g_sampler == nullptr) { throw_java(env, "Could not create the sampler"); return nullptr; }

    jint result[2] = { static_cast<jint>(prompt_tokens), static_cast<jint>(g_pending.size()) };
    jintArray out = env->NewIntArray(2);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, 2, result);
    return out;
}

// Decodes up to BATCH_SIZE queued prompt tokens. Returns how many remain.
extern "C" JNIEXPORT jint JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativePrefillStep(JNIEnv * env, jobject /*thiz*/) {
    if (g_ctx == nullptr) { throw_java(env, "No model loaded"); return 0; }
    const size_t n = std::min(g_pending.size() - g_pending_off, static_cast<size_t>(BATCH_SIZE));
    g_batch.clear();
    for (size_t i = 0; i < n; ++i) {
        const size_t idx = g_pending_off + i;
        const bool last = idx + 1 == g_pending.size();
        g_batch.add(g_pending[idx], static_cast<llama_pos>(g_cached.size() + i), 0, last);
    }
    if (n > 0) {
        const int32_t rc = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
        if (rc != 0) {
            reset_cache();
            throw_java(env, "Prompt decoding failed (llama_process returned " + std::to_string(rc) + ")");
            return 0;
        }
        g_cached.insert(g_cached.end(), g_pending.begin() + static_cast<std::ptrdiff_t>(g_pending_off),
                        g_pending.begin() + static_cast<std::ptrdiff_t>(g_pending_off + n));
        g_pending_off += n;
    }
    const size_t remaining = g_pending.size() - g_pending_off;
    if (remaining == 0) {
        g_cached_text = g_pending_full_text;
        g_cached_text_valid = true;
        g_pending.clear();
        g_pending_off = 0;
    }
    return static_cast<jint>(remaining);
}

// Samples and decodes one token. Returns its UTF-8 bytes (possibly a partial character), or null
// at end of generation or when the context is full.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeNextToken(JNIEnv * env, jobject /*thiz*/) {
    if (g_ctx == nullptr || g_sampler == nullptr) { throw_java(env, "No reply in progress"); return nullptr; }
    if (g_cached.size() >= static_cast<size_t>(g_n_ctx)) {
        LOGw("Context full (%d tokens), ending the reply", g_n_ctx);
        return nullptr;
    }
    const llama_token token = common_sampler_sample(g_sampler, g_ctx, -1);
    common_sampler_accept(g_sampler, token, true);
    if (llama_vocab_is_eog(g_vocab, token)) return nullptr;

    const std::string piece = common_token_to_piece(g_ctx, token, true);
    g_batch.clear();
    g_batch.add(token, static_cast<llama_pos>(g_cached.size()), 0, true);
    const int32_t rc = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
    if (rc != 0) {
        reset_cache();
        throw_java(env, "Token decoding failed (llama_process returned " + std::to_string(rc) + ")");
        return nullptr;
    }
    g_cached.push_back(token);
    if (g_cached_text_valid) g_cached_text += piece;
    return to_jbytes(env, piece);
}
