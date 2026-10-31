# Handover

The state of Liquid Pocket as of 2026-10-31 (commit a15253c on `claude/start-7o65wi`), for whoever
works on it next. Read [README.md](../README.md) for install, signing and llama.cpp updates, and
[PLAN.md](PLAN.md) for the decisions behind each version. This file covers what those don't: how
the code fits together, how to check a change, what was done last, and what is still unverified.

## What it is

An Android app (Kotlin, Jetpack Compose) that runs LFM models on the phone through llama.cpp
(`third_party/llama.cpp`, a pinned submodule). It targets a Galaxy S26 Ultra. Chat works offline once
a model is downloaded from Hugging Face. Package: `com.vanskis.liquidpocket`.

## Modules

| Module | What it holds | Builds without the Android SDK |
| --- | --- | --- |
| `core` | Model catalog, downloads/installer, Hugging Face API, Heretic and custom-model specs, GGUF header reader | Yes: `./gradlew :core:test` |
| `engine` | `Engine` interface, `LlamaEngine`, the JNI bridge `LlamaNative.kt` and `src/main/cpp/lpengine.cpp` | No |
| `app` | Everything else: UI, chat, tools, settings, storage, media | No |

Key places in `app/src/main/kotlin/com/vanskis/liquidpocket/`:

- `LiquidPocketApp.kt`: the app graph (`settings`, `engine`, `installer`, `downloads`, `chats`, …).
  `ACTIVE_MODEL` (LFM3-1.7B, text) and `MEDIA_MODEL` (LFM3-Omni-2B, photos and voice) are set here.
- `chat/ChatController.kt`: the reply loop: model loading and swapping, tool calls, thinking,
  compaction. `chat/ChatViewModel.kt` feeds it settings, picks which model answers (`models`,
  `candidates`, `picked`), and builds `GenerationParams` in `params()`.
- `chat/ModelSwap.kt`: `LoadTarget`, `ModelChoice`, and the text/media routing.
- `settings/SettingsRepository.kt`: `AppSettings` and its DataStore keys. `SettingsViewModel` keeps
  edits as a draft until Save. Exceptions that write at once: custom models (`putCustomModel`,
  `removeCustomModel`), the chat model (`setChatModel`) and thinking (`setThinking`).
- `tools/`: phone tools. Only core tools are offered up front; `tool_search` loads the rest. Math
  goes through a math.js sandbox (`MathTool`, `MathScript`).
- `ui/components/`: Composables, mostly free of Android services, so they compile on the JVM.

## Checking a change

- **CI is the real gate.** `.github/workflows/android.yml` runs on every push. It has two jobs, each
  about 10–13 minutes:
  - **build:** runs `:core:test`, `:engine:testReleaseUnitTest` and `:app:testReleaseUnitTest`, then
    `:app:assembleRelease`. The CPU APK goes to the rolling prerelease
    `latest-<branch-with-slashes-as-dashes>`, e.g.
    https://github.com/Vanskis/Vanskis/releases/tag/latest-claude-start-7o65wi
  - **npu:** builds the Hexagon NPU APK (`-PlpHexagon`). It is only a run artifact named
    `liquid-pocket-npu`, never a release.
- **Locally, `:core:test` needs only a JDK.** The full build needs the Android SDK, NDK and CMake at
  the versions in the workflow.
- **The previous agent's JVM-only harness is not in the repo.** It lived in `scratch/`, which is
  gitignored. That harness compiled the app's pure-Kotlin files with `Engine.kt` and `core`, ran
  `app/src/test`, and compiled `ui/components`. Without an SDK, you can rebuild something similar,
  or push and read CI.
- **Code that only CI compiles:** ViewModels, screens, repositories, workers, `LiquidPocketApp` and
  all native code. Re-read those diffs carefully before pushing.
- **Last known results:**
  - The local harness ran 289 app/core tests with 0 failures.
  - CI run 42 (a15253c) is green on both jobs.
- **Sandbox network:** in the Claude Code cloud sandbox, `huggingface.co` and Google Maven were
  blocked. Hugging Face lookups could only be tested with recorded JSON.

## Conventions

- **Branch:** work on `claude/start-7o65wi` unless told otherwise. Don't open a PR unless asked.
- **Secrets:** never commit `release.jks` or the signing secrets (`LP_KEYSTORE_BASE64`,
  `LP_KEYSTORE_PASSWORD`, `LP_KEY_ALIAS`, `LP_KEY_PASSWORD`).
- **Commits:** no model names or identifiers in commits or code.
- **Writing style:**
  - Code comments are short and say why.
  - Commit messages use a plain-language subject and a body saying what the user gets.
  - UI text is plain and short.
- **Broken builds:** don't push a build you know is broken.
- **Tests:** a fix ships with a test that fails without it. Check that by setting the fix aside,
  running the test, seeing it fail, then restoring the fix.
