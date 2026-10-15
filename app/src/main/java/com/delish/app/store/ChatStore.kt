package com.delish.app.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.delish.app.LogBus
import com.delish.app.llm.ChatEngine

/**
 * Local-only conversation memory and recents.
 *
 * Two tables: sessions + messages. Everything stays in app-private storage.
 */
class ChatStore(context: Context) : SQLiteOpenHelper(context, "delish.db", null, 1) {

    companion object {
        private const val KEY_ACTIVE = "active_session"
    }

    private val appContext = context.applicationContext
    private val prefs by lazy {
        context.getSharedPreferences("delish_chat", Context.MODE_PRIVATE)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT,
                created_at INTEGER,
                updated_at INTEGER
            )"""
        )
        db.execSQL(
            """CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER,
                role TEXT,
                content TEXT,
                created_at INTEGER
            )"""
        )
        db.execSQL("CREATE INDEX idx_msg_session ON messages(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS sessions")
        onCreate(db)
    }

    /* ─────────── sessions ─────────── */

    private fun titleOf(id: Long): String? =
        readableDatabase.rawQuery("SELECT title FROM sessions WHERE id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun exists(id: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM sessions WHERE id=?", arrayOf(id.toString()))
            .use { it.moveToFirst() }

    /**
     * The chat the user is currently looking at.
     *
     * Tracked in preferences rather than inferred from `updated_at`. Ordering by
     * recency meant that merely opening an older conversation moved the "active"
     * pointer, so the next message landed in the wrong thread.
     */
    @Synchronized
    fun currentSessionId(): Long {
        val stored = prefs.getLong(KEY_ACTIVE, -1L)
        if (stored > 0 && exists(stored)) return stored
        // adopt the newest existing session rather than littering the list
        readableDatabase.rawQuery("SELECT id FROM sessions ORDER BY updated_at DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) { val id = it.getLong(0); prefs.edit().putLong(KEY_ACTIVE, id).apply(); return id } }
        return newSession()
    }

    @Synchronized
    fun newSession(): Long {
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("title", "New chat")
            put("created_at", now)
            put("updated_at", now)
        }
        val id = writableDatabase.insert("sessions", null, v)
        prefs.edit().putLong(KEY_ACTIVE, id).apply()
        LogBus.log("STORE", "new session #$id")
        return id
    }

    /**
     * Names the thread after the first thing the user said, the way ChatGPT does.
     *
     * Without this every session stayed "New chat" and the recents list — which
     * filters that placeholder out — was permanently empty.
     */
    @Synchronized
    private fun autoTitle(id: Long, text: String) {
        if (titleOf(id) != "New chat") return
        val one = text.trim().replace(Regex("\\s+"), " ")
        if (one.isEmpty()) return
        val trimmed = if (one.length <= 42) one else one.take(42)
        // don't leave half a word dangling
        val t = trimmed.substringBeforeLast(' ', trimmed)
            .ifBlank { trimmed }
        writableDatabase.update("sessions", ContentValues().apply { put("title", t) },
            "id=?", arrayOf(id.toString()))
        LogBus.log("STORE", "titled #$id -> \"$t\"")
    }

    @Synchronized
    fun sessionCount(id: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Points subsequent messages at an existing thread. */
    @Synchronized
    fun setActive(id: Long) {
        if (exists(id)) prefs.edit().putLong(KEY_ACTIVE, id).apply()
    }

    @Synchronized
    fun touch() {
        val id = currentSessionId()
        val v = ContentValues().apply { put("updated_at", System.currentTimeMillis()) }
        writableDatabase.update("sessions", v, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun deleteSession(id: Long) {
        val db = writableDatabase
        db.delete("messages", "session_id=?", arrayOf(id.toString()))
        db.delete("sessions", "id=?", arrayOf(id.toString()))
        if (prefs.getLong(KEY_ACTIVE, -1L) == id) newSession()
        LogBus.log("STORE", "deleted session #$id")
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete("messages", null, null)
        writableDatabase.delete("sessions", null, null)
        newSession()
    }

    /* ─────────── messages ─────────── */

    @Synchronized
    fun appendMessage(text: String, role: String) {
        val id = currentSessionId()
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("session_id", id)
            put("role", role)
            put("content", text)
            put("created_at", now)
        }
        val db = writableDatabase
        db.insert("messages", null, v)
        if (role == "user") {
            autoTitle(id, text)
            db.update("sessions", ContentValues().apply { put("updated_at", now) },
                "id=?", arrayOf(id.toString()))
        }
    }

    /** Recent messages of the active session, oldest first, ready for the prompt. */
    @Synchronized
    fun messages(limit: Int = 12): List<ChatEngine.Msg> {
        val id = currentSessionId()
        val out = ArrayList<ChatEngine.Msg>(limit)
        readableDatabase.rawQuery(
            "SELECT role, content FROM messages WHERE session_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(id.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(ChatEngine.Msg(c.getString(0), c.getString(1)))
        }
        return out.reversed()
    }

    @Synchronized
    fun updateLastAssistant(text: String) {
        val id = currentSessionId()
        val db = writableDatabase
        val lastId = db.rawQuery(
            "SELECT id FROM messages WHERE session_id=? AND role='assistant' ORDER BY id DESC LIMIT 1",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        if (lastId > 0) {
            val v = ContentValues().apply { put("content", text) }
            db.update("messages", v, "id=?", arrayOf(lastId.toString()))
        }
    }

    /* ─────────── json for the UI ─────────── */

    @Synchronized
    fun recentsJson(): String {
        val sb = StringBuilder("[")
        var first = true
        val active = currentSessionId()
        // Only threads that actually have content — a room full of "New chat"
        // placeholders is noise, not history.
        readableDatabase.rawQuery(
            "SELECT s.id, s.title, s.updated_at, " +
                "(SELECT content FROM messages m WHERE m.session_id=s.id ORDER BY m.id DESC LIMIT 1) " +
                "FROM sessions s WHERE EXISTS(SELECT 1 FROM messages m WHERE m.session_id = s.id) " +
                "ORDER BY s.updated_at DESC LIMIT 40", null
        ).use { c ->
            while (c.moveToNext()) {
                if (!first) sb.append(',')
                first = false
                sb.append("{\"id\":").append(c.getLong(0))
                    .append(",\"title\":").append(json(c.getString(1)))
                    .append(",\"updatedAt\":").append(c.getLong(2))
                    .append(",\"preview\":").append(json(c.getString(3)))
                    .append(",\"active\":").append(if (c.getLong(0) == active) "true" else "false")
                    .append('}')
            }
        }
        return sb.append(']').toString()
    }

    @Synchronized
    fun messagesJson(sessionId: Long): String {
        val sb = StringBuilder("[")
        var first = true
        readableDatabase.rawQuery(
            "SELECT role, content FROM messages WHERE session_id=? ORDER BY id ASC",
            arrayOf(sessionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                if (!first) sb.append(',')
                first = false
                sb.append("{\"role\":").append(json(c.getString(0)))
                    .append(",\"content\":").append(json(c.getString(1))).append('}')
            }
        }
        return sb.append(']').toString()
    }

    private fun json(s: String?): String {
        if (s == null) return "\"\""
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append(' ') else sb.append(ch)
        }
        return sb.append('"').toString()
    }
}