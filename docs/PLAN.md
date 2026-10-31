# Liquid Pocket: plan

A private, on-device assistant for a Galaxy S26 Ultra (12 GB RAM) built on Liquid AI's LFM models.
It handles text chat, camera and photos, and voice, and it can take actions on the phone. Everything
runs locally after a one-time model download. It is for personal use and installed by sideloading.

## Settled decisions

| Area | Decision |
|---|---|
| App | Liquid Pocket, `com.vanskis.liquidpocket`, opened from a regular launcher icon |
| Stack | Kotlin and Jetpack Compose |
| Runtime | llama.cpp through JNI, on the CPU, behind an `Engine` interface so LEAP or an NPU backend can be added later |
| Main model | LFM3-1.7B (`LiquidAI/LFM3-1.7B-GGUF`, Q4_0, about 1.05 GB). Text, thinking, tools and web search. A "Think" chip in the composer is on by default; thinking is capped by the Settings "Thinking budget" (2K tokens by default; 256 to 8K or no limit). Superseded the LFM2.5-8B-A1B swap, whose file is deleted on upgrade |
| Media model | LFM3-Omni-2B, Q4_K_M (about 1.3 GB). Loaded on demand (one model in memory at a time) to answer photo and voice messages itself, and to look at photos for tools. Each photo also gets a hidden caption so LFM3-1.7B can follow up |
| Model delivery | Downloaded in the app from Hugging Face LiquidAI GGUF repos over Wi-Fi, with resume and a checksum check. After that, inference needs no network |
| Voice | Omni speaks its own replies; Android TTS reads LFM3-1.7B replies. Android `SpeechRecognizer` is the dictation fallback. Live voice mode: full-screen orb, ends a turn on ~0.8 s of silence, barge-in interrupts; the user picks Omni (fast, own voice) or LFM3-1.7B + Android voice (thinking, search) |
| Web search | `web_search` (DuckDuckGo lite, Wikipedia fallback) and `fetch_page` (Jina reader). Runs freely with a "Searched" chip and sources; Settings switch turns it off. The only data that leaves the phone |
| Documents | PDF and text attachments and share-ins. Text layer via a PDF library, ML Kit on-device OCR for scanned pages, BM25 keyword retrieval of relevant chunks |
| Tools: read | Time, battery, calendar and contacts lookup. Run freely |
| Tools: write | Calendar events, reminders, notes and alarms. **Every write shows a confirmation card** |
| File search | MediaStore metadata (date, place, name), then Omni looks at the top candidates. No background indexing |
| Memory | Chat history in Room plus a short "facts about me" list added to the prompt |
| Context | 8K by default, adjustable up to 32K, with an estimated memory use shown in Settings. A ring by the composer shows fill (amber 80%, red 95%; tap for details and Compact now). At 85% older turns are auto-summarized behind an "Earlier messages summarized" divider |
| Look | Gemini-style: colorful, big greeting with suggestion chips, Markdown and code blocks with copy, collapsible "Thought for Xs", tokens/s under replies |
| Access | Launcher icon, share target (text, links, photos, PDFs), home-screen widget, Quick Settings tile |
| Extras | Search across all chats, custom instructions with presets, export or share a chat |
| Acceleration | CPU by default. Opt-in "Use NPU (experimental)" on llama.cpp's Hexagon backend (Snapdragon 8 Elite Gen 5, Hexagon v81) with automatic CPU fallback and a CPU-vs-NPU benchmark |
| Build | GitHub Actions builds a release APK signed with a keystore stored in repo secrets |

## Milestones (each one ends with an installable APK)

1. **M1: text chat.** Gradle project, llama.cpp submodule with an NDK/CMake build, JNI `Engine`,
   model download manager, streaming chat UI, CI workflow that produces a signed APK.
2. **M2: vision.** Camera and photo picker, image input to Omni (the mtmd projector).
3. **M3: voice.** Model-native speech in and out, the Android ASR/TTS fallback, push-to-talk UI.
4. **M4: tools and memory.** Tool-call parser, read and write tools with confirmation cards,
   MediaStore search, Room memory, the 8B-A1B swap and `escalate()`.