- **Token use:** the owner asked for low token use and no subagents. Batch reads and keep turns few.

## Done most recently (v6, decided 2026-10-30)

1. **Thinking through tool calls** (13f99ef): the model's reasoning stays in the chat and is handed
   back to the model until the reply ends.
2. **Math nudge** (f56b37e): a message that asks for math gets a one-line hint to use `calculate`,
   and that chat offers `calculate` in full. Logic in `chat/MathHint.kt`.
3. **Indicators** (c658b1a): thinking tok/s, answer tok/s, token count and elapsed time under
   replies.
   - Each has a switch in Settings → Chat indicators.
   - Defaults: speeds and elapsed on, token count off.
   - Code: `Indicators`, `LocalIndicators`, `ChatNumbers.kt`.
4. **Model menu** (459e300): the composer chip (`ui/components/ModelMenu.kt`) replaces the Think chip.
   - Thinking levels: Off, Low 512, Medium 2048, High 8192, Max (`ThinkBudget.PRESETS`). They are
     hidden for a model whose `ModelSpec.thinks` is false.
   - Lists every installed model. Picking one keeps the chat and loads that model.
   - Each reply is labeled with its model (`ChatStore.Turn.model`, stored in the message extras JSON).
5. **Custom models** (180f7ea): Settings → Custom models (`ui/components/CustomModels.kt`).
   - **Adding:** from a Hugging Face GGUF repo (Look up, then pick one file), or by importing a
     `.gguf` from the phone (copied into the models folder and recorded by `ModelInstaller.adopt`).
   - **Detection:**
     - Thinking and tools come from the chat template, via `TemplateTraits`. The template comes from
       `/api/models/<repo>` → `gguf.chat_template`, or from the file's own header via `GgufHeader`.
     - Vision is on when the repo has an mmproj file.
     - Each detected ability has a switch.
   - **Heretic:** a model can have a Heretic repo, which becomes its own menu entry.
   - **Behaviour in chat:**
     - A custom model without tools is offered none.
     - A vision custom model captions photos only when Omni isn't installed.
   - **Downloads:** jobs carry the full spec (`ModelSpecData`), so `ModelDownloadWorker` can rebuild
     specs that are not in the catalog.
6. **Per-model settings** (a15253c): Settings → Per-model settings
   (`ui/components/ModelTuningSection.kt`, `settings/ModelTuning.kt`).
   - Options: top-p, top-k, min-p, repeat penalty, longest reply, seed, extra instructions, and a
     reset to defaults.
   - Storage: keyed by model id. A Heretic build falls back to its base model's settings.
   - Engine path: `GenerationParams.sampling` (`Sampling`) reaches llama.cpp through two new
     `nativeBeginReply` arguments (`samplingF`, `samplingI`). Defaults equal llama.cpp's, so a model
     with no settings replies as before.

## Unverified: needs the phone

- **Display math:** check that it renders after the fix in 92eabdc. The reported case was
  `\[ 4 + 5 + x + 0 = 5 \times 4 = 20. \]` followed by "Solving for \(x\)".
- **Omni thinking:** `ModelCatalog.LFM3_OMNI_2B` has `thinks = false`. That is an assumption, not a
  verified fact. If Omni can think, set it to true so the menu shows thinking levels for it.
- **Custom models end to end:**
  - a Hugging Face lookup on a real repo;
  - an import of a large file;
  - a vision model with its mmproj;
  - a Heretic pairing.
- **Sampling:** whether the new sampling settings visibly change replies, and whether a fixed seed
  gives repeatable output.

## Known limits and places to be careful

- **Changing the JNI signature:** keep `LlamaNative.nativeBeginReply` (Kotlin) and
  `Java_..._nativeBeginReply` in `lpengine.cpp` in step. Only CI compiles the native side.
- **Split models:** repos that split a model into several files (`-00001-of-0000N`) are filtered out
  of the custom-model file list.
- **Imports:** an import runs in `SettingsViewModel`'s scope. Leaving Settings mid-copy can leave a
  `.part` file behind.
- **Per-model settings in chat:** they apply to the model selected in the menu. A photo or voice turn
  answered by Omni still uses the selected model's sampling.
- **Model ids:**
  - custom ids start with `custom-` (`CustomModel.idFor`);
  - Heretic ids are `<base id>-heretic-<repo slug>` (`Heretic.ID_SUFFIX`).
  - `ModelTuning.of` and the model menu rely on these patterns.
- **Writing settings:** `SettingsViewModel.save()` applies the draft edits on top of what is stored.
  A new setting that must write at once needs its own repository method, like `setChatModel`.

## Possible next steps

None of these are decided. Ask the owner first.

- Support split (multi-file) GGUF downloads for custom models.
- Let a vision custom model see photos directly, rather than only captioning them when Omni is
  missing.
- Apply each model's own per-model settings to turns answered by the media model.
- Record v6 in `docs/PLAN.md`.
