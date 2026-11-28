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
import android.os.Looper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the interactive sign-in flows of the harness CLIs using the IDE's real PTY
 * ([HarnessPtySession]) and surfaces them to the UI as a small state machine.
 *
 * Claude Code (`claude setup-token`):
 *  - The IDE starts the command inside the project-agnostic harness home.
 *  - Claude prints a sign-in URL; the user opens it, approves access and receives a
 *    long-lived token (`sk-ant-oat…`), either printed by the CLI itself (the IDE
 *    captures it automatically) or pasted from the browser into the dialog.
 *  - The token is stored in the Android Keystore ([HarnessKeystore]) — never in plain
 *    preferences — and registered with [HarnessLog] for redaction.
 *
 * Antigravity CLI (`agy`):
 *  - An interactive session replicates the official "manual URL + one-time code" flow.
 *    The URL is surfaced to the UI; the user pastes the one-time code back and the
 *    IDE feeds it to the CLI's raw-mode editor.
 *  - After the CLI writes its credential file, sign-in is verified headlessly with
 *    `agy models` (no model call) before it is reported as connected.
 *  - The credential file is owned by agy; the IDE never reads or copies its contents.
 */
object HarnessAuth {

    private const val TAG = "HarnessAuth"
    private const val AUTH_TIMEOUT_MS = 10L * 60 * 1000

    enum class Provider { CLAUDE, AGY }

    enum class Status {
        IDLE,
        STARTING,
        AWAITING_CODE,
        COMPLETING,
        CONNECTED,
        ERROR,
    }

    data class AuthState(
        val provider: Provider,
        val status: Status,
        /** URL the user must open in a browser (when known). */
        val url: String? = null,
        /** Human-readable status line. */
        val message: String? = null,
        /** Google account email when known (Antigravity only). */
        val accountEmail: String? = null,
    )

    private val _state = MutableStateFlow<AuthState?>(null)
    val state: StateFlow<AuthState?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: HarnessPtySession? = null

    /** Set when the user explicitly cancels — watchers must not store anything afterwards. */
    @Volatile
    private var cancelRequested = false

    // ------------------------------------------------------------ status queries

    fun isClaudeConnected(context: Context): Boolean =
        HarnessKeystore.has(context, HarnessEnvironment.KEY_CLAUDE_TOKEN)

    fun isAgyConnected(context: Context): Boolean {
        if (HarnessEnvironment.agyCredentialFile(context).isFile) return true
        return prefManagerGet(context, HarnessEnvironment.KEY_AGY_SIGNED_IN)
    }

    fun agyAccountEmail(context: Context): String? =
        runCatching { prefManagerGetString(context, HarnessEnvironment.KEY_AGY_EMAIL) }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    fun signOut(context: Context, provider: Provider) {
        cancel()
        when (provider) {
            Provider.CLAUDE -> {
                HarnessKeystore.clear(context, HarnessEnvironment.KEY_CLAUDE_TOKEN)
                HarnessLog.audit(TAG, "Claude token removed by user")
                _state.value = AuthState(provider, Status.IDLE, message = "Claude disconnected.")
            }
            Provider.AGY -> {
                val credential = HarnessEnvironment.agyCredentialFile(context)
                val deleted = runCatching { credential.delete() }.getOrDefault(false)
                prefManagerPutBoolean(context, HarnessEnvironment.KEY_AGY_SIGNED_IN, false)
                prefManagerPutString(context, HarnessEnvironment.KEY_AGY_EMAIL, "")
                HarnessLog.audit(TAG, "Antigravity sign-out requested (credential file deleted: $deleted)")
                _state.value = AuthState(provider, Status.IDLE, message = "Google account disconnected.")
            }
        }
    }

    fun dismiss() {
        _state.value = null
    }

    /** Cancel an in-flight login (kills the CLI; nothing is stored). */
    fun cancel() {
        cancelRequested = true
        session?.destroy()
        session = null
        val current = _state.value
        if (current != null && current.status != Status.CONNECTED) {
            _state.value = AuthState(current.provider, Status.IDLE, message = "Sign-in cancelled.")
        }
    }

    // ------------------------------------------------------------ login flows

