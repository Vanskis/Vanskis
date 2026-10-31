// JNI bridge between LlamaNative.kt and llama.cpp + libmtmd (pinned at b11960).
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
//     ggml_backend_reg_t ggml_backend_load(const char * path);   // null when the backend does not init
//     size_t ggml_backend_reg_dev_count(ggml_backend_reg_t reg);
//     ggml_backend_dev_t ggml_backend_reg_dev_get(ggml_backend_reg_t reg, size_t index);
//     const char * ggml_backend_dev_name(ggml_backend_dev_t device);
//   include/llama.h (device selection)
//     llama_model_params { ggml_backend_dev_t * devices;   // NULL-terminated; NULL = every device
//                          int32_t n_gpu_layers; ... }     // default -1: all layers offloaded
//   ggml/src/ggml-hexagon (NPU builds, built as liblp-hexagon.so)
//     ggml_backend_hexagon_reg() returns null when libcdsprpc.so cannot be loaded; it opens no DSP
//     session. Each device opens its FastRPC session lazily (ggml_backend_hexagon_device_context::
//     session()), on the first buffer alloc or supports_op, i.e. during llama_model_load_from_file.
//     A refusal there (e.g. remote_session_control(unsign) failed) throws std::runtime_error, which
//     llama_model_load catches and logs, so the load returns null. The DSP loads
//     file:///libggml-htp-v<arch>.so from ADSP_LIBRARY_PATH. Compute errors are GGML_ABORTs.
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
//                   // enable_thinking is passed to the jinja template as its enable_thinking variable
//                   // (common_chat_template_direct_apply_impl); LFM3's ends the generation prompt with
//                   // "<think>" when true, "<think></think>" when false.
//     common_chat_params::prompt is a common_chat_prompt { parts: [{ text, is_input }] }; when the vocab
//     adds BOS, the template's leading bos_token text is already stripped from parts[0].
//     common_chat_templates_inputs { messages, tools, parallel_tool_calls, add_generation_prompt, use_jinja,
//                                    enable_thinking, reasoning_format, ... }
//     common_chat_msg { role, content, tool_calls, tool_name, ... }; common_chat_tool_call { name, arguments, id }
//     common_chat_tool { name, description, parameters }   // parameters: JSON schema as a string
//     common_chat_params { format, prompt, grammar, grammar_lazy, generation_prompt, thinking_start_tag,
//                          thinking_end_tags, grammar_triggers, preserved_tokens, parser, ... }
//                   // LFM3's template (it has <tool_call>, <function=, <parameter=) maps to
//                   // common_chat_params_init_qwen3_coder: thinking_start_tag "<think>", thinking_end_tags
//                   // { "\n</think>", "</think>", "<tool_call>" }, lazy grammar trigger word "<tool_call>".
//     common_chat_input common_chat_input_tokenize(const llama_vocab * vocab, const std::string & text);
//     void common_chat_input::append(const std::string & piece, llama_token token);
//     common_chat_parser_params { format, generation_prompt, debug, parser }
//     common_chat_msg common_chat_parse(const common_chat_input & input, bool is_partial,
//                                       const common_chat_parser_params & params);   // throws on a mismatch
//   common/common.h
//     common_grammar(common_grammar_type t, std::string g); common_grammar_trigger { type, value, token }
//   common/sampling.h
//     void common_sampling_add_preserved_tokens(common_params_sampling &, const llama_vocab *, const std::vector<std::string> &);
//     void common_sampling_add_grammar_triggers(common_params_sampling &, const llama_vocab *, std::vector<common_grammar_trigger>);
//     common_sampler * common_sampler_init(const llama_model * model, common_params_sampling & params);
//     void        common_sampler_free(common_sampler * gsmpl);
//     llama_token common_sampler_sample(common_sampler * gsmpl, llama_context * ctx, int idx, bool grammar_first = false);
//     void        common_sampler_accept(common_sampler * gsmpl, llama_token token, bool is_generated);
//   tools/mtmd/mtmd.h
//     const char * mtmd_default_marker(void);
//     mtmd_context_params mtmd_context_params_default(void);   // { use_gpu, n_threads, media_marker, warmup, ... }
//     mtmd_context * mtmd_init_from_file(const char * mmproj_fname, const llama_model * text_model,
//                                        const mtmd_context_params ctx_params);
//     void mtmd_free(mtmd_context * ctx);
//     bool mtmd_support_vision(const mtmd_context * ctx);
//     bool mtmd_support_audio(const mtmd_context * ctx);
//     int  mtmd_get_audio_sample_rate(const mtmd_context * ctx);   // -1 without audio
//     mtmd_bitmap * mtmd_bitmap_init(uint32_t nx, uint32_t ny, const unsigned char * data);   // RGB; null data = placeholder
//     mtmd_bitmap * mtmd_bitmap_init_from_audio(size_t n_samples, const float * data);        // null data = placeholder
//     void mtmd_bitmap_set_id(mtmd_bitmap * bitmap, const char * id);
//     void mtmd_bitmap_free(mtmd_bitmap * bitmap);
//     mtmd_input_chunks * mtmd_input_chunks_init(void);  size_t mtmd_input_chunks_size(const mtmd_input_chunks *);
//     const mtmd_input_chunk * mtmd_input_chunks_get(const mtmd_input_chunks * chunks, size_t idx);
//     void mtmd_input_chunks_free(mtmd_input_chunks * chunks);
//     mtmd_input_chunk_type mtmd_input_chunk_get_type(const mtmd_input_chunk * chunk);
//     const llama_token * mtmd_input_chunk_get_tokens_text(const mtmd_input_chunk * chunk, size_t * n_tokens_output);
//     size_t      mtmd_input_chunk_get_n_tokens(const mtmd_input_chunk * chunk);
//     llama_pos   mtmd_input_chunk_get_n_pos(const mtmd_input_chunk * chunk);
//     const char * mtmd_input_chunk_get_id(const mtmd_input_chunk * chunk);   // the bitmap id, media chunks
//     struct mtmd_input_text { text, text_len, add_special, parse_special };
//     struct mtmd_input_part { const mtmd_input_text * text; const mtmd_bitmap * bitmap; };   // exactly one set
//     int32_t mtmd_tokenize_from_parts(const mtmd_context * ctx, mtmd_input_chunks * output,
//                                      const mtmd_input_part * const * parts, size_t n_parts, bool add_special);
//                   // per-part parse_special; adds the model's media begin/end tokens and BOS like mtmd_tokenize
//   tools/mtmd/mtmd-helper.h
//     int32_t mtmd_helper_eval_chunk_single(mtmd_context * ctx, llama_context * lctx, const mtmd_input_chunk * chunk,
//                                           llama_pos n_past, llama_seq_id seq_id, int32_t n_batch,
//                                           bool logits_last, llama_pos * new_n_past);
//     mtmd_helper::gen_audio { init(lctx, mctx); valid(); set_input(inp); step_prompt(n_batch);
//                              step_gen(sampled, h_state, &h_state_out, &stop); next_is_text();
//                              logit_bias(); get_output(&rate, &data, &len); flush(); }
//     mtmd_helper::gen_audio::inp { data: mtmd_helper_gen_audio_inp { stream, seq_id, n_past, out_mode,
//                                   seed, out_type, ... }; set_prompt(text); get(); }
//   tools/mtmd/mtmd.h (speech output)
//     mtmd_gen_audio_info mtmd_gen_audio_get_info(const mtmd_context * ctx);   // { type, sample_rate, ... }
//   include/llama.h (speech output)
//     void        llama_set_embeddings(llama_context * ctx, bool embeddings);   // true: every token is output
//     float *     llama_get_embeddings_ith(llama_context * ctx, int32_t i);
//     int32_t     llama_model_n_embd(const llama_model * model);
//     llama_pos   llama_memory_seq_pos_max(llama_memory_t mem, llama_seq_id seq_id);
//     bool        llama_vocab_is_control(const llama_vocab * vocab, llama_token token);

#include <jni.h>

#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <memory>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "sampling.h"
#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <map>
#include <mutex>

