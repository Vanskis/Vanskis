# Liquid Pocket

A private assistant for a Galaxy S26 Ultra that runs Liquid AI's LFM models entirely on the phone.
The app downloads the model once from Hugging Face; after that, chatting needs no network and
nothing leaves the device. It is built with Kotlin and Jetpack Compose, and the model runs on
the CPU through [llama.cpp](https://github.com/ggml-org/llama.cpp).

Milestone 1 is text chat with LFM3-Omni-2B (Q4_K_M, about 1.3 GB). See [docs/PLAN.md](docs/PLAN.md)
for the roadmap (vision, voice, tools and memory).

## Install on the phone

Every push to a branch builds an APK and publishes it as a prerelease named `latest-<branch>`,
with `/` in the branch name replaced by `-`.

1. On the phone, open the `latest-claude-start-7o65wi` prerelease in the browser:
   <https://github.com/Vanskis/Vanskis/releases/tag/latest-claude-start-7o65wi>.
   If the repository is private, sign in to GitHub in that browser first.
2. Tap `liquid-pocket-<build>.apk` to download it.
3. Open the download. If Android asks, allow **Install unknown apps** for the browser
   (Settings → Apps → *your browser* → Install unknown apps), then go back and tap **Install**.
4. Open **Liquid Pocket**. On the **Setup** screen, tap **Download** while on Wi-Fi. Allow
   notifications so you can follow the progress. The download continues in the background,
   resumes after an interruption and is checked against its SHA-256 before use. On mobile data
   the app asks first.
5. When the status shows **Ready**, tap **Open chat**.

Settings has the context size (2K to 32K tokens, 8K by default; changing it reloads the model)
and the temperature.

## CI and signing

`.github/workflows/android.yml` runs on every push and on demand. It runs the unit tests, builds
the release APK, uploads it as the `liquid-pocket-apk` artifact and publishes the branch's
prerelease.

To sign releases with your own key, add these repository secrets
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `LP_KEYSTORE_BASE64` | The keystore file, base64-encoded (`base64 -w0 release.jks`) |
| `LP_KEYSTORE_PASSWORD` | The keystore password |
| `LP_KEY_ALIAS` | The key alias |
| `LP_KEY_PASSWORD` | The key password |

A keystore can be created with
`keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias liquidpocket`.
Never commit it.

Without these secrets the APK is signed with a debug key and the build shows a warning. Android
only installs an update signed with the same key as the installed app, so when you switch
between a debug-signed and a release-signed build, uninstall the app once first (this deletes
the downloaded model, which then has to be downloaded again).

## Build locally

- **Android Studio:** open the repository root and run the `app` configuration. The llama.cpp
  submodule must be checked out (`git submodule update --init --recursive`), and the NDK and
  CMake versions from the workflow must be installed.
- **Command line:** `./gradlew :app:assembleRelease` (needs the Android SDK, through `ANDROID_HOME`
  or `local.properties`). The APK is in `app/build/outputs/apk/release/`.
- **Without an Android SDK** only the pure-Kotlin `:core` module is part of the build, so
  `./gradlew :core:test` works on any machine with a JDK.

The release build reads `LP_VERSION_CODE` and the `LP_KEYSTORE_PATH`, `LP_KEYSTORE_PASSWORD`,
`LP_KEY_ALIAS` and `LP_KEY_PASSWORD` environment variables, the same as CI.

## Updating llama.cpp

llama.cpp is a shallow submodule in `third_party/llama.cpp`, pinned to a release tag. To move it to
another tag:

```sh
git -C third_party/llama.cpp fetch --depth 1 origin tag <tag>
git -C third_party/llama.cpp checkout <tag>
git add third_party/llama.cpp
git commit -m "Update llama.cpp to <tag>"
```

Build and chat once on the phone before relying on the new version.

## Licenses

- The LFM model weights are under the **LFM Open License v1.0** (free for use below US$10M
  annual revenue). The app downloads them from Liquid AI's Hugging Face repositories; they are
  not part of this repository or the APK.
- llama.cpp is under the **MIT License**.
