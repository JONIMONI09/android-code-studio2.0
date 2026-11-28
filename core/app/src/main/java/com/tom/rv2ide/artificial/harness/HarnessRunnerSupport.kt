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
import java.util.concurrent.TimeUnit

/**
 * Resolves how harness binaries are executed on this device.
 *
 * Why this exists: the official `claude` and `agy` binaries are glibc-linked ELFs
 * (`PT_INTERP /lib/ld-linux-aarch64.so.1`). The IDE runs them fine once the user
 * installs Termux's glibc compatibility layer (`pkg install glibc-repo && pkg
 * install glibc-runner`), which provides `grun` and the glibc loader under
 * `$PREFIX/glibc`. Without it, direct execution fails with "cannot execute: required
 * file not found". On a fully static future binary the direct path still works, so the
 * resolver probes candidates instead of assuming.
 *
 * Resolution order: cached marker → grun → glibc ld.so → direct. The first candidate
 * that passes `--version` wins and is remembered (per binary) in a marker file.
 */
object HarnessRunnerSupport {

    private const val TAG = "HarnessRunnerSupport"

    enum class Runner(val marker: String) {
        DIRECT("direct"),
        GRUN("grun"),
        LDSO("ldso");

        companion object {
            fun fromMarker(value: String?): Runner? = entries.firstOrNull { it.marker == value?.trim() }
        }
    }

    data class RunnerStatus(
        val grunInstalled: Boolean,
        val glibcInstalled: Boolean,
        val usableDirectly: Boolean,
        val message: String,
    ) {
        /** True when some invocation path is believed to exist (verified per-binary on run). */
        val available: Boolean get() = grunInstalled || glibcInstalled || usableDirectly
    }

    /** Fast, side-effect-free check for the settings screen (no process is spawned). */
    fun probeAvailability(context: Context): RunnerStatus {
        val bin = HarnessEnvironment.termuxBin(context)
        val grun = listOf(File(bin, "grun"), File(bin, "glibc-runner")).any { it.isFile && it.canExecute() }
        val glibc = ldsoPath(context) != null
        val message = when {
            grun || glibc -> "glibc compatibility layer detected"
            else -> "glibc layer not installed yet"
        }
        return RunnerStatus(
            grunInstalled = grun,
            glibcInstalled = glibc,
            usableDirectly = false, // cheap probe cannot guarantee direct execution
            message = message,
        )
    }

    fun glibcLibDir(context: Context): File = File(HarnessEnvironment.termuxPrefix(context), "glibc/lib")

    /** termux-glibc installs the loader under glibc/bin on newer versions and glibc/lib on older ones. */
    private fun ldsoPath(context: Context): File? {
        val glibc = File(HarnessEnvironment.termuxPrefix(context), "glibc")
        return sequenceOf(
            File(glibc, "bin/ld-linux-aarch64.so.1"),
            File(glibc, "lib/ld-linux-aarch64.so.1"),
            File(glibc, "bin/ld-linux-x86-64.so.2"),
            File(glibc, "lib/ld-linux-x86-64.so.2"),
        ).firstOrNull { it.isFile }
    }

    private fun grunPath(context: Context): File? {
        val bin = HarnessEnvironment.termuxBin(context)
        return listOf(File(bin, "grun"), File(bin, "glibc-runner"))
            .firstOrNull { it.isFile && it.canExecute() }
    }

    /** argv prefix for [runner] applied to [binary]. */
    private fun invocation(context: Context, runner: Runner, binary: File): List<String> = when (runner) {
        Runner.DIRECT -> listOf(binary.absolutePath)
        Runner.GRUN -> listOf(grunPath(context)?.absolutePath ?: binary.absolutePath) + binary.absolutePath
        Runner.LDSO -> listOf(ldsoPath(context)?.absolutePath ?: binary.absolutePath, "--library-path", glibcLibDir(context).absolutePath) + binary.absolutePath
    }

    /** Full argv to execute [binary] with [args] through the resolved [runner]. */
    fun wrapCommand(context: Context, runner: Runner, binary: File, args: List<String>): List<String> =
        invocation(context, runner, binary) + args

    private fun markerFile(context: Context, binary: File): File =
        File(HarnessEnvironment.harnessDir(context), ".${binary.name}-runner")

    /** Drop the cached runner markers for the harness binaries (e.g. after the glibc layer was installed). */
    fun clearRunnerCache(context: Context) {
        runCatching { File(HarnessEnvironment.harnessDir(context), ".claude-runner").delete() }
        runCatching { File(HarnessEnvironment.harnessDir(context), ".agy-runner").delete() }
    }

    fun cachedRunner(context: Context, binary: File): Runner? =
        runCatching { Runner.fromMarker(markerFile(context, binary).takeIf { it.isFile }?.readText()) }.getOrNull()

    private fun cacheRunner(context: Context, binary: File, runner: Runner) {
        runCatching { markerFile(context, binary).writeText(runner.marker) }
    }

    /**
     * Resolve the runner that can execute [binary]. Probes candidates by running
     * `--version`; returns null when nothing works. The result is cached on disk.
     */
    fun resolve(context: Context, binary: File): Runner? {
        cachedRunner(context, binary)?.let { return it }
        if (!binary.isFile) return null
        val candidates = buildList {
            if (grunPath(context) != null) add(Runner.GRUN)
            if (ldsoPath(context) != null) add(Runner.LDSO)
            add(Runner.DIRECT)
        }
        for (candidate in candidates) {
            if (probe(context, candidate, binary)) {
                cacheRunner(context, binary, candidate)
                HarnessLog.audit(TAG, "Resolved runner for ${binary.name}: $candidate")
                return candidate
            }
        }
        HarnessLog.e(TAG, "No working runner found for ${binary.name}")
        return null
    }

    /** Probe [candidate] by executing the binary with `--version`. */
    fun probe(context: Context, candidate: Runner, binary: File): Boolean {
        return try {
            val argv = wrapCommand(context, candidate, binary, listOf("--version"))
            val process = ProcessBuilder(argv)
                .redirectErrorStream(true)
                .apply { environment().putAll(HarnessEnvironment.baseEnvironment(context)) }
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(45, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                HarnessLog.w(TAG, "Runner probe timed out for $binary via $candidate")
                false
            } else {
                val ok = process.exitValue() == 0
                HarnessLog.i(TAG, "Runner probe $candidate for ${binary.name}: exit=${process.exitValue()} output=${output.take(120)}")
                ok
            }
        } catch (t: Throwable) {
            HarnessLog.i(TAG, "Runner probe $candidate for ${binary.name} failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Multi-line help shown when no runner can execute the binaries. */
    fun glibcInstallHelp(): String = buildString {
        appendLine("The official harness binaries are glibc-linked and need the glibc compatibility layer (one-time, ~30 MB).")
        appendLine()
        appendLine("In the IDE terminal run:")
        appendLine("  pkg install glibc-repo -y")
        appendLine("  pkg install glibc glibc-runner -y")
        appendLine()
        append("Then come back and tap Re-check.")
    }
}
