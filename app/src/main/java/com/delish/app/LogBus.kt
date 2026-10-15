package com.delish.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of everything worth debugging: WebView console output,
 * bridge traffic, engine lifecycle, STT/TTS events and download progress.
 *
 * Deliberately bounded so a long session can't grow without limit — 4GB phones
 * don't need another few MB of log strings.
 */
object LogBus {

    private const val MAX_LINES = 900
    private val buf = ArrayDeque<String>(MAX_LINES)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val flat = message.replace("\r\n", " | ").replace('\n', ' ').trim()
        if (flat.length > 400) flat.length // guard against runaway single lines
        val line = "${fmt.format(Date())}  $tag  ${flat.take(400)}"
        synchronized(buf) {
            while (buf.size >= MAX_LINES) buf.removeFirst()
            buf.addLast(line)
        }
        android.util.Log.d("Delish", "$tag: $flat")
    }

    fun log(tag: String, message: String, t: Throwable) {
        log(tag, "$message :: ${t.javaClass.simpleName}: ${t.message}")
    }

    /** Full log, newest last. */
    fun dump(): String = synchronized(buf) { buf.joinToString("\n") }

    /** Last [n] lines — for the on-screen preview. */
    fun tail(n: Int): String = synchronized(buf) {
        if (buf.size <= n) buf.joinToString("\n") else buf.toList().takeLast(n).joinToString("\n")
    }

    fun size(): Int = synchronized(buf) { buf.size }

    fun clear() = synchronized(buf) { buf.clear() }
}