namespace {

// Mirrors com.vanskis.liquidpocket.core.ContextSize.
constexpr int CTX_MIN = 2048;
constexpr int CTX_MAX = 262144;
constexpr int BATCH_SIZE = 512;

// The Hexagon backend's file name in NPU builds (OUTPUT_NAME in CMakeLists.txt, NPU_LIB in
// LlamaEngine.kt): not libggml-hexagon.so, so ggml_backend_load_all_from_path() never loads it.
constexpr const char * NPU_LIB = "liblp-hexagon.so";
// Where FastRPC looks for DSP libraries by default; kept after the app's own directory.
constexpr const char * ADSP_SYSTEM_PATHS = "/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";

// Role and media codes shared with LlamaNative.kt.
constexpr jint ROLE_SYSTEM = 0;
constexpr jint ROLE_USER = 1;
constexpr jint ROLE_ASSISTANT = 2;
constexpr jint ROLE_TOOL = 3;
constexpr jint MEDIA_IMAGE = 0;
constexpr jint MEDIA_AUDIO = 1;

// One element of the prompt or reply as held in memory: a text token, or a whole media chunk
// (only ever kept or dropped as a whole).
struct unit {
    llama_token token = LLAMA_TOKEN_NULL;  // LLAMA_TOKEN_NULL for a media chunk
    std::string media_id;                  // media: content hash of the image or clip
    int32_t n_tokens = 1;
    llama_pos n_pos = 1;
    const mtmd_input_chunk * chunk = nullptr;  // pending media only, owned by g_pending_chunks

    bool same(const unit & o) const {
        return token == o.token && (token != LLAMA_TOKEN_NULL || (media_id == o.media_id && n_tokens == o.n_tokens));
    }
};

// The Hexagon backend once nativeInitNpu has loaded it (stays loaded for the process).
ggml_backend_reg_t g_npu_reg = nullptr;

// Where an NPU load put the model, for nativeAcceleratorReport: llama.cpp's own lines about it
// (layers offloaded, buffer sizes, graph splits) and the Hexagon backend's warnings, collected by
// lp_log while an NPU load is traced, plus which device computed each op (count_op).
std::mutex g_trace_mu;
bool g_trace = false;                   // nativeSetOpTrace: trace the next NPU load
bool g_tracing = false;                 // the loaded model is traced
std::vector<std::string> g_trace_notes;
long g_ops_npu = 0;
long g_ops_cpu = 0;
std::map<std::string, long> g_cpu_ops;  // op name -> times computed on the CPU

void lp_log(enum ggml_log_level level, const char * text, void * user) {
    lp_android_log_callback(level, text, user);
    if (text == nullptr) return;
    std::lock_guard<std::mutex> lock(g_trace_mu);
    if (!g_tracing) return;
    const std::string line(text);
    const bool placement = line.find("offloaded ") != std::string::npos || line.find("model buffer size") != std::string::npos ||
                           line.find("splits = ") != std::string::npos;
    const bool npu_problem = line.rfind("ggml-hex:", 0) == 0 && (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR);
    if ((placement || npu_problem) && g_trace_notes.size() < 24) {
        size_t end = line.find_last_not_of(" \n");
        size_t start = line.find_first_not_of(' ');
        if (end != std::string::npos && start <= end) g_trace_notes.push_back(line.substr(start, end - start + 1));
    }
}

// The scheduler's eval callback on a traced load. Asked about each node before it is computed
// (ask=true); answering false for all of them observes nothing, so only the split's own sync is
// added. The node's output buffer says which device computed it.
bool count_op(struct ggml_tensor * t, bool ask, void * /*user*/) {
    if (!ask || t == nullptr || t->buffer == nullptr) return false;
    switch (t->op) {
        case GGML_OP_NONE: case GGML_OP_VIEW: case GGML_OP_RESHAPE: case GGML_OP_PERMUTE: case GGML_OP_TRANSPOSE:
            return false;
        default:
            break;
    }
    ggml_backend_dev_t dev = ggml_backend_buft_get_device(ggml_backend_buffer_get_type(t->buffer));
    const bool cpu = dev == nullptr || ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU;
    std::lock_guard<std::mutex> lock(g_trace_mu);
    if (cpu) {
        ++g_ops_cpu;
        ++g_cpu_ops[ggml_op_desc(t)];
    } else {
        ++g_ops_npu;
    }
    return false;
}

llama_model * g_model = nullptr;
llama_model * g_extra_model = nullptr;
llama_context * g_ctx = nullptr;
const llama_vocab * g_vocab = nullptr;
mtmd_context * g_mtmd = nullptr;
common_chat_templates_ptr g_templates;
common_sampler * g_sampler = nullptr;
common_batch g_batch;
int g_n_ctx = 0;

// Units held in memory for sequence 0: g_n_past positions and g_n_tokens cells in total.
std::vector<unit> g_cached;
llama_pos g_n_past = 0;
size_t g_n_tokens = 0;
// The text g_cached stands for (rendered prompt plus generated pieces); valid only after a
// completed text-only prefill, so a new prompt that starts with it can skip re-tokenizing the history.
std::string g_cached_text;
bool g_cached_text_valid = false;

// Prompt units still to decode for the current reply, and the text they complete.
std::vector<unit> g_pending;
size_t g_pending_off = 0;
size_t g_pending_tokens = 0;  // n_tokens of g_pending[g_pending_off..]
mtmd::input_chunks_ptr g_pending_chunks;
std::string g_pending_full_text;
bool g_pending_has_media = false;

// The current reply, for the template's own parser.
common_chat_parser_params g_parser;
common_chat_input g_generated;

// Speech output: with an LFM3-Audio projector, a spoken reply is driven by libmtmd's gen-audio
// helper the way llama-server's realtime slot drives a response (tools/server/server-realtime.cpp,
// server_realtime_slot::response_start/response_step): the helper decodes the generation prompt
// itself, opens an interleaved text+audio block and decodes every text token and audio frame;
// this side samples the text tokens and collects the PCM.
mtmd_helper::gen_audio g_gen;           // valid only when the projector can continue a conversation in speech
bool g_speak = false;                   // the current reply is spoken (its generation prompt is held back)
std::string g_speak_header;             // that generation prompt, decoded by g_gen
llama_pos g_speak_n_past = 0;           // where it goes: the end of the prefilled prompt
bool g_speak_started = false;           // g_gen has decoded the header
bool g_speak_done = false;              // the spoken reply has ended
bool g_speak_in_memory = false;         // memory holds a spoken reply past g_n_past that g_cached does not
std::vector<float> g_h_state;           // the backbone hidden state the next audio frame starts from
llama_token g_sampled = LLAMA_TOKEN_NULL;  // the text token g_gen takes next, NULL before an audio frame
std::set<llama_token> g_preserved;      // control tokens the parser needs in the text (tool calls)
std::vector<float> g_audio;             // PCM not yet taken by nativeTakeAudio

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

std::vector<std::string> jbytes_array_to_std(JNIEnv * env, jobjectArray arr) {
    std::vector<std::string> out;
    const jsize n = arr == nullptr ? 0 : env->GetArrayLength(arr);
    for (jsize i = 0; i < n; ++i) {
        auto b = static_cast<jbyteArray>(env->GetObjectArrayElement(arr, i));
        out.push_back(jbytes_to_std(env, b));
        env->DeleteLocalRef(b);
    }
    return out;
}

std::vector<jint> jints_to_std(JNIEnv * env, jintArray arr) {
    const jsize n = arr == nullptr ? 0 : env->GetArrayLength(arr);
    std::vector<jint> out(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(arr, 0, n, out.data());
    return out;
}

jbyteArray to_jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out != nullptr && !s.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return out;
}

jobjectArray to_jbytes_array(JNIEnv * env, const std::vector<std::string> & v) {
    jclass cls = env->FindClass("[B");
    if (cls == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(v.size()), cls, nullptr);
    for (size_t i = 0; out != nullptr && i < v.size(); ++i) {
        jbyteArray b = to_jbytes(env, v[i]);
        env->SetObjectArrayElement(out, static_cast<jsize>(i), b);
        env->DeleteLocalRef(b);
    }
    return out;
}

// FNV-1a over the media bytes: the id that tells a cached image or clip from a new one.
std::string media_hash(jint kind, uint32_t a, uint32_t b, const void * data, size_t n) {
    uint64_t h = 1469598103934665603ULL;
    auto mix = [&h](const unsigned char * p, size_t len) {
        for (size_t i = 0; i < len; ++i) { h ^= p[i]; h *= 1099511628211ULL; }
    };
    const uint32_t head[3] = { static_cast<uint32_t>(kind), a, b };
    mix(reinterpret_cast<const unsigned char *>(head), sizeof(head));
    mix(static_cast<const unsigned char *>(data), n);
    char buf[17];
    std::snprintf(buf, sizeof(buf), "%016llx", static_cast<unsigned long long>(h));
    return buf;
}

void reset_pending() {
    g_pending.clear();
    g_pending_off = 0;
    g_pending_tokens = 0;
    g_pending_chunks.reset();
    g_pending_full_text.clear();
    g_pending_has_media = false;
}

void reset_speech() {
    g_speak = false;
    g_speak_header.clear();
    g_speak_n_past = 0;
    g_speak_started = false;
    g_speak_done = false;
    g_h_state.clear();
    g_sampled = LLAMA_TOKEN_NULL;
    g_preserved.clear();
    g_audio.clear();
}

void reset_cache() {
    if (g_ctx != nullptr) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        llama_set_embeddings(g_ctx, false);
    }
    g_cached.clear();
    g_n_past = 0;
    g_n_tokens = 0;
    g_cached_text.clear();
    g_cached_text_valid = false;
    g_speak_in_memory = false;
    reset_pending();
}

