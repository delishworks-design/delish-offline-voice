package com.delish.app.llm

import android.content.Context
import com.delish.app.LogBus
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline LLM via MediaPipe GenAI (Qwen2.5 `.task` bundles).
 *
 * Verified against tasks-genai 0.10.35 by disassembling the AAR:
 *
 *   LlmInference.LlmInferenceOptions.builder()
 *     .setModelPath / setMaxTokens / setMaxTopK / setPreferredBackend
 *   LlmInference.createFromOptions(ctx, options)
 *
 * The legacy `llm.generateResponseAsync(prompt, …)` entry point only ever had
 * `topK` — there is no temperature or topP there. Those live on the **session**
 * API, which does expose them:
 *
 *   LlmInferenceSessionOptions.builder()
 *     .setTopK(int) .setTopP(float) .setTemperature(float) .setRandomSeed(int)
 *   LlmInferenceSession.createFromOptions(llm, options)
 *   session.addQueryChunk(String)
 *   session.generateResponseAsync(ProgressListener { text, done -> … })
 *   session.updateSessionOptions { b -> b.setTemperature(…) }   // no reload
 *
 * So sampling knobs are applied per session, while the output ceiling
 * (`maxTokens`, which is the KV-cache allocation) is fixed at load time.
 */
class ChatEngine(private val context: Context) {

    @Volatile
    var ready = false
        private set

    private var llm: LlmInference? = null
    private var session: LlmInferenceSession? = null

    /** Called from the inference thread with incremental fragments. */
    @Volatile
    var onPartial: ((text: String, done: Boolean) -> Unit)? = null

    @Volatile
    var onError: ((Throwable) -> Unit)? = null

    @Volatile
    var tuning: Tuning = Tuning.BALANCED
        private set

    /**
     * @param maxTokens must not exceed the model's KV cache (ekv1280 -> 1280).
     *        Also acts as a hard ceiling on how long a reply can get, which is
     *        what makes the "Fast" mode genuinely fast.
     */
    suspend fun load(modelPath: String, maxTokens: Int = 640) = withContext(Dispatchers.IO) {
        close()
        val f = java.io.File(modelPath)
        LogBus.log("LLM", "file exists=${f.exists()} size=${f.length()}")

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setMaxTopK(tuning.topK)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build()
        val engine = LlmInference.createFromOptions(context, options)

        session = LlmInferenceSession.createFromOptions(engine, tuning.toSessionOptions())
        llm = engine
        ready = true
        LogBus.log("LLM", "loaded · ${tuning.label} (maxTokens=$maxTokens)")
    }

    /**
     * Switches sampling without touching the weights.
     *
     * Only [Tuning.maxTokens] would need a reload (it is the KV-cache size);
     * every other knob is applied to the live session.
     */
    fun applyTuning(t: Tuning) {
        tuning = t
        val s = session ?: return
        LogBus.log("LLM", "tuning -> ${t.label} (topK=${t.topK} topP=${t.topP} temp=${t.temperature})")
        // The SAM conversion needs an explicit Function here; the Kotlin lambda
        // alone resolves to Builder.apply rather than Builder -> Options.
        val fn = java.util.function.Function<
                LlmInferenceSession.LlmInferenceSessionOptions.Builder,
                LlmInferenceSession.LlmInferenceSessionOptions> { b ->
            b.setTopK(t.topK).setTopP(t.topP).setTemperature(t.temperature).build()
        }
        runCatching { s.updateSessionOptions(fn) }
            .onFailure { LogBus.log("LLM", "tuning update failed", it) }
    }

    /** True when the new mode needs the engine rebuilt (cache size changed). */
    fun needsReload(t: Tuning): Boolean = t.maxTokens != tuning.maxTokens

    fun generateTurn(history: List<Msg>) {
        val engine = llm
        if (engine == null) {
            onError?.invoke(IllegalStateException("Engine not loaded"))
            return
        }
        try {
            // Fresh session — the KV cache must not carry over from the last
            // turn, otherwise the same 640-token window gets double-counted.
            session?.close()
            val fresh = LlmInferenceSession.createFromOptions(engine, tuning.toSessionOptions())
            session = fresh

            // Budget against actual tokens, not characters. The persona and
            // the rolling history count against the same ceiling that the
            // model is allowed to write into, so reservation has to be
            // measured, not estimated.
            val reserve = tuning.maxTokens / 4
            val cap = (tuning.maxTokens - reserve).coerceAtLeast(64)

            var picked = ArrayList(history)
            var prompt = buildPrompt(picked, tuning)
            var attempts = 0
            while (attempts < 12) {
                val need = runCatching { fresh.sizeInTokens(prompt) }.getOrNull() ?: (prompt.length / 4)
                if (need <= cap || picked.size <= 2) break
                picked.removeAt(0)
                prompt = buildPrompt(picked, tuning)
                attempts++
            }

            fresh.addQueryChunk(prompt)
            fresh.generateResponseAsync(
                ProgressListener<String> { text, done ->
                    onPartial?.invoke(text ?: "", done)
                }
            )
        } catch (t: Throwable) {
            onError?.invoke(t)
        }
    }

