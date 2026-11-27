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
import android.os.Build
import com.tom.rv2ide.preferences.internal.prefManager
import com.tom.rv2ide.utils.Environment
import java.io.File

/**
 * Device and environment detection for the harness runtime.
 *
 * Harnesses run inside the IDE's embedded Termux environment (no PRoot, no extra
 * rootfs): the Termux prefix lives in the app's private filesDir (`files/usr`) and
 * the harness binaries are stored in `files/harness/bin`. Everything here works on
 * all devices that can run the IDE itself; features that need a 64-bit binary are
 * reported and degrade gracefully.
 */
object HarnessEnvironment {

    private const val TAG = "HarnessEnvironment"

    /** Directory holding the harness binaries (claude, agy). */
    fun harnessDir(context: Context): File =
        File(context.applicationContext.filesDir, "harness").apply { mkdirs() }

    fun binDir(context: Context): File =
        File(harnessDir(context), "bin").apply { mkdirs() }

    fun downloadsDir(context: Context): File =
        File(harnessDir(context), "downloads").apply { mkdirs() }

    fun checkpointsDir(context: Context): File =
        File(harnessDir(context), "checkpoints").apply { mkdirs() }

    fun sessionsDir(context: Context): File =
        File(harnessDir(context), "sessions").apply { mkdirs() }

    fun claudeBinary(context: Context): File = File(binDir(context), "claude")
    fun agyBinary(context: Context): File = File(binDir(context), "agy")

    fun claudeMarker(context: Context): File = File(harnessDir(context), ".claude-version")
    fun agyMarker(context: Context): File = File(harnessDir(context), ".agy-version")

    // ------------------------------------------------------------ Termux prefix

    fun termuxPrefix(context: Context): File = File(context.applicationContext.filesDir, "usr")

    fun termuxBin(context: Context): File = File(termuxPrefix(context), "bin")

    fun termuxHome(context: Context): File = File(context.applicationContext.filesDir, "home")

    /** Where the official `agy` CLI stores its OAuth token (owned by agy; the IDE never reads it). */
    fun agyCredentialFile(context: Context): File =
        File(termuxHome(context), ".gemini/antigravity-cli/antigravity-oauth-token")

    /** True when the IDE's embedded Termux environment looks installed (idesetup ran). */
    fun isTermuxBootstrapReady(context: Context): Boolean {
        val bin = termuxBin(context)
        if (!bin.isDirectory) return false
        // The bootstrap always ships coreutils; check a few common binaries.
        return listOf("sh", "bash", "ls", "pkg", "apt").any { File(bin, it).exists() }
    }

    // ------------------------------------------------------------ architecture

    fun primaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    /** Supported by the official prebuilt Claude Code binary. */
    fun supportsClaudeCode(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    /** The official Antigravity CLI manifest currently only ships linux-arm64. */
    fun supportsAntigravity(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    fun claudePlatformToken(): String? = when {
        Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "linux-arm64"
        Build.SUPPORTED_ABIS.contains("x86_64") -> "linux-x64"
        else -> null
    }

    fun isArmV7Only(): Boolean =
        Build.SUPPORTED_ABIS.isNotEmpty() && Build.SUPPORTED_ABIS.none { it == "arm64-v8a" || it == "x86_64" }

    // ------------------------------------------------------------ preferences

    fun isHarnessEnabled(): Boolean = prefManager.getBoolean(KEY_ENABLED, true)

    fun setHarnessEnabled(enabled: Boolean) = prefManager.putBoolean(KEY_ENABLED, enabled)

    /**
     * When true (default), harness sessions are launched with full permission bypass
     * (`--dangerously-skip-permissions` for agy, `--permission-mode bypassPermissions`
     * for Claude Code) so the agent can act without per-tool prompts. The harness
     * working directory is always the open IDE project — never anything else.
     */
    fun allowAllPermissions(): Boolean = prefManager.getBoolean(KEY_ALLOW_ALL, true)

    fun setAllowAllPermissions(enabled: Boolean) = prefManager.putBoolean(KEY_ALLOW_ALL, enabled)

    /** When true (default), the harness is allowed to trust the open IDE project workspace. */
    fun autoTrustProjectWorkspace(): Boolean = prefManager.getBoolean(KEY_AUTO_TRUST, true)

    fun setAutoTrustProjectWorkspace(enabled: Boolean) = prefManager.putBoolean(KEY_AUTO_TRUST, enabled)

    // ------------------------------------------------------------ project scope

    /**
     * The project that is currently opened in the IDE — harnesses are scoped to this
     * directory (as CWD and as the only directory the agent may touch). Null when no
     * project is open or the manager is not ready (callers must handle null).
     */
    fun ideProjectRoot(): File? = runCatching {
        com.tom.rv2ide.utils.ProjectHelper.getProjectRoot()
    }.onFailure { HarnessLog.w(TAG, "Project root unavailable: ${it.message}") }
        .getOrNull()
        ?.takeIf { it.exists() && it.isDirectory }

    /** True when [candidate] is inside (or is) the open IDE project directory. */
    fun isInsideIdeProject(context: Context, candidate: File): Boolean {
        val root = ideProjectRoot() ?: return false
        val rootPath = root.canonicalFile.path
        val candidatePath = runCatching { candidate.canonicalFile.path }.getOrNull() ?: return false
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    // ------------------------------------------------------------ environment vars

    /**
     * Base environment for harness processes. PATH intentionally prefers the Termux
     * prefix so `git`, `java`, `gradle`, `node` (if installed) behave the same as in
     * the IDE terminal. Callers add harness-specific variables (API tokens) on top.
     */
    fun baseEnvironment(context: Context): LinkedHashMap<String, String> {
        val androidHome = runCatching { Environment.ANDROID_HOME?.absolutePath }.getOrNull()
            ?: File(termuxHome(context), "android-sdk").absolutePath
        val path = buildString {
            append(termuxBin(context).absolutePath)
            append(':').append(androidHome).append("/cmdline-tools/latest/bin")
            append(':').append("/system/bin")
        }
        return LinkedHashMap<String, String>().apply {
            put("HOME", termuxHome(context).absolutePath)
            put("PREFIX", termuxPrefix(context).absolutePath)
            put("TMPDIR", Environment.TMP_DIR?.absolutePath ?: File(termuxPrefix(context), "tmp").absolutePath)
            put("PATH", path)
            put("ANDROID_HOME", androidHome)
            put("ANDROID_SDK_ROOT", androidHome)
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("DISABLE_AUTOUPDATER", "1")
            put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
            put("DISABLE_TELEMETRY", "1")
            put("DISABLE_ERROR_REPORTING", "1")
            // agy replaces its own binary in the background on any run, moving users to an
            // untested release behind the recorded install marker. Only "true" is honoured.
            put("AGY_CLI_DISABLE_AUTO_UPDATE", "true")
        }
    }

    const val KEY_ENABLED = "harness_enabled"
    const val KEY_ALLOW_ALL = "harness_allow_all_permissions"
    const val KEY_AUTO_TRUST = "harness_auto_trust_project"
    const val KEY_CLAUDE_TOKEN = "harness_claude_oauth_token"
    const val KEY_AGY_SIGNED_IN = "harness_antigravity_signed_in"
    const val KEY_AGY_EMAIL = "harness_antigravity_email"
}