void cache_push(const unit & u) {
    g_cached.push_back(u);
    g_cached.back().chunk = nullptr;
    g_n_past += u.n_pos;
    g_n_tokens += static_cast<size_t>(u.n_tokens);
}

// A spoken reply leaves its header, text tokens and audio frames in memory past g_n_past; a
// re-rendered conversation holds the reply as text only and never matches them. Account for them
// as one opaque unit (no prompt unit has this id), so the next prompt keeps only what precedes it.
// Also ends the embeddings output the helper needs, which would slow every prefill batch down.
void settle_speech() {
    if (!g_speak_in_memory) return;
    g_speak_in_memory = false;
    llama_set_embeddings(g_ctx, false);
    const llama_pos end = llama_memory_seq_pos_max(llama_get_memory(g_ctx), 0) + 1;
    if (end > g_n_past) {
        unit u;
        u.media_id = "spoken-reply";
        u.n_pos = end - g_n_past;
        u.n_tokens = static_cast<int32_t>(u.n_pos);
        cache_push(u);
    }
    g_cached_text_valid = false;
}

// Drops the last n bytes of a rendered prompt, all from template parts (the generation prompt);
// false if they reach into user input.
bool strip_tail(common_chat_prompt & prompt, size_t n) {
    while (n > 0 && !prompt.parts.empty()) {
        auto & last = prompt.parts.back();
        if (last.is_input) return false;
        const size_t k = std::min(n, last.text.size());
        last.text.resize(last.text.size() - k);
        n -= k;
        if (last.text.empty()) prompt.parts.pop_back();
    }
    return n == 0;
}

// Moves the PCM the helper produced since the last call into g_audio.
void collect_audio() {
    int32_t rate = 0;
    const char * data = nullptr;
    size_t len = 0;
    if (g_gen.get_output(&rate, &data, &len) == 0 && len > 0) {
        const auto * pcm = reinterpret_cast<const float *>(data);
        g_audio.insert(g_audio.end(), pcm, pcm + len / sizeof(float));
    }
}

// The next text token, if the helper takes one next (server-realtime.cpp, sample_next).
void speak_sample_next() {
    g_sampled = LLAMA_TOKEN_NULL;
    if (!g_gen.next_is_text()) return;
    g_sampled = common_sampler_sample(g_sampler, g_ctx, -1);
    common_sampler_accept(g_sampler, g_sampled, true);
}

// Control tokens (the text/audio switches) stay out of the text, except the preserved ones the
// parser needs (server-realtime.cpp, server_realtime_slot::piece).
std::string speak_piece(llama_token tok) {
    const bool p = g_preserved.count(tok) > 0;
    return p || !llama_vocab_is_control(g_vocab, tok) ? common_token_to_piece(g_ctx, tok, p) : "";
}

void free_all() {
    reset_cache();
    reset_speech();
    g_gen.ctx.reset();
    if (g_sampler != nullptr) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    g_templates.reset();
    g_parser = common_chat_parser_params();
    g_generated = common_chat_input();
    g_batch = common_batch();
    if (g_mtmd != nullptr) { mtmd_free(g_mtmd); g_mtmd = nullptr; }
    if (g_ctx != nullptr) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_extra_model != nullptr) { llama_model_free(g_extra_model); g_extra_model = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_n_ctx = 0;
}

// The conversation as passed from LlamaNative.kt.
struct prompt_args {
    jintArray roles;
    jobjectArray contents;    // byte[] per message
    jobjectArray tool_names;  // byte[] per message, empty unless a tool turn
    jobjectArray tool_calls;  // byte[][] per message: name, arguments, name, arguments, ...
    jintArray media_msg;      // per media item: index of its message (per message: images, then audio)
    jintArray media_kind;     // MEDIA_IMAGE or MEDIA_AUDIO
    jintArray media_dims;     // per item: width, height (image) or sample count, 0 (audio)
    jobjectArray media_data;  // per item: byte[] RGB or float[] PCM; null (array or entry) = placeholder
    jobjectArray tools;       // byte[] triples: name, description, parameters JSON
};

using bitmaps = std::vector<mtmd::bitmap_ptr>;

// The media items as mtmd bitmaps, in prompt order. Placeholders (no data) tokenize to the right
// number of tokens without being decoded.
bitmaps make_bitmaps(JNIEnv * env, const prompt_args & a) {
    const auto kinds = jints_to_std(env, a.media_kind);
    const auto dims = jints_to_std(env, a.media_dims);
    if (dims.size() != kinds.size() * 2) throw std::runtime_error("media arrays size mismatch");
    bitmaps out;
    for (size_t i = 0; i < kinds.size(); ++i) {
        jobject data = a.media_data == nullptr ? nullptr : env->GetObjectArrayElement(a.media_data, static_cast<jsize>(i));
        const auto d0 = static_cast<uint32_t>(dims[2 * i]);
        const auto d1 = static_cast<uint32_t>(dims[2 * i + 1]);
        mtmd_bitmap * bm = nullptr;
        std::string id;
        if (kinds[i] == MEDIA_IMAGE) {
            if (data == nullptr) {
                bm = mtmd_bitmap_init(d0, d1, nullptr);
            } else {
                auto arr = static_cast<jbyteArray>(data);
                const size_t n = static_cast<size_t>(env->GetArrayLength(arr));
                if (n != static_cast<size_t>(d0) * d1 * 3) throw std::runtime_error("image is not width*height*3 bytes");
                std::vector<unsigned char> rgb(n);
                env->GetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<jbyte *>(rgb.data()));
                bm = mtmd_bitmap_init(d0, d1, rgb.data());
                id = media_hash(kinds[i], d0, d1, rgb.data(), n);
            }
        } else if (kinds[i] == MEDIA_AUDIO) {
            if (data == nullptr) {
                bm = mtmd_bitmap_init_from_audio(d0, nullptr);
            } else {
                auto arr = static_cast<jfloatArray>(data);
                const size_t n = static_cast<size_t>(env->GetArrayLength(arr));
                std::vector<float> pcm(n);
                env->GetFloatArrayRegion(arr, 0, static_cast<jsize>(n), pcm.data());
                bm = mtmd_bitmap_init_from_audio(n, pcm.data());
                id = media_hash(kinds[i], static_cast<uint32_t>(n), 0, pcm.data(), n * sizeof(float));
            }
        } else {
            throw std::runtime_error("unknown media kind " + std::to_string(kinds[i]));
        }
        if (data != nullptr) env->DeleteLocalRef(data);
        if (bm == nullptr) throw std::runtime_error("could not create a media bitmap");
        if (!id.empty()) mtmd_bitmap_set_id(bm, id.c_str());
        out.emplace_back(bm);
    }
    return out;
}

