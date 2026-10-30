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
