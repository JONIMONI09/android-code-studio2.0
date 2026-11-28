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
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Headless PTY session for driving interactive harness binaries (e.g. `agy`,
 * `claude setup-token`) without a visible terminal view.
 *
 * It reuses the IDE's built-in termux-emulator: a real pseudo-terminal is created through
 * [com.termux.terminal.JNI], so full-screen TUI programs that demand a raw-mode TTY behave
 * exactly as they do in the IDE terminal. Screen contents are read back through the
 * emulator's scrollback (`getTranscriptText*()`).
 *
 * Threading: [start] must be called on the main thread (same as TerminalActivity). [write],
 * [transcript], [isRunning] and [waitFor] are safe to call from any thread — writes are
 * queued to the PTY and reads are best-effort snapshots of the emulated screen.
 */
class HarnessPtySession(
    context: Context,
    private val shellPath: String,
    private val args: List<String>,
    private val cwd: File,
    private val env: List<String>,
    private val transcriptRows: Int = 1_000,
    private val columns: Int = 120,
    private val rows: Int = 40,
    private val onFinished: ((exitCode: Int) -> Unit)? = null,
) {

    private val appContext = context.applicationContext

    @Volatile
    private var session: TerminalSession? = null

    @Volatile
    private var finished = false

    @Volatile
    private var exitCode = -1

    private val finishLatch = CountDownLatch(1)

    fun start(): Boolean {
        check(session == null) { "Session already started" }
        return try {
            val client = object : TerminalSessionClient {
                override fun onTextChanged(changedSession: TerminalSession) = Unit
                override fun onTitleChanged(changedSession: TerminalSession) = Unit

                override fun onSessionFinished(finishedSession: TerminalSession) {
                    finished = true
                    exitCode = finishedSession.exitStatus
                    finishLatch.countDown()
                    onFinished?.invoke(exitCode)
                }

                override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
                override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
                override fun onBell(session: TerminalSession) = Unit
                override fun onColorsChanged(session: TerminalSession) = Unit
                override fun onTerminalCursorStateChange(state: Boolean) = Unit
                override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
                override fun getTerminalCursorStyle(): Int? = null
                override fun logError(tag: String, message: String) = HarnessLog.w(tag, message)
                override fun logWarn(tag: String, message: String) = HarnessLog.w(tag, message)
                override fun logInfo(tag: String, message: String) = HarnessLog.i(tag, message)
                override fun logDebug(tag: String, message: String) = HarnessLog.d(tag, message)
                override fun logVerbose(tag: String, message: String) = HarnessLog.d(tag, message)
                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
                    HarnessLog.e(tag, message, e)
                override fun logStackTrace(tag: String, e: Exception) = HarnessLog.e(tag, "Terminal failure", e)
            }
            val dir = if (cwd.isDirectory) cwd else cwd.apply { mkdirs() }
            val created = TerminalSession(
                shellPath,
                dir.absolutePath,
                args.toTypedArray(),
                env.toTypedArray(),
                transcriptRows,
                client,
            )
            session = created
            created.updateSize(columns, rows)
            HarnessLog.i(TAG, "PTY session started: $shellPath ${args.joinToString(" ")} (cwd=${dir.absolutePath})")
            true
        } catch (t: Throwable) {
            HarnessLog.e(TAG, "Failed to start PTY session for $shellPath", t)
            false
        }
    }

    /** Write text (UTF-8) to the PTY. Control bytes must be embedded as characters (\u001B …). */
    fun write(text: String) {
        val current = session ?: return
        if (!current.isRunning) return
        runCatching {
            val bytes = text.toByteArray(Charsets.UTF_8)
            current.write(bytes, 0, bytes.size)
        }
            .onFailure { HarnessLog.w(TAG, "PTY write failed: ${it.message}") }
    }

    /**
     * Best-effort snapshot of everything on the emulated screen + scrollback.
     * Prefers the fully-joined variant so hard-wrapped URLs appear on one line.
     */
    fun transcript(): String {
        val current = session ?: return ""
        return runCatching {
            val screen = current.emulator?.screen
                ?: return@runCatching ""
            val joined = screen.transcriptTextWithFullLinesJoined
            if (joined.isNotBlank()) joined else screen.transcriptText
        }.getOrElse { "" }
    }

    fun isRunning(): Boolean = session?.isRunning ?: false

    fun exitCode(): Int = if (finished) exitCode else -1

    /** Wait up to [timeoutMs] for the process to exit. Returns the exit code, or null on timeout. */
    fun waitFor(timeoutMs: Long): Int? {
        if (finished) return exitCode
        val completed = finishLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return if (completed) exitCode else null
    }

    /** Kill the subprocess (SIGKILL) and release the session. */
    fun destroy() {
        val current = session ?: return
        runCatching { current.finishIfRunning() }
        session = null
        finishLatch.countDown()
    }

    companion object {
        private const val TAG = "HarnessPtySession"
    }
}
