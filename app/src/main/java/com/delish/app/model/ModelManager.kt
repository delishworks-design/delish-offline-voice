package com.delish.app.model

import android.content.Context
import com.delish.app.LogBus
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Canonical on-device layout, relative to filesDir. */
object ModelPaths {
    const val MODELS = "models"
    const val STT = "stt"
    const val TTS = "tts"

    const val VOSK_EN = "stt/en"
    const val VOSK_TL = "stt/tl"
    const val TTS_RYAN = "tts/ryan"

    const val MINI = "models/qwen-0.5b.task"
    const val PRO = "models/qwen-1.5b.task"
}

/**
 * Downloads and tracks the offline engines.
 *
 * Everything lives in app-private storage (filesDir) so nothing shows up in
 * the gallery and can be wiped from Settings → Clear all data.
 */
class ModelManager(private val ctx: Context) {

    companion object {
        const val TIER_MINI = "mini"
        const val TIER_PRO = "pro"

        private const val PREFS = "delish_state"

        private const val KEY_INSTALLED = "installed"
        private const val KEY_TIER = "tier"

        /* ungated, Apache-2.0 bundles — every URL below verified anonymous (HTTP 200) */
        private const val HF = "https://huggingface.co/litert-community"

        /**
         * Qwen2.5-0.5B INT8 — the default, 521 MB.
         *
         * Constraint discovered the hard way: MediaPipe's `LlmInference` native
         * engine (`llm_utils/tokenizer_utils.cc`) requires a **SentencePiece**
         * tokenizer inside the bundle. Qwen3, SmolLM2 and Gemma3 ship a fast
         * BPE tokenizer instead, and fail at load with
         * `INVALID_ARGUMENT: SentencePiece tokenizer is not found in the model`
         * — verified on a real device, not inferred. Only the Qwen2 family is
         * loadable here.
         */
        const val URL_MINI =
            "$HF/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

        /** Qwen2.5-1.5B INT8 — 1494 MB. Smarter, but heavy for a 4 GB phone. */
        const val URL_PRO =
            "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task"

        private const val VOSK_BASE = "https://alphacephei.com/vosk/models"
        const val URL_VOSK_EN = "$VOSK_BASE/vosk-model-small-en-us-0.15.zip"
        const val URL_VOSK_TL = "$VOSK_BASE/vosk-model-tl-ph-generic-0.6.zip"
    }

