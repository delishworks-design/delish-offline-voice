package com.delish.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speech output.
 *
 * Primary path is the system TTS engine running offline (no network needed as
 * long as the voice data is present on device). Pitch/rate are tuned for the
 * Delish persona.
 */
class Speaker(private val ctx: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    @Volatile var available = false
        private set

    @Volatile private var onDoneCb: (() -> Unit)? = null
    @Volatile private var onStartCb: (() -> Unit)? = null

    init {
        tts = TextToSpeech(ctx.applicationContext, this)
    }

    override fun onInit(status: Int) {
        available = status == TextToSpeech.SUCCESS && tts != null
        if (!available) return
        val engine = tts ?: return
        engine.setLanguage(Locale.US)
        engine.setSpeechRate(1.0f)
        engine.setPitch(1.1f)
        pickGender()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { onStartCb?.invoke() }
            override fun onDone(utteranceId: String?) { onDoneCb?.invoke() }
            override fun onError(utteranceId: String?) { onDoneCb?.invoke() }
            override fun onError(utteranceId: String?, errorCode: Int) { onDoneCb?.invoke() }
        })
    }

    /** "male" or "female" — picks a system voice whose name best matches. */
    var gender: String = "male"
        private set

    fun setGender(g: String) {
        gender = if (g == "female") "female" else "male"
        runCatching { pickGender() }
    }

    private fun pickGender() {
        val engine = tts ?: return
        val want = gender
        // Most system voice names contain the gender for the local pack.
        val byName = runCatching {
            engine.voices?.firstOrNull { v ->
                v.locale.language == Locale.US.language &&
                    !v.isNetworkConnectionRequired &&
                    v.name.lowercase().contains(want)
            }
        }.getOrNull()
        if (byName != null) {
            runCatching { engine.setVoice(byName) }
            com.delish.app.LogBus.log("TTS", "gender=$gender -> ${byName.name}")
        } else {
            // Fallback: keep the US default so we never go silent.
            runCatching { engine.setLanguage(Locale.US) }
            com.delish.app.LogBus.log("TTS", "gender=$gender (no matching voice, default kept)")
        }
    }

    /** True while the engine is actively speaking. */
    val isSpeaking: Boolean
        get() = runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    /** Installed offline voices, as a JSON array the WebView can render. */
    fun voicesJson(): String {
        val sb = StringBuilder("[")
        var first = true
        val engine = tts
        if (engine != null && available) {
            runCatching {
                for (v in engine.voices) {
                    val loc = runCatching { v.locale.toLanguageTag() }.getOrDefault("")
                    if (!v.isNetworkConnectionRequired) {
                        if (!first) sb.append(',')
                        first = false
                        sb.append("{\"name\":\"").append(esc(v.name))
                            .append("\",\"locale\":\"").append(esc(loc))
                            .append("\"}")
                    }
                }
            }
        }
        return sb.append(']').toString()
    }

    fun speak(text: String, onStart: () -> Unit = {}, onDone: () -> Unit = {}) {
        // guard against overlapping utterances so the barge-in path is clean
        runCatching { tts?.stop() }
        speakInternal(text, onStart, onDone)
    }

    private fun speakInternal(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit
    ) {
        val engine = tts
        if (!available || engine == null || text.isBlank()) { onDone(); return }
        onStartCb = onStart
        onDoneCb = onDone
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "delish-${System.nanoTime()}")
        }.onFailure { onDoneCb = null; onDone() }
    }

    private fun esc(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder()
        for (ch in s.toCharArray()) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    fun stop() {
        runCatching { tts?.stop() }
        onDoneCb = null
        onStartCb = null
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        available = false
    }
}