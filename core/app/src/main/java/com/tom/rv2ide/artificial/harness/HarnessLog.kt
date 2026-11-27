/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.tom.rv2ide.artificial.harness

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Crash-safe, rolling, redacting file logger for the Harness feature set.
 *
 * Design goals:
 *  - **Crash-safe**: every important event is written synchronously and flushed to
 *    disk immediately. A session marker file records the in-flight harness run;
 *    if the app or harness dies unexpectedly, the next [init] appends a
 *    diagnostic line instead of failing silently.
 *  - **Redacting**: known secret values are masked before anything touches disk.
 *    Exact secret strings can be registered via [registerSecret] (e.g. the saved
 *    Claude subscription token) and are replaced with `••••` in every message.
 *    Common token shapes (Bearer, sk-…, ghp_…, OAuth state/code params) are
 *    masked by built-in regex patterns even if never registered.
 *  - **Bounded**: `harness.log` rotates to `harness.1.log` … `harness.[MAX_ROTATIONS].log`
 *    once it exceeds [MAX_FILE_BYTES]; old files are deleted after rotation.
 *
 * Logs never leave the device unless the user explicitly exports them from the
 * Harness settings screen (or via the AI Agent IDE settings page).
 */
object HarnessLog {

    private const val LOG_DIR_NAME = "harness-logs"
    private const val ACTIVE_LOG = "harness.log"
    private const val SESSION_STATE = "session-state.json"
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024 // 4 MB per file
    private const val MAX_ROTATIONS = 3
    private const val TAG = "HarnessLog"

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var appContext: Context? = null

    /** In-flight exact secrets to redact from every message (registered at runtime). */
    private val registeredSecrets = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val BUILTIN_PATTERNS: List<Pair<Pattern, String>> = listOf(
        // Bearer tokens in any context
        Pattern.compile("""(?i)(authorization\s*:?\s*bearer\s+)[A-Za-z0-9._~+/=-]+""") to "$1••••",
        // OpenAI-style keys
        Pattern.compile("""(?i)sk-[A-Za-z0-9._-]{8,}""") to "sk-••••",
        // GitHub tokens
        Pattern.compile("""gh[pousr]_[A-Za-z0-9]{20,}""") to "gh_••••",
        // URL query parameters carrying OAuth material
        Pattern.compile("""(?i)([?&](?:state|code|code_challenge|client_secret|access_token|refresh_token)=)[^&\s"'<>]+""") to "$1••••",
        // Environment variable assignments with secrets
        Pattern.compile("""(?i)((?:CLAUDE_CODE_OAUTH_TOKEN|ANTHROPIC_AUTH_TOKEN|ANTHROPIC_API_KEY|GEMINI_API_KEY|GOOGLE_API_KEY|MH_DSH_API_KEY|DEEPSEEK_API_KEY|OPENAI_API_KEY|XAI_API_KEY)\s*[=:]\s*)\S+""") to "$1••••",
        // key=value style secrets in arbitrary text
        Pattern.compile("""(?i)((?:api[_-]?key|apikey|secret|password|passwd|token)\s*[=:]\s*)[^\s'"&]{6,}""") to "$1••••",
    )