    private val prefs by lazy { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /* ─────────── state ─────────── */

    fun activeTier(ctx: Context): String = prefs.getString(KEY_TIER, TIER_MINI) ?: TIER_MINI

    fun setActiveTier(ctx: Context, tier: String) {
        prefs.edit().putString(KEY_TIER, tier).apply()
    }

    fun setInstalled(ctx: Context, installed: Boolean) {
        prefs.edit().putBoolean(KEY_INSTALLED, installed).apply()
        if (!installed) deleteAll(ctx)
    }

    fun isInstalled(ctx: Context): Boolean =
        prefs.getBoolean(KEY_INSTALLED, false) && modelPath(ctx) != null

    fun hasModel(ctx: Context): Boolean = modelPath(ctx) != null

    /**
     * True when a real Vosk model is unpacked.
     *
     * Checks for `am/final.mdl` rather than the directory name — the archive
     * root gets stripped on unzip, so `stt/en` is not what actually exists.
     */
    fun sttInstalled(ctx: Context): Boolean =
        File(ctx.filesDir, ModelPaths.VOSK_EN).resolve("am/final.mdl").exists()

    private fun sttInstalled(): Boolean = sttInstalled(ctx)

    /** Relative on-disk path for a tier. */
    fun pathFor(tier: String): String = when (tier) {
        TIER_PRO -> ModelPaths.PRO
        else -> ModelPaths.MINI
    }

    fun urlFor(tier: String): String = when (tier) {
        TIER_PRO -> URL_PRO
        else -> URL_MINI
    }

    fun labelFor(tier: String): String = when (tier) {
        TIER_PRO -> "Qwen2.5-1.5B INT8"
        else -> "Qwen2.5-0.5B INT8"
    }

    fun modelPath(ctx: Context): String? {
        val active = pathFor(activeTier(ctx))
        File(ctx.filesDir, active).takeIf { it.exists() && it.length() > 1024 }
            ?.let { return it.absolutePath }
        // the chosen tier isn't there — use any bundle that is, so a half-finished
        // tier switch can't leave the app unable to answer
        for (rel in listOf(ModelPaths.MINI, ModelPaths.PRO)) {
            val f = File(ctx.filesDir, rel)
            if (f.exists() && f.length() > 1024) return f.absolutePath
        }
        return null
    }

    /**
     * Deletes bundles that no longer correspond to a supported tier.
     *
     * An abandoned `.litertlm` is 331 MB of dead weight on a 4 GB phone, and
     * nothing else would ever reclaim it.
     */
    fun purgeUnknownModels(ctx: Context) {
        val known = setOf(ModelPaths.MINI, ModelPaths.PRO)
        val dir = File(ctx.filesDir, ModelPaths.MODELS)
        dir.listFiles()?.forEach { f ->
            val rel = "${ModelPaths.MODELS}/${f.name}"
            if (rel !in known && f.length() > 1024) {
                LogBus.log("LLM", "purging unsupported model ${f.name} (${f.length() / 1048576} MB)")
                f.delete()
                File(f.absolutePath + ".part").delete()
            }
        }
    }

    fun deleteAll(ctx: Context) {
        listOf(ModelPaths.MODELS, ModelPaths.STT, ModelPaths.TTS).forEach {
            File(ctx.filesDir, it).deleteRecursively()
        }
        prefs.edit().remove(KEY_INSTALLED).apply()
    }

    /* ─────────── downloads ─────────── */

    /**
     * Fetches the LLM bundle for [tier] plus the STT model if missing.
     * Runs on a background thread; progress is reported as whole percent.
     */
    fun download(
        ctx: Context,
        tier: String,
        onThreadStart: (Thread) -> Unit,
        onProgress: (Int, Long, Long) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        Thread({
            try {
                val llmUrl = urlFor(tier)
                val llmTarget = File(ctx.filesDir, pathFor(tier))

                // ── 1. the LLM bundle (the big one: 331 MB mini / 586 MB pro) ──
                if (!llmTarget.exists() || llmTarget.length() < 1024) {
                    LogBus.log("DL", "LLM ${labelFor(tier)} -> ${llmTarget.name}")
                    fetchFile(llmUrl, llmTarget) { pct, got, _ ->
                        onProgress((pct * 0.85).toInt(), got, 0L)
                    }
                } else {
                    LogBus.log("DL", "LLM already present, skipping")
                    onProgress(85, llmTarget.length(), 0L)
                }

                // ── 2. the STT model (small, ~41 MB) ──
                // A failure here must NOT block chat — voice just stays off.
                try {
                    if (!sttInstalled()) {
                        LogBus.log("DL", "Vosk EN")
                        val zip = File(ctx.cacheDir, "vosk-en.zip")
                        fetchFile(URL_VOSK_EN, zip) { pct, got, _ ->
                            onProgress(85 + (pct * 0.14).toInt(), got, 0L)
                        }
                        installVosk(zip, "vosk-model-small-en-us-0.15", ModelPaths.VOSK_EN)
                        zip.delete()
                        LogBus.log("DL", "Vosk unpacked: ${sttInstalled()}")
                    } else {
                        LogBus.log("DL", "Vosk already present")
                        onProgress(99, 0L, 0L)
                    }
                } catch (t: Throwable) {
                    LogBus.log("DL", "Vosk FAILED (chat still works): ${t.message}")
                }

                setActiveTier(ctx, tier)
                setInstalled(ctx, true)
                onProgress(100, 0L, 0L)
                onComplete(llmTarget.absolutePath)

            } catch (t: Throwable) {
                LogBus.log("DL", "DOWNLOAD FAILED", t)
                onError(t)
            }
        }, "model-download").apply {
            isDaemon = true
            onThreadStart(this)
            start()
        }
    }

    /**
     * Streams [url] into [target] with HTTP range resume.
     *
     * A half-finished download lives in `<target>.part`; if it exists we ask
     * for the remaining bytes instead of starting over. Timeouts are generous
     * because the HF CDN stalls on weak connections far more often than it
     * fails outright.
     */
    private fun fetchFile(
        url: String,
        target: File,
        onProgress: (Int, Long, Long) -> Unit
    ) {
        target.parentFile?.mkdirs()
        val tmp = File(target.absolutePath + ".part")

        LogBus.log("DL", "GET $url  (resume from ${tmp.length() / 1048576} MB)")

        val startedAt = System.currentTimeMillis()
        var lastStep = -1

        for (attempt in 1..4) {
            var conn: HttpURLConnection? = null
            try {
                var from = if (tmp.exists()) tmp.length() else 0L
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000          // tolerate stalls
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Delish/1.0")
                    setRequestProperty("Accept", "*/*")
                    if (from > 0) setRequestProperty("Range", "bytes=$from-")
                }
                conn.connect()

                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")

                // Server ignored our Range header (sent 200 with the whole body).
                // Appending would corrupt the file, so throw the partial away.
                if (from > 0 && code == 200) {
                    LogBus.log("DL", "server ignored Range — restarting from 0")
                    tmp.delete()
                    from = 0
                }

                val bodyLen = conn.contentLengthLong
                val total = if (bodyLen > 0) from + bodyLen else 0L
                var done = from
                val roundStart = System.currentTimeMillis()

                conn.inputStream.use { input ->
                    // append mode — this is what makes resume actually work
                    FileOutputStream(tmp, true).use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                val step = pct / 5
                                if (step != lastStep) {
                                    lastStep = step
                                    val secs = ((System.currentTimeMillis() - roundStart) / 1000).coerceAtLeast(1)
                                    val mbps = (done - from) / 1048576.0 / secs
                                    LogBus.log("DL", "$pct%  ${done / 1048576} MB  ${"%.1f".format(mbps)} MB/s")
                                    onProgress(pct, done, total)
                                }
                            } else {
                                onProgress(0, done, 0L)
                            }
                        }
                        out.flush()
                    }
                }
                conn.disconnect(); conn = null