    fun beginLogin(context: Context, provider: Provider) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch { withContext(Dispatchers.Main) { beginLogin(context, provider) } }
            return
        }
        cancelInternal()
        cancelRequested = false
        _state.value = AuthState(provider, Status.STARTING, message = "Starting the sign-in flow…")
        HarnessLog.audit(TAG, "Sign-in started for $provider")

        val appContext = context.applicationContext
        appContextRef = appContext
        val binary = when (provider) {
            Provider.CLAUDE -> HarnessEnvironment.claudeBinary(appContext)
            Provider.AGY -> HarnessEnvironment.agyBinary(appContext)
        }
        if (!binary.isFile) {
            _state.value = AuthState(
                provider,
                Status.ERROR,
                message = "The ${providerName(provider)} binary is not installed. Install it first from the Harness section.",
            )
            return
        }

        // Runner resolution probes binaries (can block for seconds) — keep it off the main thread.
        scope.launch {
            val runner = HarnessRunnerSupport.resolve(appContext, binary)
            withContext(Dispatchers.Main) {
                if (_state.value?.status != Status.STARTING) return@withContext // cancelled meanwhile
                if (runner == null) {
                    _state.value = AuthState(provider, Status.ERROR, message = HarnessRunnerSupport.glibcInstallHelp())
                    return@withContext
                }

                val args: List<String> = when (provider) {
                    Provider.CLAUDE -> listOf("setup-token")
                    Provider.AGY -> emptyList()
                }
                val command = HarnessRunnerSupport.wrapCommand(appContext, runner, binary, args)

                val authWorkspace = File(HarnessEnvironment.harnessDir(appContext), "auth").apply { mkdirs() }
                val home = HarnessEnvironment.termuxHome(appContext).apply { mkdirs() }
                val envMap = linkedMapOf(
                    "HOME" to home.absolutePath,
                    "TERM" to "xterm-256color",
                    "NO_COLOR" to "1",
                    // SSH-style environment selects the manual "open URL, paste code" flow in both CLIs.
                    "SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1",
                    "PATH" to listOf(
                        HarnessEnvironment.binDir(appContext).absolutePath,
                        HarnessEnvironment.termuxBin(appContext).absolutePath,
                        "/system/bin",
                    ).joinToString(":"),
                )
                when (provider) {
                    Provider.CLAUDE -> {
                        // Never let a stored token shadow the explicit setup-token sign-in.
                        envMap["CLAUDE_CODE_OAUTH_TOKEN"] = ""
                        envMap["DISABLE_AUTOUPDATER"] = "1"
                        envMap["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"
                    }
                    Provider.AGY -> envMap["AGY_CLI_DISABLE_AUTO_UPDATE"] = "true"
                }
                val env = envMap.map { (k, v) -> "$k=$v" }

                val pty = HarnessPtySession(
                    context = appContext,
                    shellPath = command.first(),
                    args = command,
                    cwd = authWorkspace,
                    env = env,
                    onFinished = { exitCode ->
                        HarnessLog.i(TAG, "$provider sign-in process exited with code $exitCode")
                    },
                )
                if (!pty.start()) {
                    _state.value = AuthState(provider, Status.ERROR, message = "Could not start the CLI. See the harness logs.")
                    return@withContext
                }
                session = pty
                scope.launch { watchAuthLoop(appContext, provider, pty) }
            }
        }
    }

    /** Submit the pasted one-time code (or token) to the pending sign-in. */
    fun submitCode(code: String) {
        val current = _state.value ?: return
        val value = code.trim()
        if (value.isEmpty()) return

        // Claude tokens are self-contained — accept a pasted token directly, no PTY round-trip.
        if (current.provider == Provider.CLAUDE) {
            val token = HarnessStreamJson.extractClaudeToken(value)
            if (token != null) {
                storeClaudeToken(contextForSubmit(), token)
                return
            }
        }
        val pty = session
        if (pty == null || !pty.isRunning()) {
            _state.value = current.copy(status = Status.ERROR, message = "The sign-in session expired. Please sign in again.")
            return
        }
        // Both CLIs run their editors in raw mode where ink expects CR LF for Enter.
        pty.write(value + "\r\n")
        HarnessLog.audit(TAG, "One-time code submitted for ${current.provider}")
        _state.value = current.copy(status = Status.COMPLETING, message = "Code submitted — waiting for the CLI…")
    }

    // ------------------------------------------------------------ internals

    private fun contextForSubmit(): Context? = appContextRef
    @Volatile private var appContextRef: Context? = null

    private fun providerName(provider: Provider): String = when (provider) {
        Provider.CLAUDE -> "Claude Code"
        Provider.AGY -> "Antigravity CLI"
    }

    private fun cancelInternal() {
        session?.destroy()
        session = null
    }

    private fun storeClaudeToken(context: Context?, token: String) {
        if (context == null) return
        val ok = HarnessKeystore.save(context, HarnessEnvironment.KEY_CLAUDE_TOKEN, token)
        HarnessLog.audit(TAG, "Claude setup-token ${if (ok) "stored in Keystore" else "FAILED to store"}")
        cancelInternal()
        _state.value = if (ok) {
            AuthState(Provider.CLAUDE, Status.CONNECTED, message = "Claude account connected.")
        } else {
            AuthState(Provider.CLAUDE, Status.ERROR, message = "Could not store the token securely.")
        }
    }

    private suspend fun watchAuthLoop(context: Context, provider: Provider, pty: HarnessPtySession) {
        appContextRef = context
        val startedAt = System.currentTimeMillis()
        var urlSurfaced = false
        var oauthMenuAdvanced = false
        var colorAnswered = false
        var renderingAnswered = false
        var privacyAnswered = false
        var trustAnswered = false

        while (scope.isActive) {
            delay(150)
            if (cancelRequested) return
            if (System.currentTimeMillis() - startedAt > AUTH_TIMEOUT_MS) {
                fail(context, provider, pty, "Sign-in timed out. Please try again.")
                return
            }
            val transcript = pty.transcript()

            when (provider) {
                Provider.CLAUDE -> {
                    HarnessStreamJson.extractClaudeToken(transcript)?.let { token ->
                        storeClaudeToken(context, token)
                        return
                    }
                    val url = HarnessStreamJson.extractFirstUrl(transcript)
                    if (url != null && !urlSurfaced) {
                        urlSurfaced = true
                        _state.value = AuthState(provider, Status.AWAITING_CODE, url = url, message = "Open the URL, approve access, then paste the token from the page or the CLI output.")
                    } else if (!urlSurfaced) {
                        val waitingForCode = containsAny(transcript, "paste", "code")
                        if (waitingForCode) {
                            urlSurfaced = true
                            _state.value = AuthState(provider, Status.AWAITING_CODE, url = null, message = "Paste the one-time code from your browser.")
                        }
                    }
                }
                Provider.AGY -> {
                    if (!oauthMenuAdvanced &&
                        containsAny(transcript, "Select login method") &&
                        transcript.contains("Google OAuth", ignoreCase = true)
                    ) {
                        // Ink raw mode: lone CR repaints the menu; CR LF selects the highlighted entry.
                        pty.write("\r\n")
                        oauthMenuAdvanced = true
                        _state.value = _state.value?.copy(message = "Google OAuth selected — waiting for the URL…")
                    }
                    if (!colorAnswered && containsAny(transcript, "Choose your color scheme")) {
                        pty.write("\r\n")
                        colorAnswered = true
                    }
                    if (!renderingAnswered &&
                        containsAny(transcript, "no flickering") && containsAny(transcript, "inline")
                    ) {
                        pty.write("\u001B[B\r\n")
                        renderingAnswered = true
                    }
                    if (!privacyAnswered && containsAny(transcript, "Terms of Service")) {
                        val optOut = if (transcript.contains("[x] Yes", ignoreCase = true)) " \t\t\r\n" else "\t\t\r\n"
                        pty.write(optOut)
                        privacyAnswered = true
                    }
                    if (!trustAnswered && containsAny(transcript, "Do you trust the contents")) {
                        pty.write("\r\n")
                        trustAnswered = true
                    }

                    val url = HarnessStreamJson.extractGoogleOAuthUrl(transcript)
                    if (url != null && _state.value?.url == null) {
                        _state.value = AuthState(provider, Status.AWAITING_CODE, url = url, message = "Open the URL, approve access, then paste the one-time code.")
                    }
                    if (isAgySignedInScreen(transcript)) {
                        completeAgyLogin(context, pty, transcript)
                        return
                    }
                    if (containsAny(transcript, "authentication failed", "failed to exchange")) {
                        fail(context, provider, pty, "Google rejected the sign-in. Please try again.")
                        return
                    }
                }
            }

            if (!pty.isRunning()) {
                if (cancelRequested) return // user aborted — never store anything
                // The CLI exited before completing the flow.
                val tail = transcript.takeLast(4_000).trim()
                finishOnExit(context, provider, tail)
                session = null
                return
            }
        }
    }

    private suspend fun completeAgyLogin(context: Context, pty: HarnessPtySession, transcript: String) {
        val email = EMAIL_REGEX.findAll(transcript)
            .map { it.value }
            .firstOrNull { !it.endsWith(".apps.googleusercontent.com", ignoreCase = true) }
        _state.value = AuthState(Provider.AGY, Status.COMPLETING, message = "Sign-in detected — finalizing…")

        // agy shows its main screen before the credential file is fully written; let it settle.
        val credential = HarnessEnvironment.agyCredentialFile(context)
        waitForFileToStabilize(credential, settleMs = 3_000, timeoutMs = 20_000)
        pty.write("/quit\r\n")
        val exited = pty.waitFor(10_000)
        if (exited == null) pty.destroy()
        session = null
        if (cancelRequested) return

        val verified = verifyAgySignIn(context)
        when (verified) {
            HarnessStreamJson.AgySignIn.SIGNED_OUT -> {
                prefManagerPutBoolean(context, HarnessEnvironment.KEY_AGY_SIGNED_IN, false)
                _state.value = AuthState(Provider.AGY, Status.ERROR, message = "Sign-in did not stick — agy reports you are signed out. Please sign in again.")
                HarnessLog.w(TAG, "agy sign-in verification returned SIGNED_OUT")
            }
            else -> {
                prefManagerPutBoolean(context, HarnessEnvironment.KEY_AGY_SIGNED_IN, true)
                email?.let { prefManagerPutString(context, HarnessEnvironment.KEY_AGY_EMAIL, it) }
                HarnessLog.audit(TAG, "Antigravity sign-in complete (verified headlessly, email=${email ?: "<hidden>"})")
                _state.value = AuthState(
                    Provider.AGY,
                    Status.CONNECTED,
                    message = email?.let { "Google account connected as $it" } ?: "Google account connected.",
                    accountEmail = email,
                )
            }
        }
    }

    private fun finishOnExit(context: Context, provider: Provider, transcriptTail: String) {
        when (provider) {
            Provider.CLAUDE -> {
                // claude exits after printing the token; the token path usually runs first,
                // but handle the race where the process exits with the token on screen.
                val token = HarnessStreamJson.extractClaudeToken(transcriptTail)
                if (token != null) {
                    storeClaudeToken(context, token)
                } else {
                    _state.value = AuthState(Provider.CLAUDE, Status.ERROR, message = "Claude exited before the token was captured. ${transcriptTail.take(200)}")
                }
            }
            Provider.AGY -> {
                _state.value = AuthState(Provider.AGY, Status.ERROR, message = "Antigravity closed before sign-in completed. ${transcriptTail.take(200)}")
            }
        }
    }

    private fun fail(context: Context, provider: Provider, pty: HarnessPtySession, message: String) {
        HarnessLog.w(TAG, "Sign-in failed for $provider: $message")
        pty.destroy()
        if (session === pty) session = null
        _state.value = AuthState(provider, Status.ERROR, message = message)
    }

    private suspend fun waitForFileToStabilize(file: File, settleMs: Long, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSignature = -1L to -1L
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val signature = if (file.isFile) file.length() to file.lastModified() else -1L to -1L
            val now = System.currentTimeMillis()
            if (signature != lastSignature) {
                lastSignature = signature
                stableSince = now
            } else if (signature.first > 0L && now - stableSince >= settleMs) {
                return
            }
            delay(250)
        }
    }

    /** Headless check (`agy models`) — needs a sign-in but makes no model call. */
    suspend fun verifyAgySignIn(context: Context): HarnessStreamJson.AgySignIn = withContext(Dispatchers.IO) {
        val binary = HarnessEnvironment.agyBinary(context)
        val runner = HarnessRunnerSupport.resolve(context, binary) ?: return@withContext HarnessStreamJson.AgySignIn.UNKNOWN
        val authWorkspace = File(HarnessEnvironment.harnessDir(context), "auth").apply { mkdirs() }
        return@withContext try {
            val argv = HarnessRunnerSupport.wrapCommand(context, runner, binary, listOf("models"))
            val process = ProcessBuilder(argv)
                .directory(authWorkspace)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(
                        linkedMapOf(
                            "HOME" to HarnessEnvironment.termuxHome(context).absolutePath,
                            "PATH" to "/system/bin",
                            "NO_COLOR" to "1",
                            "AGY_CLI_DISABLE_AUTO_UPDATE" to "true",
                        ),
                    )
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(90, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                HarnessStreamJson.AgySignIn.UNKNOWN
            } else {
                HarnessStreamJson.agySignInStatus(output, process.exitValue())
            }
        } catch (t: Throwable) {
            HarnessLog.w(TAG, "agy sign-in verification failed: ${t.message}")
            HarnessStreamJson.AgySignIn.UNKNOWN
        }
    }

    private fun isAgySignedInScreen(transcript: String): Boolean =
        transcript.contains("for shortcuts", ignoreCase = true) ||
            (transcript.contains("Antigravity CLI", ignoreCase = true) && transcript.contains("Google AI", ignoreCase = true))

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it, ignoreCase = true) }

    private val EMAIL_REGEX = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)

    private fun prefManagerGet(context: Context, key: String): Boolean =
        runCatching { com.tom.rv2ide.preferences.internal.prefManager.getBoolean(key, false) }.getOrDefault(false)

    private fun prefManagerGetString(context: Context, key: String): String? =
        runCatching { com.tom.rv2ide.preferences.internal.prefManager.getString(key, "") }.getOrNull()

    private fun prefManagerPutBoolean(context: Context, key: String, value: Boolean) {
        runCatching { com.tom.rv2ide.preferences.internal.prefManager.putBoolean(key, value) }
    }

    private fun prefManagerPutString(context: Context, key: String, value: String) {
        runCatching { com.tom.rv2ide.preferences.internal.prefManager.putString(key, value) }
    }
}