    fun cancel() {
        runCatching { session?.cancelGenerateResponseAsync() }
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        runCatching { llm?.close() }
        llm = null
        ready = false
    }

    /* ─────────── speed / verbosity modes ─────────── */

    /**
     * Sampling presets.
     *
     * Two levers do the work: how much the model may sample (temperature /
     * topP / topK) and how many tokens it may produce at all. The second is
     * what actually caps latency, so the fast mode also shrinks the KV cache —
     * which is a genuine memory saving on a 4 GB phone, not just a speed one.
     */
    enum class Tuning(
        val key: String,
        val label: String,
        val blurb: String,
        val temperature: Float,
        val topP: Float,
        val topK: Int,
        val maxTokens: Int
    ) {
        FAST(
            "fast", "Fast", "Short answers, quickest reply. Great on battery.",
            temperature = 0.4f, topP = 0.7f, topK = 20, maxTokens = 256
        ),
        BALANCED(
            "balanced", "Balanced", "Everyday replies. A good mix of speed and detail.",
            temperature = 0.7f, topP = 0.9f, topK = 40, maxTokens = 640
        ),
        FULL(
            "full", "Full", "Longest, most detailed answers. Noticeably slower.",
            temperature = 0.8f, topP = 0.95f, topK = 64, maxTokens = 1280
        );

        fun toSessionOptions(): LlmInferenceSession.LlmInferenceSessionOptions =
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(topK)
                .setTopP(topP)
                .setTemperature(temperature)
                .setRandomSeed(0)
                .build()

        companion object {
            fun from(key: String?): Tuning =
                entries.firstOrNull { it.key == key } ?: BALANCED
        }
    }

    /* ─────────── prompt construction ─────────── */

    data class Msg(val role: String, val content: String)

    companion object {
        /* Qwen ChatML control tokens, split so this file stays plain ASCII. */
        private const val IM_START: String = "<|" + "im_start" + "|>"
        private const val IM_END: String = "<|" + "im_end" + "|>"
        private const val ASSISTANT: String = "assistant"

        const val MAX_PROMPT_CHARS = 1800
        const val MAX_PROMPT_MESSAGES = 12

        /**
         * The persona, plus a length hint derived from the active mode.
         *
         * A 0.5B model follows an explicit instruction far more reliably than it
         * obeys a sampling knob, so the hard cap on length is set both ways.
         */
        fun systemPrompt(t: Tuning): String {
            val length = when (t) {
                Tuning.FAST -> "Answer in ONE short sentence, under 15 words. No preamble, no lists."
                Tuning.FULL -> "You may elaborate when it helps, but never pad. Keep it conversational."
                Tuning.BALANCED -> "Keep replies to 1-3 sentences unless asked for more detail."
            }
            return "You are Delish, a confident and playful AI companion that runs fully offline " +
                "on the user's phone. You are cheeky and a bit boastful, and you love " +
                "pointing out that you work anytime, anywhere, with no internet and no Google. " +
                length +
                " Never mention being a language model. Always reply in the same language " +
                "the user writes in."
        }

        /** Plain ChatML — the format Qwen2.5 expects. */
        fun buildPrompt(history: List<Msg>, t: Tuning = Tuning.BALANCED): String {
            val picked = ArrayList<Msg>(MAX_PROMPT_MESSAGES)
            var chars = 0
            var i = history.size - 1
            while (i >= 0 && picked.size < MAX_PROMPT_MESSAGES) {
                val m = history[i]
                if (chars + m.content.length > MAX_PROMPT_CHARS && picked.isNotEmpty()) break
                picked.add(0, m)
                chars += m.content.length
                i--
            }

            val sb = StringBuilder(chars + 640)
            sb.append(IM_START).append("system\n").append(systemPrompt(t)).append(IM_END).append('\n')
            for (m in picked) {
                sb.append(IM_START).append(m.role).append('\n')
                    .append(m.content).append(IM_END).append('\n')
            }
            sb.append(IM_START).append(ASSISTANT).append('\n')
            return sb.toString()
        }
    }
}