package com.delish.app

import android.content.Context
import android.webkit.JavascriptInterface
import com.delish.app.llm.ChatEngine
import com.delish.app.model.ModelManager
import com.delish.app.stt.SpeechRecognizer
import com.delish.app.store.ChatStore
import com.delish.app.tts.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bridge between the WebView UI and native engines.
 *
 * JS side: window.Delish.startVoiceLoop() etc.
 * Native → JS: window.DelishNative.onNative(event, payload)
 */
class WebAppInterface(
    private val ctx: Context,
    private val hostProvider: () -> MainActivity?
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val store = ChatStore(ctx)
    private val engine = ChatEngine(ctx)
    private val recognizer = SpeechRecognizer(ctx)
    private val speaker = Speaker(ctx)
    private val models = ModelManager(ctx)

    private val host: MainActivity? get() = hostProvider()

    /* ─────────── state ─────────── */
    @Volatile var loopActive = false; private set
    @Volatile var autoSpeak = true
    @Volatile var autoContinue = true
    @Volatile var language = "auto"

    /** Set by interrupt(): remaining LLM partials get discarded. */
    @Volatile private var discarded = false

    private val prefs by lazy { ctx.getSharedPreferences("delish_ui", Context.MODE_PRIVATE) }

    private companion object {
        const val KEY_SPEED = "speed_mode"
    }

    /** Accumulates the LLM's incremental fragments into the full reply. */
    private val buffer = StringBuilder()

    init {
        // restore the saved speed mode before the model is loaded, so the very
        // first build already uses the right cache size
        engine.applyTuning(ChatEngine.Tuning.from(prefs.getString(KEY_SPEED, "balanced")))

        /* MediaPipe's ProgressListener emits INCREMENTAL FRAGMENTS ("Hey", "!",
           " How") and sends an empty string on the final callback — so the full
           reply only exists if we accumulate here. The UI always receives the
           cumulative text. */
        engine.onPartial = stream@{ fragment, done ->
            if (discarded) {
                if (done) { discarded = false; pendingLoop = false; emit("state", """{"state":"idle"}""") }
                return@stream
            }

            if (!done) buffer.append(fragment)

            if (done) {
                val full = buffer.toString().trim()
                buffer.setLength(0)
                discarded = false
                if (full.isBlank()) {
                    LogBus.log("LLM", "final: EMPTY (model produced nothing)")
                    emit("reply", """{"text":"","done":true}""")
                    if (loopActive && autoContinue) startRecognizer()
                    else emit("state", """{"state":"idle"}""")
                    return@stream
                }
                LogBus.log("LLM", "final(${full.length} chars): ${full.take(200)}")
                emit("reply", """{"text":"${esc(full)}","done":true}""")
                store.appendMessage(full, role = "assistant")
                store.touch()
                if (loopActive) {
                    // voice loop always speaks, regardless of the chat auto-speak pref
                    utter(full)
                } else {
                    // chat mode is silent by design — TTS is a voice-loop feature
                    emit("state", """{"state":"idle"}""")
                }
            } else {
                emit("reply", """{"text":"${esc(buffer.toString())}","done":false}""")
            }
        }
        engine.onError = { e ->
            buffer.setLength(0)
            discarded = false
            emit("error", """{"message":"${esc(e.message ?: "inference failed")}"}""")
            if (loopActive && autoContinue) startRecognizer()
            else emit("state", """{"state":"idle"}""")
        }
    }

    /* ── interrupt ── */

    /**
     * Barge-in: kills TTS immediately, stops dictation, and discards any
     * tokens still streaming from the LLM. MediaPipe has no cancel API, so the
     * generation is allowed to finish internally but never reaches the UI.
     */
    @JavascriptInterface
    fun interrupt() {
        speaker.stop()
        stopRecognizer()
        discarded = true
        loopActive = false
        pendingLoop = false
        buffer.setLength(0)
        emit("reply", """{"text":"","done":true}""")   // close the streaming bubble
        emit("state", """{"state":"idle"}""")
        LogBus.log("UI", "interrupted")
    }

    @JavascriptInterface
    fun isBusy(): Boolean = speaker.available && speaker.isSpeaking

    /* ── voices ── */

    @JavascriptInterface
    fun listVoices(): String = speaker.voicesJson()

    @JavascriptInterface
    fun speakSample() {
        speaker.speak(
            "Hi, I'm Delish. I run entirely offline, so I never need the internet.",
            onStart = { emit("state", """{"state":"speaking"}""") },
            onDone = { emit("state", """{"state":"idle"}""") }
        )
    }

    /* ── storage ── */

    @JavascriptInterface
    fun storageInfo(): String {
        val llm = fileSize("${com.delish.app.model.ModelPaths.MODELS}")
        val stt = fileSize(com.delish.app.model.ModelPaths.STT)
        val tts = fileSize(com.delish.app.model.ModelPaths.TTS)
        return """{"llm":$llm,"stt":$stt,"tts":$tts,"total":${llm + stt + tts},
                  "tier":"${activeTierOf()}","hasModel":${models.hasModel(ctx)}}"""
    }

    private fun activeTierOf(): String = models.activeTier(ctx)

    private fun fileSize(rel: String): Long {
        val dir = java.io.File(ctx.filesDir, rel)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /* ─────────── page lifecycle ─────────── */

    fun onPageReady(url: String) {
        LogBus.log("PAGE", "ready: $url")
        models.purgeUnknownModels(ctx)
        val hasModel = models.hasModel(ctx)
        // Auto-load an already-installed model so entering chat is instant.
        if (hasModel && !engine.ready) {
            LogBus.log("LLM", "auto-loading installed model")
            loadModelInternal()          // emits state:loading itself
        } else {
            LogBus.log("LLM", "engine ready=${engine.ready} hasModel=$hasModel")
        }
        // `ready` carries BOTH facts: hasModel = file on disk, modelLoaded =
        // engine warm. The UI must not treat the first as the second.
        emit("ready", """{"modelLoaded":${engine.ready},"hasModel":$hasModel,"loading":$loading}""")
    }

    fun onHostPause() {
        stopRecognizer()
        speaker.stop()
    }

    fun shutdown() {
        stopRecognizer()
        speaker.shutdown()
        engine.close()
        store.close()
        scope.cancel()
    }

    /* ─────────── JS-callable API ─────────── */

    @JavascriptInterface
    fun ready(): Boolean = engine.ready

    @JavascriptInterface
    fun platform(): String = "android"

    @JavascriptInterface
    fun engineInfo(): String =
        """{"modelReady":${engine.ready},"loading":$loading,"modelPath":"${models.modelPath(ctx) ?: ""}","hasModel":${models.hasModel(ctx)}}"""

    /* ── chat ── */

    @JavascriptInterface
    fun sendMessage(text: String) {
        val msg = text.trim()
        if (msg.isEmpty()) return
        buffer.setLength(0)          // never inherit the previous turn's text
        discarded = false
        store.appendMessage(msg, role = "user")
        emit("state", """{"state":"thinking"}""")
        runLlm()
    }

    @JavascriptInterface
    fun clearChat() {
        store.newSession()
        emit("cleared", "{}")
    }

    /* ── voice loop ── */

    @Volatile private var pendingLoop = false

    /* Anything already in flight when the loop stops must not paint anything.
       Recognizer and TTS callbacks land on their own threads, so `loopActive`
       alone is not enough — a Vosk utterance that was mid-decode would still
       emit `thinking` after the user already exited, and with the loop off it
       would land in the chat list where nothing clears it. Every loop session
       gets an epoch; callbacks carry the epoch they were born in and drop if
       it has moved on. */
    private val loopEpoch = java.util.concurrent.atomic.AtomicInteger(0)

    @JavascriptInterface
    fun startVoiceLoop() {
        loopEpoch.incrementAndGet()
        discarded = false
        if (!models.hasModel(ctx)) {
            emit("notice", """{"message":"Download the AI model first — open Settings."}""")
            return
        }
        if (!ensureMic()) { pendingLoop = true; return }
        if (!engine.ready) loadModelInternal()
        pendingLoop = false
        loopActive = true
        emit("state", """{"state":"listening"}""")
        startRecognizer()
    }

    @JavascriptInterface
    fun stopVoiceLoop() {
        loopEpoch.incrementAndGet()   // invalidate every in-flight callback
        loopActive = false
        pendingLoop = false
        discarded = true               // an unfinished reply must not paint
        stopRecognizer()
        speaker.stop()
        emit("state", """{"state":"idle"}""")
    }

    @JavascriptInterface
    fun isLooping(): Boolean = loopActive

    /** Called by MainActivity when the mic permission dialog resolves. */
    fun onPermissionResult(granted: Boolean) {
        if (granted && pendingLoop) {
            loopEpoch.incrementAndGet()
            discarded = false
            pendingLoop = false
            loopActive = true
            if (!engine.ready) loadModelInternal()
            emit("state", """{"state":"listening"}""")
            startRecognizer()
        } else if (!granted) {
            emit("notice", """{"message":"Microphone permission is required for the voice loop."}""")
            emit("state", """{"state":"idle"}""")
        }
    }

    private fun ensureMic(): Boolean {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            host?.requestMicPermission()
            emit("state", """{"state":"needPermission"}""")
        }
        return granted
    }

    /* ── one-shot dictation ── */

    @JavascriptInterface
    fun startDictation() {
        if (!models.hasModel(ctx)) {
            emit("notice", """{"message":"Download the AI model first — open Settings."}""")
            return
        }
        if (!ensureMic()) return
        if (!engine.ready) loadModelInternal()
        stopRecognizer()
        recognizer.start(
            onPartial = { p -> emit("transcript", """{"partial":"${esc(p)}"}""") },
            onFinal = { f ->
                emit("transcript", """{"final":"${esc(f)}"}""")
                if (f.isNotBlank()) {
                    store.appendMessage(f, role = "user")
                    runLlm()
                } else if (loopActive) {
                    startRecognizer()
                }
            },
            onError = { e -> emit("error", """{"message":"${esc(e.message ?: "stt")}"}""") }
        )
    }

    @JavascriptInterface
    fun stopDictation() = stopRecognizer()

    /* ── speech output ── */

    /**
     * Voice-loop speech. Chat mode never calls this — TTS exists to close the
 * listen → think → speak cycle, not to read chat replies aloud.
 */
    private fun utter(text: String) {
        if (!speaker.available || text.isBlank()) {
            if (loopActive && autoContinue) startRecognizer() else emit("state", """{"state":"idle"}""")
            return
        }
        speaker.speak(
            text,
            onStart = { emit("state", """{"state":"speaking"}""") },
            onDone = {
                if (loopActive && autoContinue) {
                    emit("state", """{"state":"listening"}""")
                    startRecognizer()
                } else {
                    emit("state", """{"state":"idle"}""")
                }
            }
        )
    }

    @JavascriptInterface
    fun stopSpeaking() = speaker.stop()

    @JavascriptInterface
    fun setGender(g: String) = speaker.setGender(g)

    /* ── model management ── */

    @JavascriptInterface
    fun modelTier(): String = models.activeTier(ctx)

    @JavascriptInterface
    fun modelInstalled(): Boolean = models.hasModel(ctx)

    private var downloadThread: Thread? = null

    @JavascriptInterface
    fun requestDownload(tierName: String) {
        if (downloadThread?.isAlive == true) {
            LogBus.log("DL", "requestDownload ignored — already running")
            return
        }
        val tier = if (tierName == ModelManager.TIER_PRO) ModelManager.TIER_PRO
                     else ModelManager.TIER_MINI
        LogBus.log("DL", "requestDownload ${models.labelFor(tier)}")

        models.download(
            ctx = ctx,
            tier = tier,
            onThreadStart = { downloadThread = it },
            onProgress = { pct, got, _ ->
                // got restarts at 0 on a resumed attempt, so add what we already had
                val already = models.pendingBytes(ctx, tier)
                emit("download", """{"percent":$pct,"bytes":${got + already},"total":0}""")
            },
            onComplete = { path ->
                LogBus.log("DL", "complete -> $path")
                emit("download", """{"percent":100,"done":true}""")
                loadModelInternal(path)
            },
            onError = { e ->
                LogBus.log("DL", "error: ${e.message}")
                emit("error", """{"message":"${esc(e.message ?: "download failed")}"}""")
            }
        )
    }

    /** Lets the retry button start a fresh attempt after a failure. */
    @JavascriptInterface
    fun clearDownloadError() {
        LogBus.log("DL", "retry requested")
    }

    @JavascriptInterface
    fun setTier(tier: String) {
        val t = if (tier == ModelManager.TIER_PRO) ModelManager.TIER_PRO
                else ModelManager.TIER_MINI
        models.setActiveTier(ctx, t)
        LogBus.log("LLM", "tier -> ${models.labelFor(t)}")
        emit("tier", """{"tier":"$t","label":"${models.labelFor(t)}"}""")
        // swap the engine over to whatever is now selected
        loadModelInternal()
    }

    @JavascriptInterface
    fun setAutoContinueEnabled(on: Boolean) { autoContinue = on }

    @JavascriptInterface
    fun setSpeechLanguage(lang: String) { language = lang }

    /* ── speed / verbosity ── */

    @JavascriptInterface
    fun speedMode(): String = engine.tuning.key

    @JavascriptInterface
    fun speedModeInfo(): String {
        val sb = StringBuilder("[")
        ChatEngine.Tuning.entries.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("""{"key":"${t.key}","label":"${t.label}","blurb":"${esc(t.blurb)}",""")
                .append("\"temperature\":${t.temperature},\"topP\":${t.topP},\"topK\":${t.topK},")
                .append("\"maxTokens\":${t.maxTokens}}")
        }
        return sb.append(']').toString()
    }

    /**
     * Applies a speed mode.
     *
     * `maxTokens` is the KV-cache allocation, so changing it means rebuilding
     * the engine — which takes several seconds and shows a loading state. Every
     * other knob is pushed into the live session.
     */
    @JavascriptInterface
    fun setSpeedMode(key: String) {
        val t = ChatEngine.Tuning.from(key)
        val reload = engine.needsReload(t)
        engine.applyTuning(t)
        prefs.edit().putString(KEY_SPEED, t.key).apply()
        LogBus.log("LLM", "speed mode -> ${t.label}${if (reload) " (rebuilding)" else ""}")
        emit("speed", """{"mode":"${t.key}"}""")
        if (reload) { forceReload = true; loadModelInternal(); }
    }

    @JavascriptInterface
    fun setInstalled(installed: Boolean) { models.setInstalled(ctx, installed) }

    @JavascriptInterface
    fun clearAllData() {
        models.deleteAll(ctx)
        store.clearAll()
        emit("cleared", """{"all":true}""")
    }

    /* ── conversation memory ── */

    @JavascriptInterface
    fun recents(): String = store.recentsJson()

    /**
     * Switches the active thread and hands the UI everything it needs to paint
     * it. Opening a chat also makes it the one new messages land in.
     */
    @JavascriptInterface
    fun openSession(id: Long) {
        val msgs = store.messagesJson(id)
        store.setActive(id)
        LogBus.log("STORE", "opened session #$id")
        emit("session", """{"id":$id,"messages":$msgs,"recents":${store.recentsJson()}}""")
    }

    @JavascriptInterface
    fun newChat() {
        val id = store.newSession()
        emit("session", """{"id":$id,"messages":[],"recents":${store.recentsJson()}}""")
    }

    @JavascriptInterface
    fun deleteSession(id: Long) {
        store.deleteSession(id)
        emit("recents", store.recentsJson())
    }

    @JavascriptInterface
    fun currentSessionId(): Long = store.currentSessionId()

    /**
     * Messages of the active thread.
     *
     * The voice loop writes to the store directly, so the chat page has no way
     * of knowing its own DOM is stale — after a loop turn it would still show
     * an empty conversation. This lets the UI re-read and repaint.
     */
    @JavascriptInterface
    fun currentMessages(): String =
        store.messagesJson(store.currentSessionId())

    /* ─────────── internals ─────────── */

    private var loading = false
    private var forceReload = false

    private fun loadModelInternal(path: String? = null) {
        val p = path ?: models.modelPath(ctx)
        if (p == null) {
            emit("error", """{"message":"No model file"}""")
            return
        }
        if (loading) return                 // already warming up — don't stack
        if (engine.ready && !forceReload) { flushPending(); return }

        loading = true
        emit("state", """{"state":"loading"}""")
        LogBus.log("LLM", "loading model: $p (${engine.tuning.label})")
        scope.launch {
            runCatching { engine.load(p, engine.tuning.maxTokens) }
                .onSuccess {
                    LogBus.log("LLM", "model loaded OK")
                    loading = false
                    forceReload = false
                    emit("modelReady", """{"path":"$p"}""")
                    flushPending()
                }
                .onFailure {
                    LogBus.log("LLM", "model load FAILED", it)
                    loading = false
                    forceReload = false
                    pendingPromptMsgs.clear()
                    emit("error", """{"message":"${esc(it.message ?: "model load failed")}"}""")
                }
        }
    }

    /**
     * Queue fed to the model once it finishes loading.
     *
     * The file being on disk does not mean the engine is warm — MediaPipe takes
     * several seconds to map a ~520 MB bundle. Sending into that window used to
     * raise "Model not loaded" and kill the turn, so the prompt is parked here
     * and answered as soon as the engine is ready.
     */
    private val pendingPromptMsgs = ArrayList<ChatEngine.Msg>()

    private fun discardPendingMsgs() = pendingPromptMsgs.clear()

    private fun runLlm() {
        if (!engine.ready) {
            // hold the turn instead of erroring; loadModelInternal flushes it
            pendingPromptMsgs.clear()
            pendingPromptMsgs.clear()
            pendingPromptMsgs.addAll(store.messages())
            emit("state", """{"state":"loading"}""")
            LogBus.log("LLM", "engine not ready — holding the turn")
            loadModelInternal()
            return
        }
        emit("state", """{"state":"thinking"}""")
        discarded = false
        engine.generateTurn(store.messages())
    }

    /** Answers whatever arrived while the engine was warming up. */
    private fun flushPending() {
        if (pendingPromptMsgs.isEmpty()) return
        val msgs = ArrayList(pendingPromptMsgs)
        pendingPromptMsgs.clear()
        if (!engine.ready) return          // still not up — keep waiting
        LogBus.log("LLM", "flushing held turn (${msgs.size} messages)")
        emit("state", """{"state":"thinking"}""")
        discarded = false
        engine.generateTurn(msgs)
    }

    private fun startRecognizer() {
        if (!loopActive) return
        stopRecognizer()
        val epoch = loopEpoch.get()      // this recognizer belongs to this session
        recognizer.start(
            onPartial = { p ->
                if (epoch != loopEpoch.get()) return@start
                emit("transcript", """{"partial":"${esc(p)}"}""")
            },
            onFinal = { f ->
                // The loop may have been stopped while Vosk was still decoding.
                // Without this check the final text fires `thinking` after the
                // user has already left, and it never gets cleared.
                if (epoch != loopEpoch.get()) {
                    LogBus.log("STT", "dropping final from a stopped session")
                    return@start
                }
                if (f.isNotBlank()) {
                    store.appendMessage(f, role = "user")
                    runLlm()
                } else if (loopActive) {
                    startRecognizer()
                }
            },
            onError = { e ->
                if (epoch != loopEpoch.get()) return@start
                emit("error", """{"message":"${esc(e.message ?: "stt")}"}""")
                host?.toast("Microphone error")
            }
        )
    }

    private fun stopRecognizer() = recognizer.stop()

    private fun emit(event: String, json: String) {
        LogBus.log("→JS", "$event  $json")
        host?.sendToPage(event, json)
    }

    /* ─────────── diagnostics ─────────── */

    @JavascriptInterface
    fun getLogs(tailLines: Int): String = LogBus.tail(if (tailLines > 0) tailLines else 60)

    @JavascriptInterface
    fun getLogCount(): Int = LogBus.size()

    /** Header block that makes the log self-describing. */
    private fun diagnosticReport(): String = buildString {
        appendLine("=== Delish diagnostics ===")
        appendLine("exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("abi: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("ram: ${android.app.ActivityManager.MemoryInfo().let { "${it.totalMem / 1048576} MB total, ${it.availMem / 1048576} MB free" }}")
        appendLine("lowRamDevice: ${lowRamFlag()}")
        appendLine("model tier: ${models.activeTier(ctx)}")
        appendLine("model file: ${models.modelPath(ctx) ?: "MISSING"}")
        appendLine("engine ready: ${engine.ready}")
        appendLine("tts available: ${speaker.available}")
        appendLine("vosk installed: ${recognizer.isInstalled()}")
        appendLine("lines: ${LogBus.size()}")
        appendLine("=== log ===")
        append(LogBus.dump())
    }

    private fun lowRamFlag(): Boolean =
        (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager).isLowRamDevice

    /** Copies the FULL log to the clipboard so it can be pasted anywhere. */
    @JavascriptInterface
    fun copyLogs(): Boolean = runCatching {
        val text = diagnosticReport()
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Delish log", text))
        true
    }.getOrElse {
        LogBus.log("LOGS", "copy failed", it)
        false
    }

    /**
     * Writes the log to Downloads/Delish/delish-log-<timestamp>.txt and returns
     * the human-readable location. No storage permission needed: MediaStore owns
     * the Downloads collection on API 29+, and on 26–28 we fall back to app-scoped
     * external files (readable over USB, and the user can share it from here).
     */
    @JavascriptInterface
    fun saveLogs(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "delish-log-$stamp.txt"
        val body = diagnosticReport().toByteArray()

        return runCatching {
            val uri: android.net.Uri
            val location: String
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val cr = ctx.contentResolver
                uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("MediaStore insert failed")
                cr.openOutputStream(uri)?.use { it.write(body) }
                        ?: throw IllegalStateException("openOutputStream failed")
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                cr.update(uri, values, null, null)
                location = "Downloads/$name"
            } else {
                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        ?: throw IllegalStateException("no external dir")
                val f = java.io.File(dir, name)
                f.writeBytes(body)
                uri = android.net.Uri.fromFile(f)
                location = f.absolutePath
            }
            LogBus.log("LOGS", "saved $location (${body.size / 1024} KB)")
            location
        }.getOrElse {
            LogBus.log("LOGS", "save failed", it)
            "ERROR: ${it.message ?: "save failed"}"
        }
    }

    @JavascriptInterface
    fun clearLogs() {
        LogBus.clear()
        LogBus.log("LOGS", "cleared")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}