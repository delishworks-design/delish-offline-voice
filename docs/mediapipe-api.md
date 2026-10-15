# MediaPipe tasks-genai 0.10.35 — verified API

Findings from inspecting the AAR directly
(`~/.gradle/caches/modules-2/.../tasks-genai-0.10.35/classes.jar`).

## LlmInferenceOptions.Builder — the ONLY available setters

```java
setModelPath(String)
setMaxTokens(int)
setMaxTopK(int)          // ← NOT setTopK
setMaxNumImages(int)
setSupportedLoraRanks(List<Integer>)
setVisionModelOptions(VisionModelOptions)
setAudioModelOptions(AudioModelOptions)
setPreferredBackend(LlmInference.Backend)   // CPU | GPU
build()
```

**Not on the builder** (they existed in older docs/samples):
- `setTopK(...)` → actually `setMaxTopK(int)`
- `setTemperature(...)` → moved to session options upstream, absent here
- `setRandomSeed(...)` → absent here
- `setResultListener(...)` → absent here
- `setErrorListener(...)` → absent here

Consequence: temperature/seed are not tunable in 0.10.35 via this entry point.
Sampling uses library defaults.

## LlmInference

```java
static LlmInference createFromOptions(Context, LlmInferenceOptions)
String generateResponse(String)
ListenableFuture<String> generateResponseAsync(String)
ListenableFuture<String> generateResponseAsync(String, ProgressListener<String>)
int sizeInTokens(String)
long getSentencePieceProcessorHandle()
void close()
```

## ProgressListener — this is how you stream

```java
public interface ProgressListener<OutputT> {
    void run(OutputT output, boolean done);   // cumulative text + finished flag
}
```

Single abstract method → SAM-converts to a Kotlin lambda:

```kotlin
engine.generateResponseAsync(prompt) { partial, done ->
    onPartial?.invoke(partial ?: "", done)
}
```

`partial` is the **cumulative** generated text so far, not a delta.

## Gotchas hit while building this

1. **Property vs method JVM clash**
   A Kotlin `@Volatile var autoSpeak` compiles to `setAutoSpeak(Z)V`. Adding
   `@JavascriptInterface fun setAutoSpeak(Boolean)` is a *platform declaration
   clash*. Rename the JS-exposed method (`setAutoSpeakEnabled`).

2. **Content-token truncation**
   `<|im_start|>` style literals are easy to mangle when written through
   tooling, so the template tokens are assembled from fragments:
   ```kotlin
   private const val IM_START = "<|" + "im_start" + "|>"
   ```

3. **`@Volatile` on `var` in a class with `setX()` methods** — same clash class
   of bug; keep JS-facing names distinct from property names.