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
| Resident model | LFM3-Omni-2B, Q4_K_M (about 1.3 GB, estimated). Handles text, image in, audio in and speech out |
| Smart model | LFM2.5-8B-A1B, Q4_K_M (about 5 GB, estimated). Downloaded the first time it is needed |
| Swapping | Omni is unloaded and the 8B model loaded, then swapped back. Triggered by the "Think harder" button or Omni's `escalate()` tool |
| Model delivery | Downloaded in the app from Hugging Face LiquidAI GGUF repos over Wi-Fi, with resume and a checksum check. After that, inference needs no network |
| Voice | The model's own speech in and out first. Android's offline `SpeechRecognizer` and system TTS are the fallback, chosen in settings |
| Tools: read | Time, battery, calendar and contacts lookup. Run freely |
| Tools: write | Calendar events, reminders, notes and alarms. **Every write shows a confirmation card** |
| File search | MediaStore metadata (date, place, name), then Omni looks at the top candidates. No background indexing |
| Memory | Chat history in Room plus a short "facts about me" list added to the prompt |
| Context | 8K by default, adjustable up to 32K. Older turns are summarized |
| Acceleration | CPU (ARM i8mm and SME kernels) in v1. NPU later |
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