// Renders the conversation with the model's own jinja template from the GGUF (never the legacy
// built-in templates, which take LFM3 for plain ChatML), ending with the assistant generation prompt.
// `think` is the template's enable_thinking (ignored by templates without thinking). With a
// projector loaded, a user turn gets one media marker per media item before its text. Throws
// std::exception on template errors.
common_chat_params render(JNIEnv * env, const prompt_args & a, bool with_markers, bool think) {
    const auto roles = jints_to_std(env, a.roles);
    const auto contents = jbytes_array_to_std(env, a.contents);
    const auto tool_names = jbytes_array_to_std(env, a.tool_names);
    const auto media_msg = jints_to_std(env, a.media_msg);
    if (contents.size() != roles.size() || tool_names.size() != roles.size() ||
        env->GetArrayLength(a.tool_calls) != static_cast<jsize>(roles.size())) {
        throw std::runtime_error("message arrays size mismatch");
    }
    const std::string marker = mtmd_default_marker();

    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = think;
    // Lets the template's parser take the think block apart from the content.
    inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
    inputs.parallel_tool_calls = true;
    for (size_t i = 0; i < roles.size(); ++i) {
        common_chat_msg msg;
        switch (roles[i]) {
            case ROLE_SYSTEM: msg.role = "system"; break;
            case ROLE_USER: msg.role = "user"; break;
            case ROLE_ASSISTANT: msg.role = "assistant"; break;
            case ROLE_TOOL: msg.role = "tool"; break;
            default: throw std::runtime_error("unknown role code " + std::to_string(roles[i]));
        }
        std::string content = contents[i];
        if (with_markers && g_mtmd != nullptr) {
            // A marker typed by the user would be taken for a media item.
            for (size_t p; (p = content.find(marker)) != std::string::npos;) content.erase(p, marker.size());
            std::string markers;
            for (jint m : media_msg) if (m == static_cast<jint>(i)) markers += marker;
            content = markers + content;
        }
        // An assistant message's thinking leads its content between \x01 and \x02 (NativePrompt.packed).
        if (roles[i] == ROLE_ASSISTANT && !content.empty() && content[0] == '\x01') {
            const size_t end = content.find('\x02');
            if (end != std::string::npos) {
                msg.reasoning_content = content.substr(1, end - 1);
                content.erase(0, end + 1);
            }
        }
        msg.content = std::move(content);
        msg.tool_name = tool_names[i];
        auto calls = static_cast<jobjectArray>(env->GetObjectArrayElement(a.tool_calls, static_cast<jsize>(i)));
        const auto call_fields = jbytes_array_to_std(env, calls);
        if (calls != nullptr) env->DeleteLocalRef(calls);
        for (size_t c = 0; c + 1 < call_fields.size(); c += 2) {
            common_chat_tool_call call;
            call.name = call_fields[c];
            call.arguments = call_fields[c + 1];
            msg.tool_calls.push_back(std::move(call));
        }
        inputs.messages.push_back(std::move(msg));
    }
    const auto tool_fields = jbytes_array_to_std(env, a.tools);
    for (size_t t = 0; t + 2 < tool_fields.size(); t += 3) {
        common_chat_tool tool;
        tool.name = tool_fields[t];
        tool.description = tool_fields[t + 1];
        tool.parameters = tool_fields[t + 2];
        inputs.tools.push_back(std::move(tool));
    }
    return common_chat_templates_apply(g_templates.get(), inputs);
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

std::vector<unit> text_units(const llama_tokens & tokens) {
    std::vector<unit> out(tokens.size());
    for (size_t i = 0; i < tokens.size(); ++i) out[i].token = tokens[i];
    return out;
}

// Tokenizes a prompt with media through libmtmd: the markers render() put into the message
// contents become the bitmaps, in order, and the special-token rule of tokenize_from still holds.
mtmd::input_chunks_ptr tokenize_media(const common_chat_prompt & prompt, const bitmaps & bms) {
    const std::string marker = mtmd_default_marker();
    std::vector<std::string> texts;              // stable storage for the text parts
    std::vector<bool> parse_special;
    std::vector<std::pair<bool, size_t>> order;  // (is_text, index into texts or bms)
    size_t n_bm = 0;
    auto add_text = [&](std::string t, bool special) {
        if (t.empty()) return;
        order.emplace_back(true, texts.size());
        texts.push_back(std::move(t));
        parse_special.push_back(special);
    };
    for (const auto & part : prompt.parts) {
        if (!part.is_input) { add_text(part.text, true); continue; }
        size_t pos = 0;
        for (size_t m; (m = part.text.find(marker, pos)) != std::string::npos; pos = m + marker.size()) {
            add_text(part.text.substr(pos, m - pos), false);
            if (n_bm >= bms.size()) throw std::runtime_error("more media markers than media items");
            order.emplace_back(false, n_bm++);
        }
        add_text(part.text.substr(pos), false);
    }
    if (n_bm != bms.size()) throw std::runtime_error("the chat template dropped media markers");

    std::vector<mtmd_input_text> in_texts(texts.size());
    for (size_t i = 0; i < texts.size(); ++i) in_texts[i] = { texts[i].data(), texts[i].size(), false, parse_special[i] };
    std::vector<mtmd_input_part> parts(order.size());
    std::vector<const mtmd_input_part *> part_ptrs(order.size());
    for (size_t i = 0; i < order.size(); ++i) {
        if (order[i].first) parts[i] = { &in_texts[order[i].second], nullptr };
        else parts[i] = { nullptr, bms[order[i].second].get() };
        part_ptrs[i] = &parts[i];
    }
    mtmd::input_chunks_ptr chunks(mtmd_input_chunks_init());
    const int32_t rc = mtmd_tokenize_from_parts(g_mtmd, chunks.get(), part_ptrs.data(), part_ptrs.size(), true);
    if (rc != 0) throw std::runtime_error("media tokenization failed (mtmd_tokenize_from_parts returned " + std::to_string(rc) + ")");
    return chunks;
}

std::vector<unit> chunk_units(const mtmd_input_chunks * chunks) {
    std::vector<unit> out;
    for (size_t i = 0; i < mtmd_input_chunks_size(chunks); ++i) {
        const mtmd_input_chunk * c = mtmd_input_chunks_get(chunks, i);
        if (mtmd_input_chunk_get_type(c) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t n = 0;
            const llama_token * toks = mtmd_input_chunk_get_tokens_text(c, &n);
            for (size_t k = 0; k < n; ++k) { unit u; u.token = toks[k]; out.push_back(std::move(u)); }
        } else {
            unit u;
            const char * id = mtmd_input_chunk_get_id(c);
            u.media_id = id != nullptr ? id : "";
            u.n_tokens = static_cast<int32_t>(mtmd_input_chunk_get_n_tokens(c));
            u.n_pos = mtmd_input_chunk_get_n_pos(c);
            u.chunk = c;
            out.push_back(std::move(u));
        }
    }
    return out;
}

size_t sum_tokens(const std::vector<unit> & units) {
    size_t n = 0;
    for (const auto & u : units) n += static_cast<size_t>(u.n_tokens);
    return n;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeInit(JNIEnv * env, jobject /*thiz*/, jstring jlib_dir) {
    llama_log_set(lp_log, nullptr);
    mtmd_helper_log_set(lp_log, nullptr);
    const std::string lib_dir = jstring_to_std(env, jlib_dir);
    // The DSP opens the skels (libggml-htp-v<arch>.so, next to the NPU backend) from
    // ADSP_LIBRARY_PATH when the backend opens its sessions. Set here, before llama.cpp starts any
    // thread of its own: setenv is not safe while other native threads may call getenv.
    const std::string npu_lib = lib_dir + "/" + NPU_LIB;
    if (access(npu_lib.c_str(), R_OK) == 0) {
        const char * prev = getenv("ADSP_LIBRARY_PATH");
        const std::string adsp = lib_dir + ";" + (prev != nullptr && prev[0] != '\0' ? prev : ADSP_SYSTEM_PATHS);
        setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
        LOGi("ADSP_LIBRARY_PATH=%s", adsp.c_str());
        // The Hexagon session's settings, read when its backend starts (ggml-hexagon.cpp, getenv
        // GGML_HEXAGON_*). Spelled out rather than left to the defaults: the DSP clocked for burst
        // (DCVS off) and fused ops. Anything already set in the environment wins.
        setenv("GGML_HEXAGON_POWER_MODE", "burst", 0);
        setenv("GGML_HEXAGON_OPFUSION", "1", 0);
    }
    LOGi("Loading ggml backends from %s", lib_dir.c_str());
    ggml_backend_load_all_from_path(lib_dir.c_str());
    llama_backend_init();
    LOGi("System info: %s", llama_print_system_info());
}

// Loads the Hexagon (NPU) backend from jlib_dir, after nativeInit. Returns null on success (or if
// it is already loaded), otherwise why the NPU cannot be used. Never throws: the caller falls back
// to the CPU.
extern "C" JNIEXPORT jstring JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeInitNpu(JNIEnv * env, jobject /*thiz*/, jstring jlib_dir) {
    if (g_npu_reg != nullptr) return nullptr;
    const std::string lib_dir = jstring_to_std(env, jlib_dir);
    const std::string lib = lib_dir + "/" + NPU_LIB;
    std::string reason;
    if (access(lib.c_str(), R_OK) != 0) {
        reason = "this build has no NPU support";
    } else {
        // ADSP_LIBRARY_PATH was set by nativeInit.
        LOGi("Loading the NPU backend %s", lib.c_str());
        try {
            g_npu_reg = ggml_backend_load(lib.c_str());
            if (g_npu_reg == nullptr) {
                reason = "the NPU backend did not start (no Hexagon DSP, or libcdsprpc.so is unavailable)";
            } else if (ggml_backend_reg_dev_count(g_npu_reg) == 0) {
                reason = "the NPU backend found no devices";
            }
        } catch (const std::exception & e) {
            g_npu_reg = nullptr;
            reason = std::string("the NPU backend did not start: ") + e.what();
        } catch (...) {
            g_npu_reg = nullptr;
            reason = "the NPU backend did not start (unknown error)";
        }
    }
    if (reason.empty()) {
        LOGi("NPU backend ready: %zu device(s), first %s", ggml_backend_reg_dev_count(g_npu_reg),
             ggml_backend_dev_name(ggml_backend_reg_dev_get(g_npu_reg, 0)));
        return nullptr;
    }
    LOGw("NPU unavailable: %s", reason.c_str());
    return env->NewStringUTF(reason.c_str());
}

// Returns the context size actually allocated. jmmproj may be null (no projector). jnpu: offload
// every layer to the NPU devices (nativeInitNpu must have succeeded); otherwise CPU only.
extern "C" JNIEXPORT jint JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeLoad(JNIEnv * env, jobject /*thiz*/, jstring jpath,
                                                           jstring jmmproj, jint jn_ctx, jint jn_threads,
                                                           jboolean jnpu) {
    free_all();
    const std::string path = jstring_to_std(env, jpath);
    const bool npu = jnpu == JNI_TRUE;
    {
        std::lock_guard<std::mutex> lock(g_trace_mu);
        g_tracing = npu && g_trace;
        g_trace_notes.clear();
        g_ops_npu = 0;
        g_ops_cpu = 0;
        g_cpu_ops.clear();
    }
    if (npu && g_npu_reg == nullptr) {
        throw_java(env, "The NPU backend is not loaded");
        return 0;
    }

    // The defaults offload every layer (n_gpu_layers = -1) to every registered GPU-type device,
    // which would put a CPU-mode model on the NPU once its backend is loaded. So the device list is
    // always explicit: the NPU devices for the NPU, none (CPU only, as llama's `--device none`) and
    // no offloaded layers for the CPU.
    llama_model_params mparams = llama_model_default_params();
    std::vector<ggml_backend_dev_t> devices;
    if (npu) {
        for (size_t i = 0; i < ggml_backend_reg_dev_count(g_npu_reg); ++i) {
            devices.push_back(ggml_backend_reg_dev_get(g_npu_reg, i));
        }
        mparams.n_gpu_layers = -1;
    } else {
        mparams.n_gpu_layers = 0;
    }
    devices.push_back(nullptr);
    mparams.devices = devices.data();
    try {
        g_model = llama_model_load_from_file(path.c_str(), mparams);
    } catch (const std::exception & e) {
        g_model = nullptr;
        throw_java(env, std::string("Could not load model: ") + e.what());
        return 0;
    }
    if (g_model == nullptr) {
        // llama_model_load catches the NPU backend's errors (the DSP refusing the session, rpcmem or
        // fastrpc_mmap failures) and only logs them, so on the NPU a null model is most likely the
        // NPU's doing: say so, and LlamaEngine retries on the CPU, which reports a bad file itself.
        if (npu) {
            throw_java(env, "The NPU could not load the model (the DSP refused it or ran out of memory; see the log)");
        } else {
            throw_java(env, "Could not load model (unsupported or corrupt GGUF?): " + path);
        }
        return 0;
    }
    g_vocab = llama_model_get_vocab(g_model);

    // Same rule as core's ContextSize.clamp: 2048..262144, then the model's training context.
    int n_ctx = std::clamp(static_cast<int>(jn_ctx), CTX_MIN, CTX_MAX);
    const int n_train = llama_model_n_ctx_train(g_model);
    if (n_train > 0) n_ctx = std::min(n_ctx, n_train);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = BATCH_SIZE;
    cparams.n_ubatch = BATCH_SIZE;
    cparams.n_threads = jn_threads;
    cparams.n_threads_batch = jn_threads;
    if (g_tracing) cparams.cb_eval = count_op;
    try {
        g_ctx = llama_init_from_model(g_model, cparams);
    } catch (const std::exception & e) {
        g_ctx = nullptr;
        free_all();
        throw_java(env, std::string("Could not create a llama context: ") + e.what());
        return 0;
    }
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
    if (jmmproj != nullptr) {
        const std::string mmproj = jstring_to_std(env, jmmproj);
        mtmd_context_params mp = mtmd_context_params_default();
        mp.use_gpu = false;
        mp.print_timings = false;
        mp.n_threads = jn_threads;
        mp.media_marker = mtmd_default_marker();
        g_mtmd = mtmd_init_from_file(mmproj.c_str(), g_model, mp);
        if (g_mtmd == nullptr) {
            free_all();
            throw_java(env, "Could not load the multimodal projector (wrong or corrupt mmproj GGUF?): " + mmproj);
            return 0;
        }
        // Only the LFM3-Audio pipeline can continue a conversation in speech (supports_continue in
        // tools/mtmd/mtmd-helper-gen.cpp); the other ones are text-to-speech only.
        if (mtmd_gen_audio_get_info(g_mtmd).type == MTMD_GEN_AUDIO_TYPE_LFM3A) {
            g_gen.init(g_ctx, g_mtmd);
            if (!g_gen.valid()) LOGw("The projector generates audio, but its pipeline could not be set up");
        }
    }
    LOGi("Loaded %s: n_ctx=%d (requested %d, trained %d), threads=%d, projector=%s, accelerator=%s", path.c_str(),
         g_n_ctx, static_cast<int>(jn_ctx), n_train, static_cast<int>(jn_threads), g_mtmd != nullptr ? "yes" : "no",
         npu ? "NPU" : "CPU");
    return g_n_ctx;
}

// [vision, audioIn, audioInSampleRate, audioOut, audioOutSampleRate] of the loaded projector,
// zeros without one.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeCapabilities(JNIEnv * env, jobject /*thiz*/) {
    jint caps[5] = { 0, 0, 0, 0, 0 };
    if (g_mtmd != nullptr) {
        caps[0] = mtmd_support_vision(g_mtmd) ? 1 : 0;
        caps[1] = mtmd_support_audio(g_mtmd) ? 1 : 0;
        caps[2] = caps[1] != 0 ? std::max(0, mtmd_get_audio_sample_rate(g_mtmd)) : 0;
        if (g_gen.valid()) {
            caps[4] = std::max(0, mtmd_gen_audio_get_info(g_mtmd).sample_rate);
            caps[3] = caps[4] > 0 ? 1 : 0;
        }
    }
    jintArray out = env->NewIntArray(5);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, 5, caps);
    return out;
}

