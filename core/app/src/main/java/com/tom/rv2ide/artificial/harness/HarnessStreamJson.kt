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

import org.json.JSONObject

/**
 * Parsers for the `stream-json` event formats emitted by Claude Code
 * (`claude -p --output-format stream-json`) and the Antigravity CLI
 * (`agy --output-format stream-json`).
 *
 * Kept deliberately small and defensive: unknown event types are ignored so a new
 * CLI release never breaks the IDE.
 */
object HarnessStreamJson {

    sealed interface ClaudeEvent {
        /** Text delta or final assistant text (non-empty). */
        data class Text(val value: String) : ClaudeEvent
        /** Authoritative final result (or its absence is tolerated). */
        data class Result(
            val text: String,
            val isError: Boolean,
            val errorText: String?,
        ) : ClaudeEvent
        /** Anything else (init, tool events, thinking …) — ignored for UI purposes but useful for logs. */
        data class Ignored(val type: String) : ClaudeEvent
    }

    fun parseClaudeLine(line: String): ClaudeEvent? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (val type = json.optString("type")) {
            "stream_event" -> json.optJSONObject("event")?.let { parseClaudeEventObject(it) }
            "system", "user", "assistant" -> parseClaudeEventObject(json)
            "result" -> {
                val isError = json.optBoolean("is_error", false)
                val text = json.optString("result")
                ClaudeEvent.Result(
                    text = text,
                    isError = isError,
                    errorText = if (isError) text.ifBlank { json.optString("subtype") } else null,
                )
            }
            else -> ClaudeEvent.Ignored(type)
        }
    }

    private fun parseClaudeEventObject(json: JSONObject): ClaudeEvent {
        when (json.optString("type")) {
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return ClaudeEvent.Ignored("content_block_delta")
                val text = delta.optString("text")
                return if (text.isNotEmpty()) ClaudeEvent.Text(text) else ClaudeEvent.Ignored("content_block_delta")
            }
            "assistant" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content")
                    ?: return ClaudeEvent.Ignored("assistant")
                val text = buildString {
                    for (i in 0 until content.length()) {
                        val block = content.optJSONObject(i) ?: continue
                        if (block.optString("type") == "text") append(block.optString("text"))
                    }
                }.trim()
                return if (text.isNotEmpty()) ClaudeEvent.Text(text) else ClaudeEvent.Ignored("assistant")
            }
            "result" -> {
                val isError = json.optBoolean("is_error", false)
                val text = json.optString("result")
                return ClaudeEvent.Result(text, isError, if (isError) text else null)
            }
        }
        return ClaudeEvent.Ignored(json.optString("type"))
    }

    sealed interface AgyEvent {
        data class Initialized(val conversationId: String) : AgyEvent
        data class Text(val value: String) : AgyEvent
        data class ToolStarted(val name: String) : AgyEvent
        data class ToolCompleted(val name: String) : AgyEvent
        data class Result(
            val conversationId: String?,
            val status: String,
            val response: String?,
            val error: String?,
        ) : AgyEvent
    }

    fun parseAgyLine(line: String): AgyEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.optString("event")) {
            "init" -> root.optString("conversation_id")
                .takeIf(String::isNotBlank)
                ?.let(AgyEvent::Initialized)
            "step_update" -> {
                val step = root.optJSONObject("step_update") ?: return null
                val delta = step.optString("text_delta")
                if (delta.isNotEmpty()) return AgyEvent.Text(delta)
                val type = step.optString("step_type")
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val name = step.optString("tool_name")
                        .ifBlank { step.optJSONObject("tool_info")?.optString("name").orEmpty() }
                        .ifBlank { type }
                    return if (step.optString("state").uppercase() in setOf("DONE", "ERROR")) {
                        AgyEvent.ToolCompleted(name)
                    } else {
                        AgyEvent.ToolStarted(name)
                    }
                }
                null
            }
            "result" -> {
                val result = root.optJSONObject("result") ?: root
                AgyEvent.Result(
                    conversationId = result.optString("conversation_id").takeIf(String::isNotBlank),
                    status = result.optString("status", "ERROR"),
                    response = result.optString("response").takeIf(String::isNotBlank),
                    error = result.optString("error").takeIf(String::isNotBlank),
                )
            }
            else -> null // forward compatible
        }
    }

    /** Account-selection check for `agy models` — no model call, sign-in required. */
    fun agySignInStatus(output: String, exitCode: Int): AgySignIn {
        val value = output.replace(Regex("\\s+"), " ")
        val markers = listOf(
            "please sign in",
            "sign in required",
            "authentication required",
            "not logged in",
            "not logged into",
            "not signed in",
        )
        return when {
            markers.any { value.contains(it, ignoreCase = true) } -> AgySignIn.SIGNED_OUT
            exitCode == 0 -> AgySignIn.SIGNED_IN
            else -> AgySignIn.UNKNOWN
        }
    }

    enum class AgySignIn { SIGNED_IN, SIGNED_OUT, UNKNOWN }

    /**
     * Extract the Google OAuth URL printed by agy's login screen.
     *
     * The TUI hard-wraps the URL across rows; removing ALL whitespace reconstructs it, but
     * trailing TUI labels can then glue onto the end. The PKCE `state` parameter is the
     * last one emitted, so the match terminates at its base64url-safe value.
     */
    fun extractGoogleOAuthUrl(output: String): String? {
        val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
        GOOGLE_OAUTH_URL.find(compact)?.value
            ?.takeIf { "client_id=" in it && "code_challenge=" in it }
            ?.let { return it }
        if (!output.contains("Select login method", ignoreCase = true) &&
            (output.contains("browser", true) || output.contains("visit", true) ||
                output.contains("open", true) || output.contains("code", true) ||
                output.contains("paste", true))
        ) {
            val candidates = Regex("https://[^\\s\"']{20,}")
                .findAll(compact)
                .map { it.value.trimEnd { ch -> ch !in URL_CHARACTERS } }
                .filter { it.length >= 30 && "." in it }
                .toList()
            return candidates.firstOrNull { "google" in it } ?: candidates.firstOrNull()
        }
        return null
    }

    /** Claude Code prints long-lived tokens (sk-ant-oat01-…) after `claude setup-token`. */
    fun extractClaudeToken(output: String): String? =
        CLAUDE_TOKEN.find(output)?.value

    /** Find the first plausible https URL (>= 30 chars) in terminal output. */
    fun extractFirstUrl(output: String): String? {
        val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
        return Regex("https://[^\\s\"'<>)]{20,}")
            .findAll(compact)
            .map { it.value.trimEnd { ch -> ch !in URL_CHARACTERS } }
            .firstOrNull { it.length >= 30 }
    }

    private val URL_CHARACTERS = (
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;=%"
        ).toSet()

    private val GOOGLE_OAUTH_URL = Regex(
        "https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+",
    )

    private val CLAUDE_TOKEN = Regex("sk-ant-oat[0-9]*-[A-Za-z0-9_-]{10,}")
}
