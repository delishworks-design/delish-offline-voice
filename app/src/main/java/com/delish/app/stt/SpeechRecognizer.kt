package com.delish.app.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.delish.app.LogBus
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Offline speech-to-text using Vosk.
 *
 * Audio: 16 kHz mono PCM16 fed straight into the recognizer.
 *
 * Endpointing is done here rather than by Vosk, because a companion loop has to
 * decide when the user has finished — and that decision is sensitive to how
 * chatty they are. Two things drive it:
 *
 *  · an **adaptive** RMS gate measured against the room's own noise floor,
 *    instead of a hard-coded threshold that is wrong on half the phones out
 *    there (mic gain varies wildly between a budget 4 GB handset and a
 *    flagship);
 *  · frame durations computed from the **actual bytes read**, because
 *    `getMinBufferSize` returns a different size per device — assuming a fixed
 *    100 ms made the silence timeout land anywhere between 1.1 s and 2.8 s.
 *
 * Both matter far more for Taglish than for plain English: Filipino speakers
 * pause mid-sentence, and a timeout that cuts too early eats the end of the
 * sentence.
 */
class SpeechRecognizer(private val ctx: Context) {

    private val tag = "DelishSTT"

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    private val running = AtomicBoolean(false)
    @Volatile private var stopRequested = false

    private var lastPartial: String = ""

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2   // mono, PCM16

        /** How long a trailing gap must last before we call the turn finished. */
        private const val SILENCE_TIMEOUT_MS = 1600L

        /** Ignore anything shorter — taps, coughs, door slams. */
        private const val MIN_UTTERANCE_MS = 500L

        /** Consecutive voiced frames before we believe speech started. */
        private const val VOICE_ON_FRAMES = 2

        /**
         * Consecutive silent frames before we believe it stopped.
         * > 1 so a natural pause inside a Taglish sentence doesn't cut it off.
         */
        private const val VOICE_OFF_FRAMES = 3

        /** Floor on the gate, so a dead-silent room can't make it hypersensitive. */
        private const val MIN_GATE = 260.0

        /** Gate = noise floor × this. Higher = needs a clearer voice. */
        private const val GATE_RATIO = 2.2

