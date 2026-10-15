# Contributing to Delish

Thanks for helping. This file covers the things that will otherwise cost you an
afternoon of guessing.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | Required by AGP 8.7.3 and `sourceCompatibility = VERSION_17` |
| Android SDK | platform 35 + build-tools 35.0.0 | `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26` |
| Gradle | 8.10.2 | Via the committed wrapper — do not install it yourself |

## Build

```powershell
$env:JAVA_HOME     = "C:\Users\MALTOJ\AppData\Local\Android\jdk-17.0.20.1+1"
$env:ANDROID_HOME  = "C:\Users\MALTOJ\AppData\Local\Android\Sdk"

cd C:\Users\MALTOJ\AndroidProjects\Delish
.\gradlew.bat assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat installDebug      # build + install on a connected device
```

`gradlew` (the POSIX script) must keep its executable bit. Git for Windows reports
`core.filemode=false` by default, which commits it as `100644` and breaks
`./gradlew` on Linux CI. If a fresh clone on Linux fails with `Permission denied`,
fix it with:

```bash
git update-index --chmod=+x gradlew
```

## If Gradle fails with PKIX errors

Some corporate networks (Cisco Umbrella, for one) terminate TLS with a proxy
certificate. If Gradle reports:

```
PKIX path building failed: unable to find valid certification path to requested target
```

the JDK truststore needs that root CA. Already done on the original dev machine:

```powershell
Export-Certificate -Cert Cert:\CurrentUser\Root\C5091132E9ADF8AD3E33932AE60A5C8FA939E824 `
  -FilePath $env:TEMP\umbrella.cer -Type CERT -Force
& "$env:JAVA_HOME\bin\keytool.exe" -importcert -trustcacerts -noprompt `
  -alias umbrellaRoot -file $env:TEMP\umbrella.cer `
  -keystore "$env:JAVA_HOME\lib\security\cacerts" -storepass changeit
```

GitHub-hosted runners are unaffected.

## Iterating on the UI without building an APK

This is the fastest loop in the repo and it is easy to miss.

`assets/bridge.js` checks for `window.Delish`. When it is absent — i.e. a plain
browser, not the WebView — the same HTML runs a demo simulation instead of
calling native code. So you can edit `index.html`, reload it in a desktop
browser, and exercise the entire UI, EQ animation, and voice-loop transitions
with no Gradle build and no device.

Only build the APK when you have changed Kotlin, the manifest, or dependencies.

## Architecture in one paragraph

The UI is a WebView over `assets/*.html`. `WebAppInterface.kt` exposes methods via
`@JavascriptInterface`, which dispatch to `ChatEngine` (MediaPipe GenAI),
`SpeechRecognizer` (Vosk + `AudioRecord`), `Speaker` (system `TextToSpeech`),
`ModelManager` (HTTP + unzip), and `ChatStore` (SQLite). Kotlin never parses HTML
and JavaScript never reaches for Android APIs directly.

## Constraints that will bite you

**`maxTokens = 1280` is not arbitrary.** It must match the model's `ekv1280` KV
cache. Raise it and generation corrupts; lower it unnecessarily and you truncate
answers. The project targets 4 GB phones, so the rest of the budget is tuned
around that too: history is trimmed to 1800 characters / 12 messages, the Vosk
model is held in memory only while the loop is running, and the UI is
deliberately raster-free.

**The voice loop is a state machine**, not a queue:

```
IDLE ──[EQ]──► LISTENING ──silence 1.4s──► THINKING ──► SPEAKING
                 ▲                                              │
                 └──────────── auto-continue ──────────────────┘
```

Changing the silence timeout or the auto-continue behaviour touches both
`SpeechRecognizer.kt` and the EQ visuals in `index.html`. They have to agree or
the UI will sit in `THINKING` while the mic is already hot again.

**Models download on first run** into app-private `filesDir`. They are never
committed and never uploaded. Qwen2.5 and the Vosk model are both Apache-2.0 and
ungated on Hugging Face, so the app downloads them anonymously with no token.

**TTS is offline only if the voice data is on the device.** `Speaker.kt` filters
to voices with `!v.isNetworkConnectionRequired`. On a device with no offline
voice pack installed, `available` stays false and the app degrades to text only.
That is intended, not a bug.

## Before you open a pull request

- `./gradlew.bat assembleDebug` passes. CI runs this on every push.
- No `*.apk`, `local.properties`, `*.jks`, or `*.keystore` in the diff.
- `git status` is clean apart from your intended change.
- If you touched the voice loop, say in the PR description which states you
  tested on-device. CI only compiles; it cannot exercise the mic.

## Reporting bugs

Open an issue with the Android version, device GPU, and the logcat tail. The app
logs through `LogBus.kt`:

```powershell
adb logcat | Select-String -Pattern "Delish"
```

## License

Apache-2.0. See [LICENSE](LICENSE). Contributions are accepted under the same
terms.