// Whether the next NPU load is traced for nativeAcceleratorReport. A traced model runs a little
// slower (each split is waited for), so only the benchmark traces.
extern "C" JNIEXPORT void JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeSetOpTrace(JNIEnv * /*env*/, jobject /*thiz*/, jboolean on) {
    std::lock_guard<std::mutex> lock(g_trace_mu);
    g_trace = on == JNI_TRUE;
}

// Where the traced NPU load put the model, one fact per line: llama.cpp's placement lines, the
// Hexagon backend's warnings, and the share of ops the NPU computed with the ones left to the CPU.
// Null when the loaded model is not traced.
extern "C" JNIEXPORT jstring JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeAcceleratorReport(JNIEnv * env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_trace_mu);
    if (!g_tracing) return nullptr;
    std::string out;
    for (const auto & note : g_trace_notes) out += note + "\n";
    const long total = g_ops_npu + g_ops_cpu;
    if (total > 0) {
        out += "ops on the NPU: " + std::to_string(g_ops_npu * 100 / total) + "% (" + std::to_string(g_ops_npu) + " of " +
               std::to_string(total) + ")";
        if (!g_cpu_ops.empty()) {
            std::vector<std::pair<std::string, long>> ops(g_cpu_ops.begin(), g_cpu_ops.end());
            std::sort(ops.begin(), ops.end(), [](const auto & a, const auto & b) { return a.second > b.second; });
            out += "; on the CPU:";
            for (size_t i = 0; i < ops.size() && i < 6; ++i) {
                out += std::string(i == 0 ? " " : ", ") + ops[i].first + " x" + std::to_string(ops[i].second);
            }
        }
        out += "\n";
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeUnload(JNIEnv * /*env*/, jobject /*thiz*/) {
    free_all();
}

// Number of tokens the rendered conversation (with generation prompt) takes; media chunks count
// by mtmd_input_chunk_get_n_tokens, and media data may be null (placeholders count the same).
extern "C" JNIEXPORT jint JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeCountPromptTokens(
        JNIEnv * env, jobject /*thiz*/, jintArray roles, jobjectArray contents, jobjectArray tool_names,
        jobjectArray tool_calls, jintArray media_msg, jintArray media_kind, jintArray media_dims,
        jobjectArray media_data, jobjectArray tools, jboolean jthink) {
    if (g_ctx == nullptr) { throw_java(env, "No model loaded"); return 0; }
    const prompt_args a { roles, contents, tool_names, tool_calls, media_msg, media_kind, media_dims, media_data, tools };
    try {
        const bool has_media = env->GetArrayLength(media_kind) > 0;
        const auto params = render(env, a, has_media, jthink == JNI_TRUE);
        if (!has_media) return static_cast<jint>(tokenize_from(params.prompt, 0).size());
        if (g_mtmd == nullptr) throw std::runtime_error("media needs a multimodal projector");
        const auto bms = make_bitmaps(env, a);
        const auto chunks = tokenize_media(params.prompt, bms);
        return static_cast<jint>(mtmd_helper_get_n_tokens(chunks.get()));
    } catch (const std::exception & e) {
        throw_java(env, std::string("Chat template failed: ") + e.what());
        return 0;
    }
}

// Prepares a reply: renders the conversation, reuses the cached prefix where possible and queues
// the remaining prompt for nativePrefillStep. Returns [promptTokens, prefillTokens]. With jspeak
// and a projector that speaks, the reply is spoken as well (see g_gen); otherwise jspeak is ignored.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeBeginReply(
        JNIEnv * env, jobject /*thiz*/, jintArray roles, jobjectArray contents, jobjectArray tool_names,
        jobjectArray tool_calls, jintArray media_msg, jintArray media_kind, jintArray media_dims,
        jobjectArray media_data, jobjectArray tools, jfloat temperature, jboolean jspeak, jboolean jthink,
        jfloatArray sampling_f, jintArray sampling_i) {
    if (g_ctx == nullptr) { throw_java(env, "No model loaded"); return nullptr; }
    settle_speech();
    reset_speech();
    reset_pending();
    const prompt_args a { roles, contents, tool_names, tool_calls, media_msg, media_kind, media_dims, media_data, tools };
    const bool has_media = env->GetArrayLength(media_kind) > 0;
    const bool think = jthink == JNI_TRUE;

    common_chat_params params;
    bool speak = false;
    try {
        params = render(env, a, has_media, think);
        if (jspeak && g_gen.valid()) {
            // The helper decodes the generation prompt itself and opens the spoken block after it,
            // as the realtime slot passes it the assistant header (server-realtime.cpp, on_response_create).
            const std::string & header = params.generation_prompt;
            const std::string text = params.prompt.str();
            speak = !header.empty() && text.size() > header.size() &&
                    text.compare(text.size() - header.size(), header.size(), header) == 0 &&
                    strip_tail(params.prompt, header.size());
            if (speak) {
                g_speak_header = header;
            } else {
                params = render(env, a, has_media, think);
                LOGw("The prompt does not end with the generation prompt; replying in text only");
            }
        }
        if (has_media) {
            if (g_mtmd == nullptr) throw std::runtime_error("media needs a multimodal projector");
            const auto bms = make_bitmaps(env, a);
            g_pending_chunks = tokenize_media(params.prompt, bms);
        }
    } catch (const std::exception & e) {
        reset_pending();
        throw_java(env, std::string("Chat template failed: ") + e.what());
        return nullptr;
    }
    const std::string full_text = params.prompt.str();

    // Fast path: a text-only prompt that extends the text already in memory (the usual next turn).
    if (!has_media && g_cached_text_valid && !g_cached.empty() && full_text.size() > g_cached_text.size() &&
        full_text.compare(0, g_cached_text.size(), g_cached_text) == 0) {
        g_pending = text_units(tokenize_from(params.prompt, g_cached_text.size()));
    }
    if (g_pending.empty()) {
        // Unit path: keep the longest common prefix of whole units (a media chunk matches only as a
        // whole, by content hash), and decode at least one unit for logits.
        const std::vector<unit> full =
            has_media ? chunk_units(g_pending_chunks.get()) : text_units(tokenize_from(params.prompt, 0));
        if (full.empty()) { reset_pending(); throw_java(env, "Empty prompt"); return nullptr; }
        size_t keep = 0;
        while (keep < g_cached.size() && keep < full.size() && g_cached[keep].same(full[keep])) ++keep;
        keep = std::min(keep, full.size() - 1);
        if (keep < g_cached.size()) {
            llama_pos keep_pos = 0;
            size_t keep_tokens = 0;
            for (size_t i = 0; i < keep; ++i) { keep_pos += g_cached[i].n_pos; keep_tokens += g_cached[i].n_tokens; }
            // Hybrid/recurrent models (LFM) cannot drop a partial sequence: start over then.
            if (keep == 0 || !llama_memory_seq_rm(llama_get_memory(g_ctx), 0, keep_pos, -1)) {
                llama_memory_clear(llama_get_memory(g_ctx), true);
                keep = 0;
                keep_pos = 0;
                keep_tokens = 0;
            }
            g_cached.resize(keep);
            g_n_past = keep_pos;
            g_n_tokens = keep_tokens;
        }
        g_pending.assign(full.begin() + static_cast<std::ptrdiff_t>(keep), full.end());
    }
    g_cached_text_valid = false;
    g_pending_full_text = full_text;
    g_pending_has_media = has_media;
    g_pending_tokens = sum_tokens(g_pending);

    size_t header_tokens = 0;
    if (speak) {
        g_speak_n_past = g_n_past;
        for (const auto & u : g_pending) g_speak_n_past += u.n_pos;
        header_tokens = common_tokenize(g_vocab, g_speak_header, false, true).size();
    }
    const size_t prompt_tokens = g_n_tokens + g_pending_tokens + header_tokens;
    if (prompt_tokens >= static_cast<size_t>(g_n_ctx)) {
        reset_pending();
        throw_java(env, "Prompt of " + std::to_string(prompt_tokens) + " tokens does not fit the context of " +
                        std::to_string(g_n_ctx));
        return nullptr;
    }

    // The reply is parsed by the template's own parser; its tool-call grammar (lazy: active once a
    // tool call starts) keeps calls well-formed, as llama-server does.
    g_parser = common_chat_parser_params();
    g_parser.format = params.format;
    g_parser.generation_prompt = common_chat_input_tokenize(g_vocab, params.generation_prompt);
    g_parser.parser = std::move(params.parser);
    g_generated = common_chat_input();

    if (g_sampler != nullptr) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    common_params_sampling sparams;
    sparams.temp = temperature;
    // [topP, minP, repeatPenalty] and [topK, seed]; a negative seed keeps the random default.
    if (sampling_f != nullptr && env->GetArrayLength(sampling_f) >= 3) {
        jfloat f[3];
        env->GetFloatArrayRegion(sampling_f, 0, 3, f);
        sparams.top_p = f[0];
        sparams.min_p = f[1];
        sparams.penalty_repeat = f[2];
    }
    if (sampling_i != nullptr && env->GetArrayLength(sampling_i) >= 2) {
        jint i[2];
        env->GetIntArrayRegion(sampling_i, 0, 2, i);
        sparams.top_k = i[0];
        if (i[1] >= 0) sparams.seed = (uint32_t) i[1];
    }
    if (!params.grammar.empty()) sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_TOOL_CALLS, params.grammar);
    sparams.grammar_lazy = params.grammar_lazy;
    sparams.generation_prompt = params.generation_prompt;
    common_sampling_add_preserved_tokens(sparams, g_vocab, params.preserved_tokens);
    common_sampling_add_grammar_triggers(sparams, g_vocab, params.grammar_triggers);
    if (speak) {
        // server-realtime.cpp, server_realtime_slot::response_start: continue the sequence after the
        // prompt in the interleaved text+audio mode (the helper appends <|mixed_start|> to the
        // header), streaming PCM, and add the pipeline's bias to the text sampler.
        mtmd_helper::gen_audio::inp inp;
        inp.set_prompt(g_speak_header);
        inp.data.seq_id = 0;
        inp.data.n_past = g_speak_n_past;
        inp.data.seed = sparams.seed;
        inp.data.stream = true;
        inp.data.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
        inp.data.out_mode = MTMD_HELPER_GEN_AUDIO_OUT_MIXED;
        if (g_gen.set_input(inp.get()) == 0) {
            for (const auto & b : g_gen.logit_bias()) sparams.logit_bias.push_back(b);
            g_preserved = sparams.preserved_tokens;
            g_speak = true;
        } else {
            // Decode the held-back header as part of the prompt and reply in text.
            LOGw("Could not start a spoken reply; replying in text only");
            const auto toks = common_tokenize(g_vocab, g_speak_header, false, true);
            for (const auto & u : text_units(toks)) g_pending.push_back(u);
            g_pending_tokens += toks.size();
            g_pending_full_text += g_speak_header;
            g_speak_header.clear();
            header_tokens = 0;
        }
    }
    g_sampler = common_sampler_init(g_model, sparams);
    if (g_sampler == nullptr) { reset_pending(); reset_speech(); throw_java(env, "Could not create the sampler"); return nullptr; }

    jint result[2] = { static_cast<jint>(prompt_tokens), static_cast<jint>(g_pending_tokens + header_tokens) };
    jintArray out = env->NewIntArray(2);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, 2, result);
    return out;
}

