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

/**
 * The harness agents for Agent Mode.
 *
 * Unlike the HTTP agents (Gemini, OpenAI, …), these run the official harness CLIs
 * (`claude`, `agy`) as headless subprocesses on this device, working on the project
 * open in the IDE as their working directory. All file edits are performed by the
 * harness itself — the IDE shows the agent's final report as a text response.
 *
 * Hard rules:
 *  - No project open in the IDE → the request fails fast (never runs in $HOME).
 *  - Everything the harness reads/writes is inside the IDE's embedded Termux
 *    environment and the open project; nothing else on the device is in scope.
 *  - The subscription token / Google credential are owned by the official CLIs
 *    (Android Keystore / agy's own credential file). The IDE never logs them.
 */

package com.tom.rv2ide.artificial.agents.harness

import android.content.Context
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.harness.HarnessAuth
import com.tom.rv2ide.artificial.harness.HarnessAutonomy
import com.tom.rv2ide.artificial.harness.HarnessEnvironment
import com.tom.rv2ide.artificial.harness.HarnessKeystore
import com.tom.rv2ide.artificial.harness.HarnessLog
import com.tom.rv2ide.artificial.harness.HarnessRunnerSupport
import com.tom.rv2ide.artificial.harness.HarnessStreamJson
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "HarnessAgent"
private const val RUN_TIMEOUT_MINUTES = 10L

private const val PROJECT_SCOPE_INSTRUCTION =
    "You are running inside Android Code Studio, an Android IDE on a phone. " +
        "Work ONLY on the project at the current working directory. " +
        "Do not read, write, or delete files outside of it, and do not modify global state " +
        "(shell profiles, IDE configuration) unless the user explicitly asks you to. " +
        "Prefer small, reviewable changes and summarize what you did at the end."

abstract class HarnessAgentBase : AIAgent {

    protected var appContext: Context? = null
    protected var selectedModel: String = ""
    protected var projectTreeResult: ProjectTreeResult? = null
    private val modificationHistory = mutableListOf<ModificationAttempt>()
    private var currentAttemptCount = 0
    private val maxRetryAttempts = 2
    private var fileWriter: AIFileWriter? = null

    /** Provider-specific command line, or null with an error message. */
    protected abstract fun buildCommand(
        context: Context,
        prompt: String,
        projectRoot: File,
    ): Result<List<String>>

    /** Extra (non-secret) environment entries merged over [HarnessEnvironment.baseEnvironment]. */
    protected abstract fun extraEnvironment(context: Context): Map<String, String>

    /** Store/refresh provider-side state before every run (trust, hooks …). */
    protected abstract fun beforeRun(context: Context, projectRoot: File)

    /** NDJSON payload written to the process stdin before it is closed; null for argv-prompt CLIs. */
    protected open fun stdinPayload(prompt: String): String? = null

    override fun initialize(apiKey: String, context: Context) {
        appContext = context.applicationContext
        fileWriter = AIFileWriter(context)
        val agents = Agents(context)
        val candidate = agents.getAgent()
        selectedModel = if (agents.isValidModelForProvider(candidate, providerId)) {
            candidate
        } else {
            agents.getModelsForProvider(providerId).firstOrNull() ?: ""
        }.also { if (candidate != it) agents.setAgent(it) }
        HarnessLog.audit(TAG, "Harness agent initialized: $providerId (model=${selectedModel.ifBlank { "default" }})")
    }

    override fun reinitializeWithNewModel(apiKey: String, context: Context) = initialize(apiKey, context)

    override fun setContext(context: Context) {
        appContext = context.applicationContext
        fileWriter = AIFileWriter(context)
    }

    override fun setProjectData(projectTreeResult: ProjectTreeResult) {
        this.projectTreeResult = projectTreeResult
    }

    override fun clearConversation() {
        modificationHistory.clear()
        currentAttemptCount = 0
    }

