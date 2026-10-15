# Delish — Offline AI Companion

[![Build](https://github.com/delishworks-design/delish-offline-voice/actions/workflows/build.yml/badge.svg)](https://github.com/delishworks-design/delish-offline-voice/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg?logo=kotlin&logoColor=white)](app/build.gradle.kts)
[![minSdk](https://img.shields.io/badge/minSDK-26-green.svg)](app/build.gradle.kts)
[![targetSdk](https://img.shields.io/badge/targetSDK-35-green.svg)](app/build.gradle.kts)
[![Runs offline](https://img.shields.io/badge/runs-100%25_offline-brightgreen.svg)](#what-it-is)

Offline voice+text AI assistant for Android. No internet, no account, no cloud.

> **Contributing?** Read [CONTRIBUTING.md](CONTRIBUTING.md) first — there are
> constraints here that will silently corrupt output if you trip over them.
> Found a bug? [Open an issue](https://github.com/delishworks-design/delish-offline-voice/issues).

## What it is

| Layer | Engine | Size |
|---|---|---|
| LLM | Qwen2.5 0.5B / 1.5B (MediaPipe `.task`, int8) | 600 MB / 1.7 GB |
| STT | Vosk (offline speech-to-text) | 41 MB |
| TTS | System TextToSpeech, offline voice data | 0 MB |

Total first-run download: **~704 MB** (Mini). Nothing is uploaded; everything
lives in app-private storage under `filesDir`.

## Build

```powershell
# point at your own JDK 17 and SDK
$env:JAVA_HOME    = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"

cd <repo>
.\gradlew.bat assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat installDebug         # build + install on connected device
```

Requires JDK 17 and SDK platform 35 + build-tools 35.0.0. Gradle 8.10.2 comes
from the committed wrapper.

### Iterating on the UI without building
`assets/bridge.js` detects `window.Delish`. Open `app/src/main/assets/index.html`
in a plain desktop browser and the same UI runs a demo simulation of the voice
loop — no Gradle, no device. Only build the APK once you have touched Kotlin,
the manifest, or dependencies.

### Corporate network note
Behind a TLS-intercepting proxy (**Cisco Umbrella**, among others) Gradle fails
with:

```
PKIX path building failed: unable to find valid certification path to requested target
```

The fix is to import that proxy's root CA into `%JAVA_HOME%\lib\security\cacerts`.
Exact commands are in [CONTRIBUTING.md](CONTRIBUTING.md#if-gradle-fails-with-pkix-errors).
GitHub Actions runners are unaffected.

`settings.gradle.kts` lists `mavenCentral()` ahead of `gradlePluginPortal()`
because the plugin portal is unreachable on that network and the plugin markers
are on Maven Central.

## Architecture

```
assets/*.html ──window.Delish.*()──► WebAppInterface (@JavascriptInterface)
                                        ├─ ChatEngine        MediaPipe GenAI
                                        ├─ SpeechRecognizer  Vosk + AudioRecord
                                        ├─ Speaker           TextToSpeech
                                        ├─ ModelManager      HTTP + unzip
                                        └─ ChatStore         SQLite (recents/memory)
```

`assets/bridge.js` detects `window.Delish`. Absent (plain browser), the same UI
runs the demo simulation — so the HTML can be previewed and tested standalone.

## Voice loop state machine

```
IDLE ──[EQ]──► LISTENING ──silence 1.4s──► THINKING ──► SPEAKING
                 ▲                                              │
                 └──────────── auto-continue ──────────────────┘
```

EQ visuals per state: flat red line (listening), travelling amber wave
(thinking), full teal equalizer (speaking). Tapping the EQ toggles the
collapsible chat preview.

## Memory budget (4GB phones)

- `maxTokens = 1280` — must match the model's `ekv1280` KV-cache
- Prompt history trimmed to 1800 chars / 12 messages
- Vosk model kept loaded only while the loop is running
- Raster-free: pure WebView UI, no heavy image assets

## Files

```
app/src/main/java/com/delish/app/
├── DelishApp.kt
├── MainActivity.kt
├── WebAppInterface.kt
├── LogBus.kt
├── llm/ChatEngine.kt
├── stt/SpeechRecognizer.kt
├── tts/Speaker.kt
├── model/ModelManager.kt
└── store/ChatStore.kt

app/src/main/assets/
├── bridge.js         native bridge + demo fallback
├── index.html        chat + voice loop
├── settings.html     model / voice / storage
└── onboarding.html   splash -> auto-download -> chat
```

## Model sources

Both LLM bundles are ungated (Apache-2.0) on Hugging Face:

- `litert-community/Qwen2.5-0.5B-Instruct` → `..._multi-prefill-seq_q8_ekv1280.task`
- `litert-community/Qwen2.5-1.5B-Instruct` → same filename pattern
- STT: `alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip`

Gemma-3 1B is gated (`gated:auto`), so it is not used — the app downloads
anonymously with no token.

## License

[Apache-2.0](LICENSE). The bundled model weights are fetched at runtime, not
vendored here: Qwen2.5 and Vosk are both Apache-2.0 as well.