// What the streamed text of a reply must hold back, for the given conversation and tools, as four
// arrays: [the thinking start tag, if any], [thinking end tags], [words that start a tool call],
// [the thinking start tag, if the generation prompt already opens the think block].
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeReplyMarkers(
        JNIEnv * env, jobject /*thiz*/, jintArray roles, jobjectArray contents, jobjectArray tool_names,
        jobjectArray tool_calls, jobjectArray tools, jboolean jthink) {
    if (g_ctx == nullptr) { throw_java(env, "No model loaded"); return nullptr; }
    jintArray none = env->NewIntArray(0);
    const prompt_args a { roles, contents, tool_names, tool_calls, none, none, none, nullptr, tools };
    std::vector<std::string> start, ends, tool_starts, opened;
    try {
        const auto params = render(env, a, /* with_markers */ false, jthink == JNI_TRUE);
        const std::string & tag = params.thinking_start_tag;
        if (!tag.empty()) {
            start.push_back(tag);
            // LFM3 with thinking: the generation prompt ends "<|im_start|>assistant\n<think>", so the
            // model starts with its reasoning and never writes the tag itself.
            std::string gen = params.generation_prompt;
            while (!gen.empty() && std::isspace(static_cast<unsigned char>(gen.back()))) gen.pop_back();
            if (gen.size() >= tag.size() && gen.compare(gen.size() - tag.size(), tag.size(), tag) == 0) {
                opened.push_back(tag);
            }
        }
        ends = params.thinking_end_tags;
        for (const auto & t : params.grammar_triggers) {
            if (t.type == COMMON_GRAMMAR_TRIGGER_TYPE_WORD) tool_starts.push_back(t.value);
        }
    } catch (const std::exception & e) {
        throw_java(env, std::string("Chat template failed: ") + e.what());
        return nullptr;
    }
    jclass cls = env->FindClass("[[B");
    if (cls == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(4, cls, nullptr);
    const std::vector<std::string> * groups[4] = { &start, &ends, &tool_starts, &opened };
    for (jsize i = 0; out != nullptr && i < 4; ++i) {
        jobjectArray g = to_jbytes_array(env, *groups[i]);
        env->SetObjectArrayElement(out, i, g);
        env->DeleteLocalRef(g);
    }
    return out;
}

// Decodes the next queued prompt step: up to BATCH_SIZE text tokens, or one whole media chunk
// (encoded by the projector, then decoded). Returns how many prompt tokens remain.
extern "C" JNIEXPORT jint JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativePrefillStep(JNIEnv * env, jobject /*thiz*/) {
    if (g_ctx == nullptr) { throw_java(env, "No model loaded"); return 0; }
    if (g_pending_off < g_pending.size() && g_pending[g_pending_off].token == LLAMA_TOKEN_NULL) {
        const unit & u = g_pending[g_pending_off];
        const bool last = g_pending_off + 1 == g_pending.size();
        llama_pos new_n_past = g_n_past;
        const int32_t rc = mtmd_helper_eval_chunk_single(g_mtmd, g_ctx, u.chunk, g_n_past, 0, BATCH_SIZE, last, &new_n_past);
        if (rc != 0) {
            reset_cache();
            throw_java(env, "Media decoding failed (mtmd_helper_eval_chunk_single returned " + std::to_string(rc) + ")");
            return 0;
        }
        cache_push(u);
        g_n_past = new_n_past;
        g_pending_tokens -= static_cast<size_t>(u.n_tokens);
        ++g_pending_off;
    } else {
        g_batch.clear();
        size_t n = 0;
        while (n < static_cast<size_t>(BATCH_SIZE) && g_pending_off + n < g_pending.size() &&
               g_pending[g_pending_off + n].token != LLAMA_TOKEN_NULL) {
            const size_t idx = g_pending_off + n;
            g_batch.add(g_pending[idx].token, g_n_past + static_cast<llama_pos>(n), 0, idx + 1 == g_pending.size());
            ++n;
        }
        if (n > 0) {
            const int32_t rc = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
            if (rc != 0) {
                reset_cache();
                throw_java(env, "Prompt decoding failed (llama_process returned " + std::to_string(rc) + ")");
                return 0;
            }
            for (size_t i = 0; i < n; ++i) cache_push(g_pending[g_pending_off + i]);
            g_pending_off += n;
            g_pending_tokens -= n;
        }
    }
    if (g_pending_off >= g_pending.size()) {
        g_cached_text = g_pending_full_text;
        g_cached_text_valid = !g_pending_has_media;
        for (const auto & u : g_cached) {
            if (u.token == LLAMA_TOKEN_NULL) { g_cached_text_valid = false; break; }
        }
        reset_pending();
        if (g_speak && !g_speak_started) {
            // server-realtime.cpp, response_start: the helper decodes the header, the hidden state of
            // its last token starts the first audio frame, and the first text token is sampled.
            llama_set_embeddings(g_ctx, true);
            g_speak_in_memory = true;
            g_speak_started = true;
            int32_t rc;
            while ((rc = g_gen.step_prompt(BATCH_SIZE)) > 0) {}
            const float * h = rc == 0 ? llama_get_embeddings_ith(g_ctx, -1) : nullptr;
            if (h == nullptr) {
                reset_cache();
                reset_speech();
                throw_java(env, "Could not start the spoken reply (mtmd_helper_gen_audio_step_prompt returned " +
                                std::to_string(rc) + ")");
                return 0;
            }
            g_h_state.assign(h, h + llama_model_n_embd(g_model));
            speak_sample_next();
        }
        return 0;
    }
    return static_cast<jint>(g_pending_tokens);
}

// One step of a spoken reply, a text token or an audio frame (server-realtime.cpp,
// server_realtime_slot::response_step). Returns the token's text (empty for a frame or a control
// token), or null when the reply has ended; the frame's PCM goes to g_audio.
static jbyteArray speak_step(JNIEnv * env) {
    if (g_speak_done) return nullptr;
    const llama_token sampled = g_sampled;
    const float * h_next = nullptr;
    bool stop = false;
    if (g_gen.step_gen(sampled, g_h_state.data(), &h_next, &stop) != 0) {
        reset_cache();
        reset_speech();
        throw_java(env, "Spoken reply generation failed (mtmd_helper_gen_audio_step_gen)");
        return nullptr;
    }
    std::string piece;
    if (sampled != LLAMA_TOKEN_NULL && !stop) piece = speak_piece(sampled);
    if (h_next != nullptr) g_h_state.assign(h_next, h_next + llama_model_n_embd(g_model));

    // The helper decodes on its own: the reply ends before the context is full.
    const bool ctx_full = llama_memory_seq_pos_max(llama_get_memory(g_ctx), 0) + 2 >= g_n_ctx;
    if (ctx_full && !stop) LOGw("Context full (%d tokens), ending the reply", g_n_ctx);
    const bool done = stop || ctx_full;
    if (!done) speak_sample_next();
    if (done) g_gen.flush();
    collect_audio();
    if (!piece.empty()) g_generated.append(piece, sampled);
    if (done) {
        g_speak_done = true;
        if (piece.empty()) return nullptr;
    }
    return to_jbytes(env, piece);
}

// Samples and decodes one token. Returns its UTF-8 bytes (possibly a partial character), or null
// at end of generation or when the context is full. A spoken reply also returns an empty array for
// a step that only produced audio (see nativeTakeAudio).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeNextToken(JNIEnv * env, jobject /*thiz*/) {
    if (g_ctx == nullptr || g_sampler == nullptr) { throw_java(env, "No reply in progress"); return nullptr; }
    if (g_speak) return speak_step(env);
    if (g_n_tokens >= static_cast<size_t>(g_n_ctx)) {
        LOGw("Context full (%d tokens), ending the reply", g_n_ctx);
        return nullptr;
    }
    const llama_token token = common_sampler_sample(g_sampler, g_ctx, -1);
    common_sampler_accept(g_sampler, token, true);
    if (llama_vocab_is_eog(g_vocab, token)) return nullptr;

    const std::string piece = common_token_to_piece(g_ctx, token, true);
    g_batch.clear();
    g_batch.add(token, g_n_past, 0, true);
    const int32_t rc = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
    if (rc != 0) {
        reset_cache();
        throw_java(env, "Token decoding failed (llama_process returned " + std::to_string(rc) + ")");
        return nullptr;
    }
    unit u;
    u.token = token;
    cache_push(u);
    g_generated.append(piece, token);
    if (g_cached_text_valid) g_cached_text += piece;
    return to_jbytes(env, piece);
}