    override fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean) {
        modificationHistory.add(
            ModificationAttempt(
                timestamp = System.currentTimeMillis(),
                filePath = filePath,
                previousContent = oldContent,
                newContent = newContent,
                attemptNumber = currentAttemptCount,
                success = success,
            ),
        )
    }

    override fun undoLastModification(): Boolean {
        // The harness edits files itself; the IDE cannot replay its internal edit journal.
        HarnessLog.w(TAG, "undoLastModification requested for $providerId — harness edits are applied directly and cannot be undone by the IDE")
        return false
    }

    override fun getModificationHistory(): List<ModificationAttempt> = modificationHistory.toList()

    override fun resetAttemptCount() {
        currentAttemptCount = 0
    }

    override fun incrementAttemptCount() {
        currentAttemptCount += 1
    }

    override fun getCurrentAttemptCount(): Int = currentAttemptCount

    override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

    override fun writeFile(filePath: String, content: String): FileWriteResult {
        // Project-scope guard: the IDE only allows writes inside the open project.
        val context = appContext
            ?: return FileWriteResult.Error("Harness agent is not initialized.")
        val target = File(filePath)
        if (!HarnessEnvironment.isInsideIdeProject(context, target)) {
            HarnessLog.w(TAG, "Refused out-of-project write request for $target")
            return FileWriteResult.Error("Harness agents may only write inside the project open in the IDE.")
        }
        val writer = fileWriter ?: return FileWriteResult.Error("Harness agent is not initialized.")
        return writer.writeFile(filePath, content)
    }

    override fun isInitialized(): Boolean = appContext != null

    override suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        val ctx = appContext
            ?: return@withContext Result.failure(IllegalStateException("Harness agent is not initialized."))
        val projectRoot = HarnessEnvironment.ideProjectRoot()
            ?: return@withContext Result.failure(
                IllegalStateException("Open a project in the IDE first. The harness only works on the project open in the IDE."),
            )
        if (!HarnessEnvironment.isHarnessEnabled()) {
            return@withContext Result.failure(IllegalStateException("The Harness is disabled in AI Preferences."))
        }

        beforeRun(ctx, projectRoot)

        val fullPrompt = buildString {
            appendLine(PROJECT_SCOPE_INSTRUCTION)
            appendLine()
            append(prompt)
        }

        val command = buildCommand(ctx, fullPrompt, projectRoot)
            .getOrElse { return@withContext Result.failure(it) }

        val sessionId = UUID.randomUUID().toString().take(8)
        HarnessLog.beginSession(ctx, sessionId, providerId, projectRoot.absolutePath)
        HarnessLog.i(TAG, "Session $sessionId starting: ${command.first()} (cwd=${projectRoot.name}, model=${selectedModel.ifBlank { "default" }})")

        val env = LinkedHashMap(HarnessEnvironment.baseEnvironment(ctx))
        env.putAll(extraEnvironment(ctx))

        try {
            val stdinPayload = stdinPayload(fullPrompt)
            val outcome = runProcess(ctx, command, projectRoot, env, sessionId, stdinPayload)
            HarnessLog.endSession(ctx, sessionId, outcome.first)
            val text = outcome.second
            if (outcome.first == "success") {
                Result.success(text)
            } else {
                Result.failure(classifyError(text))
            }
        } catch (t: Throwable) {
            HarnessLog.endSession(ctx, sessionId, "error: ${t.message}")
            Result.failure(t)
        }
    }

    /** Returns (outcomeLabel, textOrError). Never throws for harness-reported errors. */
    private fun runProcess(
        context: Context,
        command: List<String>,
        cwd: File,
        env: Map<String, String>,
        sessionId: String,
        stdinPayload: String?,
    ): Pair<String, String> {
        val process = ProcessBuilder(command)
            .directory(cwd)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(env)
            }
            .start()

        if (stdinPayload != null) {
            runCatching {
                process.outputStream.write(stdinPayload.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }.onFailure { HarnessLog.w(TAG, "Failed to write stdin for session $sessionId: ${it.message}") }
        }
        runCatching { process.outputStream.close() }

        // Watchdog: the timeout check below only fires once a line is read, so kill the
        // process from another thread to guarantee termination even on silent hangs.
        val watchdog = Thread {
            try {
                Thread.sleep(RUN_TIMEOUT_MINUTES * 60_000L)
                if (process.isAlive) {
                    HarnessLog.w(TAG, "Session $sessionId exceeded ${RUN_TIMEOUT_MINUTES}m — watchdog killing process")
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                // run finished before the timeout — nothing to do
            }
        }.apply { isDaemon = true; start() }

        val streamText = StringBuilder()
        var resultText: String? = null
        var resultError = false
        var resultErrorText: String? = null

        val stdout = StringBuilder()
        val start = System.currentTimeMillis()
        val reader = process.inputStream.bufferedReader()
        while (true) {
            val line = try {
                reader.readLine()
            } catch (t: Throwable) {
                null
            } ?: break
            stdout.append(line).append('\n')
            when (val parsed = parseProviderLine(line)) {
                is Parsed.Text -> streamText.append(parsed.value)
                is Parsed.Result -> {
                    resultText = parsed.text
                    resultError = parsed.isError
                    resultErrorText = parsed.errorText
                }
                null -> HarnessLog.d(TAG, "[$sessionId] $line")
            }
            if (System.currentTimeMillis() - start > RUN_TIMEOUT_MINUTES * 60_000L) {
                process.destroyForcibly()
                HarnessLog.w(TAG, "Session $sessionId exceeded ${RUN_TIMEOUT_MINUTES}m — process killed")
                break
            }
        }
        watchdog.interrupt()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        val exit = if (finished) process.exitValue() else -1

        val finalText = resultText?.trim()?.takeIf { it.isNotEmpty() }
            ?: streamText.toString().trim().takeIf { it.isNotEmpty() }
            ?: stdout.toString().trim().take(4_000)

        return if (resultError) {
            val message = resultErrorText?.ifBlank { null }
                ?: finalText.ifBlank { "The harness reported an error (exit=$exit)." }
            HarnessLog.w(TAG, "Session $sessionId failed: ${message.take(300)}")
            "failure" to message
        } else if (finalText.isBlank() && exit != 0) {
            HarnessLog.w(TAG, "Session $sessionId exited with $exit and no output")
            "failure" to "The harness exited with code $exit and produced no output. See the harness logs."
        } else {
            HarnessLog.audit(TAG, "Session $sessionId completed (exit=$exit, output=${finalText.length} chars)")
            "success" to finalText.ifBlank { "Done." }
        }
    }

    protected sealed interface Parsed {
        data class Text(val value: String) : Parsed
        data class Result(val text: String, val isError: Boolean, val errorText: String?) : Parsed
    }

    protected abstract fun parseProviderLine(line: String): Parsed?

    private fun classifyError(message: String): Exception {
        val value = message.lowercase()
        return when {
            "rate limit" in value || "rate_limit" in value || "429" in value || "overloaded" in value ->
                RateLimitException(message)
            "quota" in value || "out of credits" in value || "usage limit" in value ->
                QuotaExceededException(message)
            "insufficient" in value && "balance" in value ->
                InsufficientBalanceException(message)
            ("invalid" in value && (
                "api key" in value ||
                    "authentication" in value ||
                    "oauth" in value ||
                    "token" in value
                )) ||
                "authentication_error" in value ||
                "unauthorized" in value ->
                InvalidApiKeyException(message)
            else -> IllegalStateException(message)
        }
    }
}