                if (done <= 0L) {
                    LogBus.log("DL", "zero bytes received — retry")
                    continue
                }
                if (total > 0 && done < total) {
                    LogBus.log("DL", "short read $done/$total — resuming")
                    continue
                }

                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                val secs = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
                LogBus.log("DL", "DONE ${target.name} ${target.length() / 1048576} MB in ${secs}s")
                return

            } catch (t: Throwable) {
                runCatching { conn?.disconnect() }
                LogBus.log("DL", "attempt $attempt failed: ${t.javaClass.simpleName}: ${t.message}")
                if (attempt == 4) throw t
                Thread.sleep(1200L * attempt)   // keep .part; the retry resumes
            }
        }
    }

    /**
     * Unpacks [zip] and places it at `filesDir/[rel]` as a real directory.
     *
     * The vosk archives wrap everything in a versioned folder
     * (`vosk-model-small-en-us-0.15/am/final.mdl`). We extract to a staging
     * dir keeping that folder, then move it to its final name so the layout is
     * predictable for the recognizer.
     */
    private fun installVosk(zip: File, rootName: String, rel: String) {
        val staging = File(ctx.cacheDir, "vosk-unpack")
        staging.deleteRecursively()
        // strips the archive's top-level folder, so we get am/ conf/ ive/ directly
        unzip(zip, staging)

        if (!File(staging, "am/final.mdl").exists()) {
            throw IllegalStateException("$rootName.zip has no am/final.mdl after extract")
        }

        val dest = File(ctx.filesDir, rel)
        dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!staging.renameTo(dest)) {
            staging.copyRecursively(dest, overwrite = true)
            staging.deleteRecursively()
        }
        LogBus.log("DL", "vosk installed at ${dest.absolutePath}")
    }

    /** Bytes already fetched for the current tier, so a resume is honest. */
    fun pendingBytes(ctx: Context, tier: String): Long {
        val llm = File(ctx.filesDir, pathFor(tier))
        val part = File(llm.absolutePath + ".part")
        return if (part.exists()) part.length() else 0L
    }

    /** Flattens a zip into [dest], stripping the single top-level folder. */
    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val destPath = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var root: String? = null
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name

                if (root == null) {
                    val slash = name.indexOf('/')
                    root = if (slash > 0) name.substring(0, slash + 1) else ""
                }
                val prefix = root!!
                val rel = if (prefix.isEmpty() || !name.startsWith(prefix)) {
                    zis.closeEntry(); continue
                } else name.substring(prefix.length)

                if (rel.isNotEmpty()) {
                    val out = File(dest, rel)
                    // guard against path traversal (zip-slip)
                    if (out.canonicalPath.startsWith(destPath)) {
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o -> zis.copyTo(o) }
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }
}