// Feeds jtext into the reply as if the model had written it (it closes a thinking section that ran
// past its budget). Returns false, feeding nothing, when the context has no room for it.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeForceText(JNIEnv * env, jobject /*thiz*/, jbyteArray jtext) {
    if (g_ctx == nullptr || g_sampler == nullptr || g_speak) { throw_java(env, "No text reply in progress"); return JNI_FALSE; }
    const auto toks = common_tokenize(g_vocab, jbytes_to_std(env, jtext), false, true);
    if (g_n_tokens + toks.size() + 1 >= static_cast<size_t>(g_n_ctx)) return JNI_FALSE;
    for (const llama_token token : toks) {
        common_sampler_accept(g_sampler, token, true);
        g_batch.clear();
        g_batch.add(token, g_n_past, 0, true);
        const int32_t rc = llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
        if (rc != 0) {
            reset_cache();
            throw_java(env, "Token decoding failed (llama_process returned " + std::to_string(rc) + ")");
            return JNI_FALSE;
        }
        unit u;
        u.token = token;
        cache_push(u);
        const std::string piece = common_token_to_piece(g_ctx, token, true);
        g_generated.append(piece, token);
        if (g_cached_text_valid) g_cached_text += piece;
    }
    return JNI_TRUE;
}

// The speech PCM (mono, at the audioOutSampleRate of nativeCapabilities) generated since the last
// call, or null if none. jflush: the reply has ended, so the codec's held-back tail goes out too.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeTakeAudio(JNIEnv * env, jobject /*thiz*/, jboolean jflush) {
    if (jflush && g_speak && g_speak_started) {
        if (g_gen.flush() != 0) LOGw("Could not flush the speech decoder");
        collect_audio();
    }
    if (g_audio.empty()) return nullptr;
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(g_audio.size()));
    if (out != nullptr) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(g_audio.size()), g_audio.data());
    g_audio.clear();
    return out;
}