/** Resolve the application context the same way the rest of the IDE does. */
private fun ideContext(): Context? = runCatching { BaseApplication.getBaseInstance().applicationContext }.getOrNull()

/** Claude Code (`claude -p … --output-format stream-json`). */
class ClaudeCodeHarnessAgent : HarnessAgentBase() {

    override val providerId = "claude_harness"
    override val providerName = "Claude Code (Harness)"

    companion object {
        fun registerAgent() {
            AIAgentRegistry.register("claude_harness", object : AIAgentRegistry.AgentFactory {
                override fun create(context: Context): AIAgent = ClaudeCodeHarnessAgent()

                override fun hasValidApiKey(): Boolean {
                    val context = ideContext() ?: return false
                    return HarnessEnvironment.isHarnessEnabled() &&
                        HarnessEnvironment.claudeBinary(context).isFile &&
                        HarnessKeystore.has(context, HarnessEnvironment.KEY_CLAUDE_TOKEN)
                }

                override fun getApiKey(): String? = "harness"
            })
        }
    }

    override fun beforeRun(context: Context, projectRoot: File) {
        HarnessAutonomy.ensureClaudeSetup(context, projectRoot)
    }

    override fun buildCommand(
        context: Context,
        prompt: String,
        projectRoot: File,
    ): Result<List<String>> {
        val binary = HarnessEnvironment.claudeBinary(context)
        if (!binary.isFile) {
            return Result.failure(
                IllegalStateException("Claude Code is not installed. Install it from AI Preferences → Harness (Experimental)."),
            )
        }
        val runner = HarnessRunnerSupport.resolve(context, binary)
            ?: return Result.failure(IllegalStateException(HarnessRunnerSupport.glibcInstallHelp()))
        val args = buildList {
            add("-p")
            add(prompt)
            add("--output-format")
            add("stream-json")
            add("--include-partial-messages")
            add("--verbose")
            add("--max-turns")
            add("25")
            if (selectedModel.isNotBlank() && selectedModel != "default") {
                add("--model")
                add(selectedModel)
            }
        }
        return Result.success(HarnessRunnerSupport.wrapCommand(context, runner, binary, args))
    }

