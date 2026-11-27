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
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Prepares the files Claude Code reads before a headless run:
 *
 *  - `~/.claude/settings.json`: permission default (`acceptEdits`) plus a `PermissionRequest`
 *    hook that approves `Bash|Edit|Write|NotebookEdit` tool calls when the user has enabled
 *    "Allow All Permissions" in the Harness settings. When the toggle is off, the default
 *    mode is restored and the hook removed, so headless runs are read-only (every tool call
 *    requires an interactive approval which cannot be granted without a terminal).
 *  - `~/.claude.json`: pre-accepts the "Do you trust this folder?" dialog for the project
 *    open in the IDE — only when "Auto-Trust Open Project" is enabled. Nothing else in
 *    these files is touched; foreign keys are preserved.
 *
 * The hook script is installed at `filesDir/harness/bin/harness-permission-hook.sh`.
 */
object HarnessAutonomy {

    private const val TAG = "HarnessAutonomy"

    /** Re-apply the user's current harness permission/trust choices. Idempotent. */
    fun ensureClaudeSetup(context: Context, projectRoot: File?) {
        try {
            val allowAll = HarnessEnvironment.allowAllPermissions()
            val autoTrust = HarnessEnvironment.autoTrustProjectWorkspace()
            installPermissionHook(context, enable = allowAll)
            mergeClaudeSettings(context, allowAll = allowAll)
            projectRoot?.let { mergeWorkspaceTrust(context, it, trust = autoTrust) }
            HarnessLog.audit(TAG, "Claude autonomy applied (allowAll=$allowAll, autoTrust=$autoTrust, project=${projectRoot?.name ?: "<none>"})")
        } catch (t: Throwable) {
            HarnessLog.e(TAG, "Failed to apply Claude autonomy settings", t)
        }
    }

    private fun installPermissionHook(context: Context, enable: Boolean) {
        val bin = HarnessEnvironment.binDir(context)
        bin.mkdirs()
        val hook = File(bin, "harness-permission-hook.sh")
        if (!enable) {
            // Best effort: remove the script; mergeClaudeSettings removes its registration.
            runCatching { hook.delete() }
            return
        }
        hook.writeText(
            "#!/bin/sh\n" +
                "cat > /dev/null\n" +
                "printf '%s\\n' '{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\",\"decision\":{\"behavior\":\"allow\"}}}'\n",
        )
        hook.setExecutable(true, false)
        hook.setReadable(true, false)
    }

    private fun claudeDir(context: Context): File =
        File(HarnessEnvironment.termuxHome(context), ".claude")

    private fun hookCommand(context: Context): String =
        File(HarnessEnvironment.binDir(context), "harness-permission-hook.sh").absolutePath

    private fun mergeClaudeSettings(context: Context, allowAll: Boolean) {
        val dir = claudeDir(context)
        dir.mkdirs()
        val settingsFile = File(dir, "settings.json")
        val settings = runCatching {
            settingsFile.takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        }.getOrNull() ?: JSONObject()

        val permissions = settings.optJSONObject("permissions") ?: JSONObject()

        val hooks = settings.optJSONObject("hooks") ?: JSONObject()
        val existingEntries = hooks.optJSONArray("PermissionRequest") ?: JSONArray()

        // Drop only OUR hook registration (identified by the hook script path), never foreign hooks.
        val ourCommand = hookCommand(context)
        val keptEntries = JSONArray()
        for (i in 0 until existingEntries.length()) {
            val entry = existingEntries.optJSONObject(i) ?: continue
            val hooksArray = entry.optJSONArray("hooks")
            var isOurs = false
            if (hooksArray != null) {
                for (j in 0 until hooksArray.length()) {
                    val hookObj = hooksArray.optJSONObject(j) ?: continue
                    if (hookObj.optString("command") == ourCommand) isOurs = true
                }
            }
            if (!isOurs) keptEntries.put(entry)
        }

        if (allowAll) {
            permissions.put("defaultMode", "acceptEdits")

            val ourEntry = JSONObject()
                .put("matcher", "Bash|Edit|Write|NotebookEdit")
                .put(
                    "hooks",
                    JSONArray().put(
                        JSONObject().put("type", "command").put("command", ourCommand),
                    ),
                )
            keptEntries.put(ourEntry)
            hooks.put("PermissionRequest", keptEntries)
        } else {
            // Disable our autonomy: keep foreign settings intact, reset to conservative defaults.
            if (permissions.optString("defaultMode") == "acceptEdits") {
                permissions.put("defaultMode", "default")
            }
            if (keptEntries.length() > 0) {
                hooks.put("PermissionRequest", keptEntries)
            } else {
                hooks.remove("PermissionRequest")
            }
        }

        settings.put("permissions", permissions)
        if (hooks.length() > 0) settings.put("hooks", hooks) else settings.remove("hooks")
        settingsFile.writeText(settings.toString(2))
    }

    private fun mergeWorkspaceTrust(context: Context, projectRoot: File, trust: Boolean) {
        val stateFile = File(HarnessEnvironment.termuxHome(context), ".claude.json")
        val state = runCatching {
            stateFile.takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        }.getOrNull() ?: JSONObject()

        val projects = state.optJSONObject("projects") ?: JSONObject()
        val projectKey = projectRoot.absolutePath
        val project = projects.optJSONObject(projectKey) ?: JSONObject()
        project.put("hasTrustDialogAccepted", trust)
        projects.put(projectKey, project)
        state.put("projects", projects)
        stateFile.writeText(state.toString(2))
    }

}