// Parses the finished reply with the template's parser. Returns [content, name, arguments, name,
// arguments, ...] as UTF-8, or null when the template has no parser (its "content" would be the raw
// reply, markup included) or the reply does not match the template's format.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeParseReply(JNIEnv * env, jobject /*thiz*/) {
    if (g_parser.parser.empty() || g_parser.format == COMMON_CHAT_FORMAT_CONTENT_ONLY) {
        return nullptr;
    }
    std::vector<std::string> out;
    try {
        const common_chat_msg msg = common_chat_parse(g_generated, /* is_partial */ false, g_parser);
        out.push_back(msg.content);
        for (const auto & call : msg.tool_calls) {
            out.push_back(call.name);
            out.push_back(call.arguments.empty() ? "{}" : call.arguments);
        }
    } catch (const std::exception & e) {
        LOGw("Could not parse the reply: %s", e.what());
        return nullptr;
    }
    return to_jbytes_array(env, out);
}

// Loads an extra model (CPU weights only) alongside the chat model for memory measurement.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeLoadExtraModel(JNIEnv * env, jobject /*thiz*/, jstring jpath) {
    if (g_extra_model != nullptr) {
        llama_model_free(g_extra_model);
        g_extra_model = nullptr;
    }
    const std::string path = jstring_to_std(env, jpath);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    // Read the weights into memory: a memory-mapped model is not counted until its pages are touched.
    mparams.load_mode = LLAMA_LOAD_MODE_NONE;
    try {
        g_extra_model = llama_model_load_from_file(path.c_str(), mparams);
    } catch (...) {
        g_extra_model = nullptr;
    }
    return g_extra_model != nullptr ? JNI_TRUE : JNI_FALSE;
}

// Frees the extra model loaded by nativeLoadExtraModel.
extern "C" JNIEXPORT void JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeFreeExtraModel(JNIEnv * /*env*/, jobject /*thiz*/) {
    if (g_extra_model != nullptr) {
        llama_model_free(g_extra_model);
        g_extra_model = nullptr;
    }
}

// Saves sequence 0 state (KV cache + cached tokens) to jpath.
// Returns the number of bytes written, or throws on failure.
extern "C" JNIEXPORT jlong JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeSaveState(JNIEnv * env, jobject /*thiz*/, jstring jpath) {
    if (g_ctx == nullptr) { throw_java(env, "No model loaded"); return 0; }
    const std::string path = jstring_to_std(env, jpath);
    std::vector<llama_token> tokens;
    tokens.reserve(g_cached.size());
    for (const auto & u : g_cached) {
        if (u.token != LLAMA_TOKEN_NULL) tokens.push_back(u.token);
    }
    const size_t bytes = llama_state_seq_save_file(g_ctx, path.c_str(), 0, tokens.data(), tokens.size());
    if (bytes == 0) {
        throw_java(env, "Failed to save sequence state to " + path);
        return 0;
    }
    return static_cast<jlong>(bytes);
}

// Loads sequence 0 state from jpath. Returns number of tokens restored, or throws on failure.
extern "C" JNIEXPORT jint JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeLoadState(JNIEnv * env, jobject /*thiz*/, jstring jpath) {
    if (g_ctx == nullptr) { throw_java(env, "No model loaded"); return 0; }
    const std::string path = jstring_to_std(env, jpath);
    size_t n_tokens = 0;
    const size_t probe = llama_state_seq_load_file(g_ctx, path.c_str(), 0, nullptr, 0, &n_tokens);
    if (probe == 0) {
        throw_java(env, "Failed to read sequence state header from " + path);
        return 0;
    }
    std::vector<llama_token> tokens(n_tokens);
    reset_cache();
    const size_t bytes = llama_state_seq_load_file(g_ctx, path.c_str(), 0, tokens.data(), tokens.size(), &n_tokens);
    if (bytes == 0) {
        throw_java(env, "Failed to load sequence state from " + path);
        return 0;
    }
    for (llama_token tok : tokens) {
        unit u;
        u.token = tok;
        u.n_pos = 1;
        u.n_tokens = 1;
        g_cached.push_back(u);
        g_n_past += 1;
        g_n_tokens += 1;
    }
    return static_cast<jint>(n_tokens);
}

// Clears sequence 0 memory cache and resets pending units.
extern "C" JNIEXPORT void JNICALL
Java_com_vanskis_liquidpocket_engine_LlamaNative_nativeResetCache(JNIEnv * /*env*/, jobject /*thiz*/) {
    reset_cache();
}