    override fun extraEnvironment(context: Context): Map<String, String> {
        val token = HarnessKeystore.read(context, HarnessEnvironment.KEY_CLAUDE_TOKEN).orEmpty()
        return linkedMapOf(
            "CLAUDE_CODE_OAUTH_TOKEN" to token,
            // Never let stray API keys shadow the subscription token.
            "ANTHROPIC_API_KEY" to "",
            "ANTHROPIC_AUTH_TOKEN" to "",
            "DISABLE_AUTOUPDATER" to "1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
            "DISABLE_TELEMETRY" to "1",
            "DISABLE_ERROR_REPORTING" to "1",
        )
    }

    override fun parseProviderLine(line: String): Parsed? =
        when (val event = HarnessStreamJson.parseClaudeLine(line)) {
            is HarnessStreamJson.ClaudeEvent.Text -> Parsed.Text(event.value)
            is HarnessStreamJson.ClaudeEvent.Result -> Parsed.Result(event.text, event.isError, event.errorText)
            is HarnessStreamJson.ClaudeEvent.Ignored, null -> null
        }
}

/** Antigravity CLI (`agy --input-format stream-json …`). */
class AntigravityHarnessAgent : HarnessAgentBase() {

    override val providerId = "antigravity_harness"
    override val providerName = "Antigravity (Harness)"

    companion object {
        fun registerAgent() {
            AIAgentRegistry.register("antigravity_harness", object : AIAgentRegistry.AgentFactory {
                override fun create(context: Context): AIAgent = AntigravityHarnessAgent()

                override fun hasValidApiKey(): Boolean {
                    val context = ideContext() ?: return false
                    return HarnessEnvironment.isHarnessEnabled() &&
                        HarnessEnvironment.agyBinary(context).isFile &&
                        HarnessAuth.isAgyConnected(context)
                }

                override fun getApiKey(): String? = "harness"
            })
        }
    }

    override fun beforeRun(context: Context, projectRoot: File) {
        // agy has no settings file; autonomy is granted via --dangerously-skip-permissions at launch.
    }

    override fun buildCommand(
        context: Context,
        prompt: String,
        projectRoot: File,
    ): Result<List<String>> {
        val binary = HarnessEnvironment.agyBinary(context)
        if (!binary.isFile) {
            return Result.failure(
                IllegalStateException("The Antigravity CLI is not installed. Install it from AI Preferences → Harness (Experimental)."),
            )
        }
        if (!HarnessAuth.isAgyConnected(context)) {
            return Result.failure(
                IllegalStateException("No Google account connected. Connect your Google account from AI Preferences → Harness (Experimental)."),
            )
        }
        val runner = HarnessRunnerSupport.resolve(context, binary)
            ?: return Result.failure(IllegalStateException(HarnessRunnerSupport.glibcInstallHelp()))
        val args = buildList {
            add("--input-format")
            add("stream-json")
            add("--output-format")
            add("stream-json")
            add("--print-timeout")
            add("60m")
            // The IDE runs agy headlessly; permission prompts cannot be answered without
            // a terminal, so autonomy is required for the agent to do any work.
            if (HarnessEnvironment.allowAllPermissions()) {
                add("--dangerously-skip-permissions")
            }
            if (selectedModel.isNotBlank() && selectedModel != "default") {
                add("--model")
                add(selectedModel)
            }
            add("--new-project")
        }
        return Result.success(HarnessRunnerSupport.wrapCommand(context, runner, binary, args))
    }

    override fun stdinPayload(prompt: String): String =
        JSONObject()
            .put("event", "user")
            .put("message", JSONObject().put("content", prompt))
            .toString() + "\n"

    override fun extraEnvironment(context: Context): Map<String, String> =
        linkedMapOf("AGY_CLI_DISABLE_AUTO_UPDATE" to "true")

    override fun parseProviderLine(line: String): Parsed? =
        when (val event = HarnessStreamJson.parseAgyLine(line)) {
            is HarnessStreamJson.AgyEvent.Text -> Parsed.Text(event.value)
            is HarnessStreamJson.AgyEvent.Initialized -> null
            is HarnessStreamJson.AgyEvent.ToolStarted -> {
                HarnessLog.i(TAG, "agy tool started: ${event.name}")
                null
            }
            is HarnessStreamJson.AgyEvent.ToolCompleted -> {
                HarnessLog.i(TAG, "agy tool finished: ${event.name}")
                null
            }
            is HarnessStreamJson.AgyEvent.Result -> Parsed.Result(
                event.response ?: "",
                !event.status.equals("SUCCESS", ignoreCase = true),
                event.error,
            )
            else -> null // unparseable line — ignore, keep reading
        }
}