        /** Rolling window used to estimate the noise floor. */
        private const val FLOOR_WINDOW = 48
    }

    fun isInstalled(): Boolean =
        File(ctx.filesDir, com.delish.app.model.ModelPaths.VOSK_EN).resolve("am/final.mdl").exists()

    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (Throwable) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        stopRequested = false
        lastPartial = ""

        worker = Thread {
            var rec: Recognizer? = null
            try {
                val path = modelPath()
                LogBus.log("STT", "loading model $path")
                val m = model ?: Model(path).also {
                    model = it
                    LogBus.log("STT", "model ready")
                }
                rec = Recognizer(m, SAMPLE_RATE.toFloat())
                recognizer = rec

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val ar = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                recorder = ar
                ar.startRecording()
                // the actual bytes per read decide the frame length — log it so a
                // mis-tuned timeout is visible in the diagnostics instead of
                // showing up as "sometimes cuts off mid-sentence"
                LogBus.log(
                    "STT",
                    "recording ${SAMPLE_RATE}Hz buf=$minBuf " +
                        "(${(minBuf * 1000L / BYTES_PER_SECOND)}ms/frame, " +
                        "silence=${SILENCE_TIMEOUT_MS}ms)"
                )

                val buf = ByteArray(minBuf)
                val floorRing = FloatArray(FLOOR_WINDOW)
                var floorIdx = 0
                var floorFilled = 0

                var speechMs = 0L
                var silenceMs = 0L
                var voicedRun = 0
                var quietRun = 0
                val startedAt = System.currentTimeMillis()
                var readErrors = 0

                while (running.get() && !stopRequested) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) {
                        // AudioRecord returns ERROR_* occasionally; don't spin on it
                        if (++readErrors > 50) throw IllegalStateException("AudioRecord read failed ($n)")
                        continue
                    }
                    readErrors = 0

                    // frame length from the bytes we actually got, not an assumption
                    val frameMs = n * 1000L / BYTES_PER_SECOND
                    val rms = rms(buf, n)

                    // rolling noise floor: keep the quieter half of recent frames
                    floorRing[floorIdx] = rms.toFloat()
                    floorIdx = (floorIdx + 1) % FLOOR_WINDOW
                    if (floorFilled < FLOOR_WINDOW) floorFilled++
                    val gate = max(MIN_GATE, percentile(floorRing, floorFilled) * GATE_RATIO)

                    val voiced = rms > gate
                    if (voiced) { voicedRun++; quietRun = 0 } else { quietRun++; voicedRun = 0 }

                    val speaking = voicedRun >= VOICE_ON_FRAMES
                    val quietEnough = quietRun >= VOICE_OFF_FRAMES

                    when {
                        speaking -> { speechMs += frameMs; silenceMs = 0 }
                        quietEnough -> silenceMs += frameMs
                    }

                    val finalChunk = rec.acceptWaveForm(buf, n)
                    if (finalChunk) {
                        val text = text(rec.finalResult)
                        rec.reset()
                        if (text.isNotBlank()) {
                            deliverFinal(text, onFinal)
                            return@Thread
                        }
                    } else {
                        val part = text(rec.partialResult)
                        if (part.isNotEmpty() && part != lastPartial) {
                            lastPartial = part
                            onPartial(part)
                        }
                    }

                    // safety net: silence after speech, or user stop with content
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (speechMs > 0 && silenceMs >= SILENCE_TIMEOUT_MS && elapsed >= MIN_UTTERANCE_MS) {
                        val text = text(rec.finalResult)
                        rec.reset()
                        LogBus.log("STT", "endpoint: ${silenceMs}ms silence after ${speechMs}ms speech")
                        if (text.isNotBlank()) deliverFinal(text, onFinal)
                        return@Thread
                    }
                    if (stopRequested && speechMs > 0) {
                        val text = text(rec.finalResult)
                        rec.reset()
                        if (text.isNotBlank()) deliverFinal(text, onFinal)
                        return@Thread
                    }
                }

                // stopped without content
                if (speechMs == 0L) onFinal("")

            } catch (t: Throwable) {
                Log.e(tag, "recognizer failure", t)
                LogBus.log("STT", "ERROR ${t.javaClass.simpleName}: ${t.message}")
                onError(t)
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                recorder = null
                runCatching { rec?.close() }
                recognizer = null
                running.set(false)
            }
        }.apply { name = "vosk-stt"; isDaemon = true; start() }
    }

    private fun deliverFinal(t: String, onFinal: (String) -> Unit) {
        stopRequested = true
        LogBus.log("STT", "final: \"$t\"")
        onFinal(t.trim())
    }

    fun stop() {
        stopRequested = true
        running.set(false)
        runCatching { recorder?.stop() }
    }

    fun release() {
        stop()
        runCatching { model?.close() }
        model = null
    }

    /**
     * RMS of a PCM16 frame.
     *
     * Cheap enough to run per frame (a few thousand adds per 80 ms) and far more
     * predictable than peak amplitude, which a single spike can dominate.
     */
    private fun rms(buf: ByteArray, len: Int): Double {
        var sum = 0L
        var i = 0
        val samples = len / 2
        while (i + 1 < len) {
            val s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (s * s).toLong()
            i += 2
        }
        return Math.sqrt(sum.toDouble() / max(1, samples))
    }

    /** 20th percentile of the filled part of [ring] — a quiet-frame estimate. */
    private fun percentile(ring: FloatArray, filled: Int): Double {
        if (filled <= 0) return MIN_GATE
        val copy = ring.copyOf(filled).also { it.sort() }
        val idx = ((copy.size - 1) * 0.2f).toInt().coerceIn(0, copy.size - 1)
        return copy[idx].toDouble()
    }

    private fun text(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching {
            val o = JSONObject(json)
            when {
                o.has("text") -> o.optString("text")
                o.has("partial") -> o.optString("partial")
                else -> ""
            }
        }.getOrDefault("").trim()
    }

    private fun modelPath(): String {
        // the canonical location, verified by the presence of the acoustic model
        val dir = File(ctx.filesDir, com.delish.app.model.ModelPaths.VOSK_EN)
        if (File(dir, "am/final.mdl").exists()) return dir.absolutePath
        // fall back to any unpacked vosk dir under files/stt
        val stt = File(ctx.filesDir, "stt")
        stt.listFiles()?.firstOrNull { it.isDirectory && File(it, "am/final.mdl").exists() }
            ?.let { return it.absolutePath }
        throw IllegalStateException("Vosk model not installed")
    }
}