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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.harness.HarnessAuth.Provider
import com.tom.rv2ide.utils.IntentUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wires the "Harness (Experimental)" section in `fragment_ai_preferences.xml`:
 * toggles, install buttons, connect/sign-out dialogs, log viewing/export/clear
 * and installation reset. Kept separate from the fragment to preserve the
 * fragment's existing responsibilities.
 */
class HarnessSettingsSection(
    private val rootView: View,
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
) {

    private lateinit var enableToggle: MaterialSwitch
    private lateinit var allowAllToggle: MaterialSwitch
    private lateinit var autoTrustToggle: MaterialSwitch
    private lateinit var statusText: TextView
    private lateinit var installClaudeButton: MaterialButton
    private lateinit var installAgyButton: MaterialButton
    private lateinit var installGlibcButton: MaterialButton
    private lateinit var connectClaudeButton: MaterialButton
    private lateinit var connectAgyButton: MaterialButton
    private lateinit var recheckButton: MaterialButton
    private lateinit var viewLogsButton: MaterialButton
    private lateinit var exportLogsButton: MaterialButton
    private lateinit var clearLogsButton: MaterialButton
    private lateinit var resetButton: MaterialButton

    fun bind() {
        enableToggle = rootView.findViewById(R.id.harnessEnableToggle)
        allowAllToggle = rootView.findViewById(R.id.harnessAllowAllToggle)
        autoTrustToggle = rootView.findViewById(R.id.harnessAutoTrustToggle)
        statusText = rootView.findViewById(R.id.harnessStatusText)
        installClaudeButton = rootView.findViewById(R.id.harnessInstallClaudeButton)
        installAgyButton = rootView.findViewById(R.id.harnessInstallAgyButton)
        installGlibcButton = rootView.findViewById(R.id.harnessGlibcInstallButton)
        connectClaudeButton = rootView.findViewById(R.id.harnessConnectClaudeButton)
        connectAgyButton = rootView.findViewById(R.id.harnessConnectAgyButton)
        recheckButton = rootView.findViewById(R.id.harnessRecheckButton)
        viewLogsButton = rootView.findViewById(R.id.harnessViewLogsButton)
        exportLogsButton = rootView.findViewById(R.id.harnessExportLogsButton)
        clearLogsButton = rootView.findViewById(R.id.harnessClearLogsButton)
        resetButton = rootView.findViewById(R.id.harnessResetButton)

        enableToggle.isChecked = HarnessEnvironment.isHarnessEnabled()
        allowAllToggle.isChecked = HarnessEnvironment.allowAllPermissions()
        autoTrustToggle.isChecked = HarnessEnvironment.autoTrustProjectWorkspace()

        enableToggle.setOnCheckedChangeListener { _, checked ->
            HarnessEnvironment.setHarnessEnabled(checked)
            HarnessLog.audit(TAG, "Harness ${if (checked) "enabled" else "disabled"} by user")
            refreshStatus()
        }
        allowAllToggle.setOnCheckedChangeListener { _, checked ->
            HarnessEnvironment.setAllowAllPermissions(checked)
            HarnessAutonomy.ensureClaudeSetup(context, HarnessEnvironment.ideProjectRoot())
            HarnessLog.audit(TAG, "Harness allow-all permissions ${if (checked) "enabled" else "disabled"} by user")
        }
        autoTrustToggle.setOnCheckedChangeListener { _, checked ->
            HarnessEnvironment.setAutoTrustProjectWorkspace(checked)
            HarnessAutonomy.ensureClaudeSetup(context, HarnessEnvironment.ideProjectRoot())
            HarnessLog.audit(TAG, "Harness auto-trust ${if (checked) "enabled" else "disabled"} by user")
        }

        installClaudeButton.setOnClickListener { install(Provider.CLAUDE) }
        installAgyButton.setOnClickListener { install(Provider.AGY) }
        installGlibcButton.setOnClickListener { confirmInstallGlibc() }
        connectClaudeButton.setOnClickListener { toggleConnection(Provider.CLAUDE) }
        connectAgyButton.setOnClickListener { toggleConnection(Provider.AGY) }
        recheckButton.setOnClickListener { refreshStatus() }
        viewLogsButton.setOnClickListener { showLogViewer() }
        exportLogsButton.setOnClickListener { exportLogs() }
        clearLogsButton.setOnClickListener { confirmClearLogs() }
        resetButton.setOnClickListener { confirmResetInstallation() }

        refreshStatus()
    }

    /** Refresh status text and Connected/Connect labels. Safe to call from onResume. */
    fun refreshStatus() {
        setButtonsBusy(true)
        lifecycleOwner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { HarnessInstaller.status(context) }
            statusText.text = buildStatusText(status)
            connectClaudeButton.text =
                if (HarnessAuth.isClaudeConnected(context)) "Sign out Claude" else "Connect Claude"
            connectAgyButton.text =
                if (HarnessAuth.isAgyConnected(context)) "Sign out Google" else "Connect Google"
            installClaudeButton.text =
                if (status.claudeInstalled) "Reinstall Claude Code" else "Install Claude Code"
            installAgyButton.text =
                if (status.agyInstalled) "Reinstall Antigravity CLI" else "Install Antigravity CLI"
            installGlibcButton.text =
                if (status.glibcAvailable) "Reinstall glibc Layer" else "Install glibc Layer (required for the CLIs)"
            setButtonsBusy(false)
        }
    }

    // ------------------------------------------------------------------ install

    private fun setButtonsBusy(busy: Boolean) {
        installClaudeButton.isEnabled = !busy
        installAgyButton.isEnabled = !busy
        installGlibcButton.isEnabled = !busy
        connectClaudeButton.isEnabled = !busy
        connectAgyButton.isEnabled = !busy
        recheckButton.isEnabled = !busy
    }

    private fun install(provider: Provider) {
        setButtonsBusy(true)
        statusText.text = "Starting installation…"
        lifecycleOwner.lifecycleScope.launch {
            val onProgress: suspend (Float, String) -> Unit = { fraction, message ->
                withContext(Dispatchers.Main) {
                    statusText.text = "$message (${(fraction * 100).toInt()}%)"
                }
            }
            val result = withContext(Dispatchers.IO) {
                when (provider) {
                    Provider.CLAUDE -> HarnessInstaller.installClaude(context, onProgress)
                    Provider.AGY -> HarnessInstaller.installAgy(context, onProgress)
                }
            }
            when (result) {
                is HarnessInstaller.InstallResult.Success -> {
                    snack("${if (provider == Provider.CLAUDE) "Claude Code" else "Antigravity CLI"} ${result.version} installed.")
                    HarnessLog.audit(TAG, "User installed $provider ${result.version}")
                }
                is HarnessInstaller.InstallResult.Failure -> {
                    snack("Install failed — see logs", long = true)
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Installation failed")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Copy report") { _, _ ->
                            lifecycleOwner.lifecycleScope.launch {
                                val tail = withContext(Dispatchers.IO) { HarnessLog.tail(context, 4_000) }
                                copyToClipboard(
                                    "Harness install error",
                                    "Android Code Studio — harness install failed\n\n${result.message}\n\n----- log tail -----\n$tail",
                                )
                            }
                        }
                        .show()
                }
            }
            refreshStatus()
        }
    }

    // ------------------------------------------------------------------ glibc layer

    private fun confirmInstallGlibc() {
        MaterialAlertDialogBuilder(context)
            .setTitle("Install the glibc compatibility layer?")
            .setMessage(
                "This downloads and installs Termux's glibc packages (glibc-repo, glibc, glibc-runner, " +
                    "about 30 MB) into the IDE's built-in Termux prefix — exactly what " +
                    "'pkg install glibc-repo && pkg install glibc glibc-runner' would do in the IDE terminal. " +
                    "Nothing outside the IDE's private environment is modified. A network connection is required.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ -> installGlibcLayer() }
            .show()
    }

    private fun installGlibcLayer() {
        setButtonsBusy(true)
        statusText.text = "Installing the glibc layer…"
        lifecycleOwner.lifecycleScope.launch {
            val home = HarnessEnvironment.termuxHome(context).apply { mkdirs() }
            val prefix = HarnessEnvironment.termuxPrefix(context)
            val prefixBin = File(prefix, "bin")
            val sh = File(prefixBin, "sh")
            if (!sh.isFile) {
                setButtonsBusy(false)
                statusText.text =
                    if (HarnessEnvironment.isTermuxBootstrapReady(context)) {
                        "The IDE's 'sh' is missing — the embedded environment looks broken. See the harness logs."
                    } else {
                        "The IDE's environment is not set up yet. Finish the first-launch IDE setup first."
                    }
                return@launch
            }
            val script = "pkg install -y glibc-repo && pkg install -y glibc glibc-runner"
            val command = listOf(sh.absolutePath, "-c", script)
            val env = listOf(
                "HOME=${home.absolutePath}",
                "PREFIX=${prefix.absolutePath}",
                "TMPDIR=${File(prefix, "tmp").absolutePath}",
                "PATH=${prefixBin.absolutePath}:/system/bin",
                "TERM=dumb",
                "LANG=C.UTF-8",
            )
            // start() must run on the main thread (TerminalSession requirement) — we are on it.
            val pty = HarnessPtySession(
                context = context,
                shellPath = command.first(),
                args = command,
                cwd = home,
                env = env,
            )
            if (!pty.start()) {
                setButtonsBusy(false)
                statusText.text = "Could not start the glibc installer. See the harness logs."
                return@launch
            }
            // Poll the transcript and mirror progress into the status line; cap at 5 minutes.
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            var lastLine = ""
            while (System.currentTimeMillis() < deadline) {
                delay(500)
                val line = pty.transcript().lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (line.isNotEmpty() && line != lastLine) {
                    lastLine = line
                    statusText.text = "Installing the glibc layer…\n${line.take(120)}"
                }
                if (!pty.isRunning()) break
            }
            val exited = pty.waitFor(1_000)
            val transcript = pty.transcript().trim()
            if (pty.isRunning()) pty.destroy()
            // The glibc binaries may have appeared — drop cached runner markers so the next probe re-resolves.
            HarnessRunnerSupport.clearRunnerCache(context)
            setButtonsBusy(false)
            if (exited == 0) {
                HarnessLog.audit(TAG, "glibc compatibility layer installed by user")
                snack("glibc layer installed.")
                refreshStatus()
            } else {
                HarnessLog.w(TAG, "glibc install failed (exit=${exited ?: "timeout"})\n${transcript.takeLast(3_000)}")
                showGlibcInstallError(transcript, exited)
                refreshStatus()
            }
        }
    }

    private fun showGlibcInstallError(output: String, exitCode: Int?) {
        val report = buildString {
            appendLine("Android Code Studio — glibc layer install failed")
            appendLine("===============================================")
            appendLine()
            appendLine("Command : pkg install -y glibc-repo && pkg install -y glibc glibc-runner")
            appendLine("Exit    : ${exitCode?.toString() ?: "timeout after 5 minutes"}")
            appendLine()
            appendLine("----- output (last 6000 characters) -----")
            appendLine(output.takeLast(6_000).ifBlank { "(no output was captured)" })
            appendLine("-----------------------------------------")
            appendLine("Please paste this report into the IDE issue tracker.")
        }
        val scroll = ScrollView(context)
        val text = TextView(context).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 24)
            setText(report)
        }
        scroll.addView(text)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.5f).toInt(),
        )
        statusText.text = "glibc install failed — see the error report."
        MaterialAlertDialogBuilder(context)
            .setTitle("glibc install failed")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setNeutralButton("Copy report") { _, _ -> copyToClipboard("Harness glibc install error", report) }
            .setPositiveButton("Retry") { _, _ -> installGlibcLayer() }
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        snack("Copied to clipboard.")
    }

    // ------------------------------------------------------------------ connect

    private fun toggleConnection(provider: Provider) {
        val connected = when (provider) {
            Provider.CLAUDE -> HarnessAuth.isClaudeConnected(context)
            Provider.AGY -> HarnessAuth.isAgyConnected(context)
        }
        if (connected) {
            confirmSignOut(provider)
        } else {
            showAuthDialog(provider)
        }
    }

    private fun confirmSignOut(provider: Provider) {
        MaterialAlertDialogBuilder(context)
            .setTitle(if (provider == Provider.CLAUDE) "Sign out of Claude?" else "Sign out of Google?")
            .setMessage(
                if (provider == Provider.CLAUDE) {
                    "The stored Claude token will be removed from the Android Keystore."
                } else {
                    "Antigravity's credential file will be deleted. The token is never read or copied by the IDE."
                },
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ ->
                HarnessAuth.signOut(context, provider)
                snack("Signed out.")
                refreshStatus()
            }
            .show()
    }

    private fun showAuthDialog(provider: Provider) {
        HarnessAuth.beginLogin(context, provider)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_harness_auth, null)
        val statusView = dialogView.findViewById<TextView>(R.id.authStatusText)
        val urlView = dialogView.findViewById<TextView>(R.id.authUrlText)
        val openUrlButton = dialogView.findViewById<MaterialButton>(R.id.authOpenUrlButton)
        val codeEdit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.authCodeEdit)

        val title = if (provider == Provider.CLAUDE) "Connect Claude Code" else "Connect Antigravity (Google)"
        var currentUrl: String? = null

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> HarnessAuth.cancel() }
            .setPositiveButton("Submit", null)
            .create()
        dialog.show()

        openUrlButton.setOnClickListener {
            currentUrl?.let { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { snack("No browser available to open the URL.") }
            }
        }

        // Keep Submit enabled only while a session is awaiting input; handle clicks manually
        // so the dialog stays open on failure.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val text = codeEdit.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                snack("Paste the code or token first.")
            } else {
                HarnessAuth.submitCode(text)
            }
        }

        lifecycleOwner.lifecycleScope.launch {
            HarnessAuth.state.collect { state ->
                if (state == null || state.provider != provider) return@collect
                when (state.status) {
                    HarnessAuth.Status.STARTING -> {
                        statusView.text = state.message ?: "Starting…"
                    }
                    HarnessAuth.Status.AWAITING_CODE -> {
                        statusView.text = state.message ?: "Open the URL, then paste the code."
                        if (state.url != null) {
                            currentUrl = state.url
                            urlView.text = state.url
                            urlView.visibility = View.VISIBLE
                            openUrlButton.visibility = View.VISIBLE
                        }
                    }
                    HarnessAuth.Status.COMPLETING -> {
                        statusView.text = state.message ?: "Code submitted — waiting for the CLI…"
                    }
                    HarnessAuth.Status.CONNECTED -> {
                        statusView.text = state.message ?: "Connected."
                        snack(if (provider == Provider.CLAUDE) "Claude connected." else "Google account connected.")
                        dialog.dismiss()
                        HarnessAuth.dismiss()
                        refreshStatus()
                    }
                    HarnessAuth.Status.ERROR -> {
                        statusView.text = state.message ?: "Something went wrong."
                    }
                    HarnessAuth.Status.IDLE -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------ logs

    private fun showLogViewer() {
        val scroll = ScrollView(context)
        val text = TextView(context).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 24)
            setText("Loading…")
        }
        scroll.addView(text)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.55f).toInt(),
        )
        lifecycleOwner.lifecycleScope.launch {
            val tail = withContext(Dispatchers.IO) { HarnessLog.tail(context) }
            text.text = tail
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Harness logs")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setPositiveButton("Export") { _, _ -> exportLogs() }
            .show()
    }

    private fun exportLogs() {
        lifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { HarnessLog.exportFile(context) }
            if (file != null) {
                runCatching { IntentUtils.shareFile(context, file, "text/plain") }
                    .onFailure { snack("Unable to share the log export.") }
            } else {
                snack("Unable to build the log export.")
            }
        }
    }

    private fun confirmClearLogs() {
        MaterialAlertDialogBuilder(context)
            .setTitle("Clear harness logs?")
            .setMessage("All harness log files will be deleted from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                HarnessLog.clear(context)
                snack("Harness logs cleared.")
            }
            .show()
    }

    private fun confirmResetInstallation() {
        MaterialAlertDialogBuilder(context)
            .setTitle("Reset harness installation?")
            .setMessage("The Claude Code and Antigravity CLI binaries will be deleted. Your sign-ins and the harness logs are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { HarnessInstaller.resetInstallation(context) }
                    snack("Harness installation reset.")
                    refreshStatus()
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ status

    private fun buildStatusText(status: HarnessInstaller.HarnessStatus): String = buildString {
        appendLine("Termux bootstrap : ${if (status.bootstrapReady) "ready" else "NOT READY (run the IDE setup first)"}")
        appendLine("glibc layer      : ${if (status.glibcAvailable) "available" else "missing — see Re-check help"}")
        appendLine(
            "Claude Code      : ${
                when {
                    !status.claudeSupported -> "unsupported on ${HarnessEnvironment.primaryAbi()}"
                    status.claudeInstalled -> "installed (${status.claudeVersion ?: "?"})${if (HarnessAuth.isClaudeConnected(context)) " · signed in" else " · not signed in"}"
                    else -> "not installed"
                }
            }",
        )
        appendLine(
            "Antigravity CLI  : ${
                when {
                    !status.agySupported -> "arm64 only"
                    status.agyInstalled -> "installed (${status.agyVersion ?: "?"})${if (HarnessAuth.isAgyConnected(context)) " · signed in" else " · not signed in"}"
                    else -> "not installed"
                }
            }",
        )
        append("Harness          : ${if (HarnessEnvironment.isHarnessEnabled()) "enabled" else "disabled"} · project: ${HarnessEnvironment.ideProjectRoot()?.name ?: "<none — open a project>"}")
    }

    private fun snack(message: String, long: Boolean = false) {
        Snackbar.make(rootView, message, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT).show()
    }

    private val resources get() = rootView.resources

    companion object {
        private const val TAG = "HarnessSettings"
    }
}
