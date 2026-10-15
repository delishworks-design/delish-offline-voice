# Delish — Offline AI Companion

Offline voice+text AI assistant for Android. No internet, no account, no cloud.

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
# environment (already set on this machine)
$env:JAVA_HOME = "C:\Users\MALTOJ\AppData\Local\Android\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\MALTOJ\AppData\Local\Android\Sdk"

cd C:\Users\MALTOJ\AndroidProjects\Delish
.\gradlew.bat assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat installDebug         # build + install on connected device
```

### Corporate network note
This machine sits behind a TLS-intercepting proxy (**Cisco Umbrella**). The JDK
truststore needed that root certificate, otherwise Gradle fails with:

```
PKIX path building failed: unable to find valid certification path to requested target
```

Already fixed on this machine by importing `Cisco Umbrella Root CA` into
`%JAVA_HOME%\lib\security\cacerts`. On a new machine, run:

```powershell
Export-Certificate -Cert Cert:\CurrentUser\Root\C5091132E9ADF8AD3E33932AE60A5C8FA939E824 `
  -FilePath $env:TEMP\umbrella.cer -Type CERT -Force
& "$env:JAVA_HOME\bin\keytool.exe" -importcert -trustcacerts -noprompt `
  -alias umbrellaRoot -file $env:TEMP\umbrella.cer `
  -keystore "$env:JAVA_HOME\lib\security\cacerts" -storepass changeit
```

`plugins.gradle.org` is unreachable, so `settings.gradle.kts` lists
`mavenCentral()` ahead of `gradlePluginPortal()` — the plugin markers are on
Maven Central.

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