    /** Directory where logs are stored (created on demand). */
    fun logDirectory(context: Context): File {
        logDir?.let { if (it.exists() || it.mkdirs()) return it }
        synchronized(this) {
            logDir?.let { return it }
            val dir = File(context.filesDir, LOG_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            logDir = dir
            return dir
        }
    }

    /** Files that make up the current log set (active first, then rotations). */
    fun logFiles(context: Context): List<File> {
        val dir = logDirectory(context)
        val files = mutableListOf<File>()
        File(dir, ACTIVE_LOG).takeIf { it.isFile }?.let(files::add)
        for (i in 1..MAX_ROTATIONS) {
            File(dir, "harness.$i.log").takeIf { it.isFile }?.let(files::add)
        }
        return files
    }

    /**
     * Initialise the logger. Must be called once, e.g. from Application startup or lazily
     * from the first harness operation. Appends a marker when the previous run did not
     * end with [endSession] (crash / process death) instead of hiding it.
     */
    @Synchronized
    fun init(context: Context) {
        val dir = logDirectory(context)
        appContext = context.applicationContext
        val marker = File(dir, SESSION_STATE)
        if (marker.isFile) {
            val details = runCatching { marker.readText().take(2_000) }.getOrDefault("")
            writeLine(dir, "E", TAG, "Previous harness session did not exit cleanly (app killed or harness crashed). $details")
            runCatching { marker.delete() }
        }
        writeLine(dir, "I", TAG, "HarnessLog initialised (dir=$dir)")
    }

    /** Register an exact secret value to redact from all future log lines. */
    fun registerSecret(secret: String?) {
        val value = secret?.trim().orEmpty()
        if (value.length >= 8 && !registeredSecrets.contains(value)) {
            registeredSecrets.add(value)
        }
    }

    /** Remove a previously registered secret (e.g. after a one-shot OAuth code was used). */
    fun unregisterSecret(secret: String?) {
        val value = secret?.trim().orEmpty()
        if (value.isNotEmpty()) registeredSecrets.remove(value)
    }

    fun d(tag: String, message: String) = log("D", tag, message, null)
    fun i(tag: String, message: String) = log("I", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log("W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log("E", tag, message, throwable)

    /** Audit trail for security/lifecycle events. Always flushed to disk synchronously. */
    fun audit(tag: String, message: String) = log("A", tag, message, null, force = true)

    /** Last-resort logger for the global crash handler. Always writes, never throws. */
    fun fatal(tag: String, message: String, throwable: Throwable?) = log("E", tag, message, throwable, force = true)

    /** Mark the start of a harness process. Written synchronously so crashes are visible. */
    fun beginSession(context: Context, sessionId: String, agent: String, projectRoot: String?) {
        try {
            val dir = logDirectory(context)
            val json = org.json.JSONObject()
                .put("sessionId", sessionId)
                .put("agent", agent)
                .put("projectRoot", projectRoot ?: "")
                .put("startedAt", System.currentTimeMillis())
            File(dir, SESSION_STATE).writeText(json.toString())
            audit(TAG, "Session $sessionId started (agent=$agent, project=${projectRoot ?: "<none>"})")
        } catch (t: Throwable) {
            Log.w(TAG, "beginSession failed: ${t.message}")
        }
    }

    /** Mark a clean end of the harness process. Safe to call multiple times. */
    fun endSession(context: Context, sessionId: String, outcome: String) {
        try {
            val dir = logDirectory(context)
            runCatching { File(dir, SESSION_STATE).delete() }
            audit(TAG, "Session $sessionId ended: $outcome")
        } catch (t: Throwable) {
            Log.w(TAG, "endSession failed: ${t.message}")
        }
    }

    /** Return the tail of the log (most recent content, redacted), capped to [maxBytes]. */
    fun tail(context: Context, maxBytes: Long = 256 * 1024): String {
        return try {
            val files = logFiles(context)
            if (files.isEmpty()) return "(no harness logs yet)"
            val sb = StringBuilder()
            var remaining = maxBytes
            // Oldest rotated file first so the output reads chronologically
            for (file in files.reversed()) {
                if (remaining <= 0) break
                val text = runCatching { readTail(file, remaining) }.getOrNull() ?: continue
                sb.append("\n===== ").append(file.name).append(" =====\n")
                sb.append(text)
                remaining -= text.length.toLong()
            }
            sb.toString()
        } catch (t: Throwable) {
            "(unable to read harness logs: ${t.message})"
        }
    }

    /** Build a single redacted export file for sharing. Returns null on failure. */
    fun exportFile(context: Context): File? {
        return try {
            val out = File(context.cacheDir, "harness-logs-export.txt")
            out.writeText(tail(context, 1_500_000))
            out
        } catch (t: Throwable) {
            e(TAG, "Failed to build log export", t)
            null
        }
    }

    /** Delete every log file and the session marker. */
    fun clear(context: Context) {
        val dir = logDirectory(context)
        synchronized(this) {
            for (file in dir.listFiles().orEmpty()) {
                if (file.name.startsWith("harness") || file.name == SESSION_STATE) {
                    runCatching { file.delete() }
                }
            }
            writeLine(dir, "I", TAG, "Harness logs cleared by user")
        }
    }

    // ------------------------------------------------------------------ internals

    private fun log(level: String, tag: String, message: String, throwable: Throwable?, force: Boolean = false) {
        val safe = redact(buildString {
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable.javaClass.simpleName)
                append(": ")
                append(throwable.message ?: "")
                append("\n")
                append(throwable.stackTraceToString().take(4_000))
            }
        })
        when (level) {
            "D" -> Log.d(TAG, "[$tag] $safe")
            "I", "A" -> Log.i(TAG, "[$tag] $safe")
            "W" -> Log.w(TAG, "[$tag] $safe", throwable)
            else -> Log.e(TAG, "[$tag] $safe", throwable)
        }
        try {
            val dir = logDir ?: appContext?.let { logDirectory(it) } ?: return // no context yet; logcat still has the line
            writeLine(dir, level, tag, safe, force)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write harness log line: ${t.message}")
        }
    }

    @Synchronized
    private fun writeLine(dir: File, level: String, tag: String, line: String, force: Boolean = false) {
        rotateIfNeeded(dir)
        val file = File(dir, ACTIVE_LOG)
        FileOutputStream(file, /* append = */ true).use { out ->
            out.write("${timestampFormat.format(Date())} $level/${tag}: $line\n".toByteArray(Charsets.UTF_8))
            out.flush()
            if (force || level in listOf("E", "A")) {
                runCatching { out.fd.sync() }
            }
        }
    }

    private fun rotateIfNeeded(dir: File) {
        val active = File(dir, ACTIVE_LOG)
        if (!active.isFile || active.length() < MAX_FILE_BYTES) return
        // Shift existing rotations: harness.(n).log -> harness.(n+1).log
        for (i in MAX_ROTATIONS downTo 1) {
            val src = File(dir, "harness.$i.log")
            val dst = File(dir, "harness.${i + 1}.log")
            if (i == MAX_ROTATIONS && src.isFile) src.delete()
            if (src.isFile) src.renameTo(dst)
        }
        active.renameTo(File(dir, "harness.1.log"))
    }

    private fun readTail(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= maxBytes) return file.readText()
        file.inputStream().use { stream ->
            stream.skip(length - maxBytes)
            return stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    internal fun redact(input: String): String {
        var text = input
        for ((pattern, replacement) in BUILTIN_PATTERNS) {
            text = pattern.matcher(text).replaceAll(replacement)
        }
        for (secret in registeredSecrets) {
            if (secret.length >= 8 && text.contains(secret)) {
                text = text.replace(secret, "••••")
            }
        }
        return text
    }
}