## Open risks (not verified)

Research could read only search-result summaries. huggingface.co and docs.liquid.ai were blocked
from the research session.

- **Omni speech output in llama.cpp.** The least proven part, and the reason for the Android fallback.
- **LFM3 tool-call format.** LFM2 and LFM2.5 use `<|tool_call_start|>[fn(arg="x")]<|tool_call_end|>`.
  Check LFM3's chat template from the GGUF before M4.
- **LEAP support for LFM3.** Not confirmed, which is why llama.cpp is the primary runtime.
- **Third-party NPU access on the S26 Ultra.** Not confirmed, so the NPU is deferred.
- **License.** LFM Open License v1.0: free below $10M annual revenue. Personal use is fine.

## M2–M4 build-out (all at once)

The `Engine` contract (engine/.../Engine.kt) was extended first so the engine and app work can
proceed in parallel: `capabilities`, `EngineConfig.mmprojPath`, images/audio/tool calls on
`ChatMessage`, `GenerationParams.tools`/`speak`, and `chat()` streaming `ReplyChunk`s.

llama.cpp at the pin has everything native needs in `tools/mtmd`: image and audio input
(`mtmd_tokenize`, `mtmd_helper_eval_chunks`) and LFM3 speech output (`mtmd_gen_*`,
`MTMD_GEN_AUDIO_TYPE_LFM3A`; the server's realtime endpoint is the reference). Tool calls use the
LFM3 template's `<tool_call><function=…><parameter=…>` format, parsed by `common_chat_parse`.

Waves: (1) engine multimodal input + tools, core mmproj download + 8B offered, app Room history
and UI polish; (2) engine speech output, app tools + memory + 8B swap, app camera/photos/voice.

## v2 (decided 2026-10-30)

Replaces the 8B swap with LFM3-1.7B as the main model and adds the look, context, search, documents,
access and NPU rows above. Risks: LFM3 template detection in llama.cpp (use the GGUF's Jinja
template, not ChatML); the Hexagon backend is experimental (unsigned-PD refusal on retail firmware,
v81 output reports, conv layers likely on CPU); DuckDuckGo lite is unofficial and may block.

## v3 (decided 2026-10-30)

- **Long replies:** a reply runs until it ends or the context is full; a cut-off reply (context
  full or Stop) gets a Continue chip, which sends "Continue from where you stopped." through
  autocompact. Thinking is capped (Settings, "Thinking budget": 2K by default, 256 to 8K or no limit), then the engine closes the think block and the
  answer follows. Think harder is removed.
- **Math:** offline JLaTeXMath, inline in replies and user bubbles. `$…$` (strict, so prices stay
  text), `$$…$$`, `\(…\)`, `\[…\]`; code is never math; a formula that does not parse shows its
  source in monospace; editing shows the raw text.
- **Composer:** two rows: the field full width (up to 6 lines), then +, Think, mic, the context
  ring and Send.
- **offer_choices(question, options):** chips under the reply, a tap sends the option, "Type my
  own…" focuses the field; only the newest offer is live; the call ends the turn; live voice reads
  the options aloud. New "Interactive story" preset.

## v4 (decided 2026-10-30)

- **Math:** each formula is drawn into a software bitmap; one still blank after drawing shows its
  source instead of an empty gap.
- **NPU:** LFM3 stays on llama.cpp's Hexagon backend (no other runtimes or models). llama.cpp is
  moved to upstream master (aa588a3) for the newest ggml-hexagon; burst power mode and op fusion
  are on by default. The benchmark's NPU run is traced: layers offloaded, buffer sizes, graph
  splits, the backend's warnings, and the share of ops on the NPU with the CPU ops named. The
  verdict is plain: within about 15% of the CPU is "about the same: the NPU is not speeding this
  model up". If the DSP refuses the session (retail firmware), the note says so.
- **Thinking:** "Thinking · 12s · 340 tokens" while it runs, then "Thought for 12s · 340 tokens".
- **Fade-in:** each streamed chunk fades in over 300 ms; finished replies show at once.
- **Settings:** edits are a draft until Save in the top bar; leaving with unsaved edits asks Save,
  Discard or Cancel. Changes made elsewhere meanwhile (the composer's Think chip) are kept.
- **Themes:** Aurora (gradient accents), Clay (warm, serif headings), Mono (black and white) and
  Wallpaper (Material You, the default). Each sets colors, heading font and bubble shape and
  follows the system's light or dark mode.
- **Mic:** the on-device recognizer, then the regular one, then the model's own audio input.

## v5 (decided 2026-10-30)

- **Deferred tools:** only the core tools are offered up front; tool_search lists the rest (math,
  weather, phone controls and others) and loads one on request.
- **Math:** the calculate tool runs math.js in an isolated JavaScript sandbox: 64-digit decimals,
  fractions, algebra, units. Offline, no files or network.
- **Environment anchor:** each user message starts with a [Sent ...] line: date, time and phone
  state. The model relies on it and does not mention it unless it matters.
- **Phone controls:** flashlight, volume, Do Not Disturb and similar, via tool_search.
- **Skills:** typing / in the composer lists built-in skills (useful ones and fun ones such as
  stories) and the user's own; the picked skill applies to the chat until dropped.
- **Heretic:** Settings has a Standard/Heretic choice per model; Heretic downloads from a
  user-entered Hugging Face GGUF repo (Q4_K_M first, then other quants).
- **Live camera:** live voice can turn the camera on (back first, with flip and a small preview).
  One frame per turn, taken when the user stops speaking, goes to Omni with the audio.
- **NPU auto-pick:** the benchmark measures reply speed, prompt-reading speed and battery per reply
  on the CPU and NPU. The NPU is kept on only where it wins (faster without clearly more battery).
  It runs once quietly after the first model load, and again on Run benchmark; the switch stays as
  a manual override.
- **Math rendering:** \[...\] and \(...\) render like $$...$$ and $...$.

## v7: faster and sturdier with the models as they are (decided 2026-10-31)

No change to the models: no training, adapters or merging. Each item lands as its own commit with
its own tests, in this order. Items 1, 2 and 4 come first; measure before building 1 and 2.

0. **Measure first.** On the phone: memory with LFM3-1.7B and Omni both loaded; time to restore a
   saved 8K and 32K chat state against re-reading it; time to first token today. Record the numbers
   here before starting 1 and 2.
1. **Both models loaded.** Keep LFM3-1.7B and Omni resident so photo and voice turns need no swap.
   Falls back to swapping when memory is short (low-memory signal, or a failed second load).
2. **Saved chat state.** Save each chat's KV cache to a file (`llama_state_seq_save_file`) after a
   reply and restore it when the chat reopens or its model comes back, instead of re-prefilling.
   Files are tied to the model file and quant and deleted when either changes, when the chat is
   deleted, or past a size cap.
3. **Shared prompt prefix.** The system prompt and tool list are cached once and reused across chats
   and turns.
4. **Strict tool calls.** Tool-call arguments are always generated under the grammar built from the
   offered tools' schemas, so a malformed call cannot be produced. Thinking, answer and tool calls
   come from llama.cpp's chat parser as separate parts instead of marker scanning, where the
   template supports it; marker scanning stays as the fallback.
   **Done (6f9f523):** this was already true: llama.cpp enforces the tool grammar by
   rejection sampling, and tool calls come from the template's parser. Kept only a guard so a
   template without a parser falls back to the reply filter instead of showing raw markup.
5. **Engine in its own process.** A native crash restarts the engine only; the chat screen stays.
6. **Lookup decoding.** n-gram (prompt lookup) speculative decoding, which needs no draft model; on
   only where it measures faster.
7. **Per-phase accelerator.** Prompt reading and decoding each run on the CPU or NPU, whichever the
   benchmark found faster, backing off to the CPU when the phone is hot.
8. **Turn recorder and replay.** Each turn's prompt tokens, sampling, seed, tool results and timings
   are recorded; recorded turns replay deterministically as tests and benchmarks.
9. **Local full build.** A container with the Android SDK, NDK and Hexagon toolchain so the whole
   app builds and tests locally, not only in CI.
