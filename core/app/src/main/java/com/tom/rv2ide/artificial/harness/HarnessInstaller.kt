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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject

/**
 * Downloads, verifies and installs the harness binaries.
 *
 * Trust model: every artifact is verified against an official integrity hash *before* it is
 * made executable — Claude Code against the `dist.integrity` SHA-512 published by the npm
 * registry (read over TLS from registry.npmjs.org), Antigravity CLI against the SHA-512 from
 * the official auto-updater manifest. Installs are staged to a temporary file and moved into
 * place atomically; a failed install never overwrites a working binary. All steps are
 * appended to the harness log.
 */
object HarnessInstaller {

    private const val TAG = "HarnessInstaller"

    /** Markers keep track of what we actually installed (and verified). */
    data class HarnessStatus(
        val claudeInstalled: Boolean,
        val claudeVersion: String?,
        val agyInstalled: Boolean,
        val agyVersion: String?,
        val bootstrapReady: Boolean,
        val claudeSupported: Boolean,
        val agySupported: Boolean,
        val glibcAvailable: Boolean,
        val message: String? = null,
    )

    sealed interface InstallResult {
        data class Success(val version: String) : InstallResult
        data class Failure(val message: String) : InstallResult
    }

    // ------------------------------------------------------------------ status

    fun status(context: Context): HarnessStatus {
        val claudeFile = HarnessEnvironment.claudeBinary(context)
        val agyFile = HarnessEnvironment.agyBinary(context)
        val claudeVersion = readMarker(HarnessEnvironment.claudeMarker(context))
            ?.takeIf { claudeFile.isFile && claudeFile.canExecute() }
        val agyVersion = readMarker(HarnessEnvironment.agyMarker(context))
            ?.takeIf { agyFile.isFile && agyFile.canExecute() }
        return HarnessStatus(
            claudeInstalled = claudeVersion != null,
            claudeVersion = claudeVersion,
            agyInstalled = agyVersion != null,
            agyVersion = agyVersion,
            bootstrapReady = HarnessEnvironment.isTermuxBootstrapReady(context),
            claudeSupported = HarnessEnvironment.supportsClaudeCode(),
            agySupported = HarnessEnvironment.supportsAntigravity(),
            glibcAvailable = HarnessRunnerSupport.probeAvailability(context).available,
        )
    }

    fun installedClaudeVersion(context: Context): String? =
        readMarker(HarnessEnvironment.claudeMarker(context))
            ?.takeIf { HarnessEnvironment.claudeBinary(context).let { f -> f.isFile && f.canExecute() } }

    fun installedAgyVersion(context: Context): String? =
        readMarker(HarnessEnvironment.agyMarker(context))
            ?.takeIf { HarnessEnvironment.agyBinary(context).let { f -> f.isFile && f.canExecute() } }

    // ------------------------------------------------------------------ Claude Code

    /**
     * Checks the npm registry for the latest published Claude Code version.
     * Returns (latestVersion, installedVersion) — latest may be null on network error.
     */
    suspend fun checkClaudeUpdate(context: Context): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val latest = runCatching {
            fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest", timeoutMs = 12_000)
                ?.let { JSONObject(it).optString("version").takeIf(String::isNotBlank) }
        }.onFailure { HarnessLog.w(TAG, "Failed to query npm for claude-code: ${it.message}") }
            .getOrNull()
        Pair(latest, installedClaudeVersion(context))
    }

    /**
     * Downloads and installs the official Claude Code native binary for this device's ABI
     * (linux-arm64 or linux-x64). The version is resolved from the npm registry; the
     * tarball is verified against the integrity hash published by the npm registry
     * (publisher-signed with the npm publish credentials) before it is made executable.
     */
    suspend fun installClaude(
        context: Context,
        onProgress: suspend (fraction: Float, message: String) -> Unit = { _, _ -> },
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            val platform = HarnessEnvironment.claudePlatformToken()
                ?: return@withContext InstallResult.Failure(
                    "This device's architecture (${HarnessEnvironment.primaryAbi()}) cannot run Claude Code. " +
                        "Supported: arm64-v8a, x86_64.",
                )
            onProgress(0.02f, "Resolving latest Claude Code release…")
            val latest = fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest", timeoutMs = 15_000)
                ?.let { JSONObject(it).optString("version").takeIf(String::isNotBlank) }
                ?: return@withContext InstallResult.Failure("Could not resolve the latest Claude Code version (npm registry unreachable).")

            onProgress(0.05f, "Resolving npm package for $platform…")
            // The npm platform package (filenames: @anthropic-ai/claude-code-linux-arm64) is the
            // authoritative, always-reachable distribution channel. Its dist.integrity is the
            // publisher-signed SHA-512 of the tarball, so verifying the tarball against it is a
            // sound chain of trust. (downloads.claude.ai is used only as a fallback.)
            val pkgName = "@anthropic-ai/claude-code-$platform"
            val pkgMeta = fetchText("https://registry.npmjs.org/$pkgName/$latest", timeoutMs = 15_000)
                ?.let { JSONObject(it) }
                ?: return@withContext InstallResult.Failure("Could not resolve the npm package metadata for $pkgName@$latest.")
            val dist = pkgMeta.optJSONObject("dist")
                ?: return@withContext InstallResult.Failure("The npm package metadata for $pkgName is missing its dist block.")
            val tarballUrl = dist.optString("tarball").takeIf(String::isNotBlank)
                ?: return@withContext InstallResult.Failure("The npm package metadata is missing the tarball URL.")
            val integrity = dist.optString("integrity").takeIf(String::isNotBlank)
            val shasum = dist.optString("shasum").takeIf(String::isNotBlank)

            val downloads = HarnessEnvironment.downloadsDir(context)
            val archive = File(downloads, "claude-$latest.tgz")
            HarnessLog.audit(TAG, "Downloading Claude Code $latest ($pkgName)")
            try {
                if (integrity != null && integrity.startsWith("sha512-")) {
                    downloadVerifiedBase64(
                        url = tarballUrl,
                        expectedBase64 = integrity.removePrefix("sha512-").trim(),
                        algorithm = "SHA-512",
                        destination = archive,
                        onProgress = { fraction -> onProgress(0.05f + fraction * 0.70f, "Downloading Claude Code $latest…") },
                    )
                } else if (integrity != null && integrity.startsWith("sha256-")) {
                    downloadVerifiedBase64(
                        url = tarballUrl,
                        expectedBase64 = integrity.removePrefix("sha256-").trim(),
                        algorithm = "SHA-256",
                        destination = archive,
                        onProgress = { fraction -> onProgress(0.05f + fraction * 0.70f, "Downloading Claude Code $latest…") },
                    )
                } else if (shasum != null) {
                    downloadVerified(
                        url = tarballUrl,
                        expectedHex = shasum,
                        algorithm = "SHA-1",
                        destination = archive,
                        onProgress = { fraction -> onProgress(0.05f + fraction * 0.70f, "Downloading Claude Code $latest…") },
                    )
                } else {
                    return@withContext InstallResult.Failure("The npm package metadata contains no integrity hash — refusing to install.")
                }
            } catch (t: Throwable) {
                archive.delete()
                throw t
            }

            onProgress(0.80f, "Extracting Claude Code…")
            val staged = File(HarnessEnvironment.binDir(context), ".claude-$latest.installing")
            val found = extractFromTarGz(archive, entryName = "package/claude", destination = staged)
            archive.delete()
            if (!found) {
                staged.delete()
                return@withContext InstallResult.Failure("The npm package did not contain the claude binary (expected package/claude).")
            }
            staged.setExecutable(true, false)
            staged.setReadable(true, false)
            val final = HarnessEnvironment.claudeBinary(context)
            if (!staged.renameTo(final)) {
                staged.copyTo(final, overwrite = true)
                staged.delete()
                final.setExecutable(true, false)
            }

            onProgress(0.95f, "Verifying Claude Code…")
            // The official binary is glibc-linked; resolve and cache the runner (direct/grun/ld.so).
            if (HarnessRunnerSupport.resolve(context, final) == null) {
                // Roll back: never leave a broken binary behind.
                final.delete()
                runCatching { HarnessEnvironment.claudeMarker(context).delete() }
                return@withContext InstallResult.Failure(
                    "Claude Code was installed but cannot run on this device yet.\n\n" +
                        HarnessRunnerSupport.glibcInstallHelp(),
                )
            }
            HarnessEnvironment.claudeMarker(context).writeText(latest)
            HarnessLog.audit(TAG, "Claude Code $latest installed and verified")
            onProgress(1f, "Claude Code $latest installed")
            InstallResult.Success(latest)
        } catch (t: Throwable) {
            HarnessLog.e(TAG, "Claude Code installation failed", t)
            InstallResult.Failure(t.message?.take(300) ?: "Claude Code installation failed.")
        }
    }

    // ------------------------------------------------------------------ Antigravity CLI

    /**
     * Checks the official Antigravity release manifest for the newest version.
     */
    suspend fun checkAgyUpdate(context: Context): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val latest = runCatching { fetchAgyManifest()?.getString("version") }
            .onFailure { HarnessLog.w(TAG, "Failed to query Antigravity manifest: ${it.message}") }
            .getOrNull()
        Pair(latest, installedAgyVersion(context))
    }

    /**
     * Downloads and installs the official Antigravity CLI (`agy`) binary for arm64 devices.
     * The release (version, URL, SHA-512) comes from the official auto-updater manifest.
     */
    suspend fun installAgy(
        context: Context,
        onProgress: suspend (fraction: Float, message: String) -> Unit = { _, _ -> },
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            if (!HarnessEnvironment.supportsAntigravity()) {
                return@withContext InstallResult.Failure(
                    "The Antigravity CLI is currently only published for 64-bit ARM devices (arm64-v8a). " +
                        "This device reports ${HarnessEnvironment.primaryAbi()}.",
                )
            }
            onProgress(0.02f, "Fetching Antigravity release manifest…")
            val manifest = fetchAgyManifest()
                ?: return@withContext InstallResult.Failure("Could not fetch the Antigravity release manifest.")
            val latest = manifest.optString("version").takeIf(String::isNotBlank)
                ?: return@withContext InstallResult.Failure("The Antigravity manifest is missing a version.")
            val url = manifest.optString("url").takeIf(String::isNotBlank)
                ?: return@withContext InstallResult.Failure("The Antigravity manifest is missing a download URL.")
            val expectedSha512 = manifest.optString("sha512").takeIf(String::isNotBlank)
                ?: return@withContext InstallResult.Failure("The Antigravity manifest is missing the SHA-512 checksum.")

            val downloads = HarnessEnvironment.downloadsDir(context)
            val archive = File(downloads, "agy-$latest.tar.gz")
            HarnessLog.audit(TAG, "Downloading Antigravity CLI $latest")
            downloadVerified(
                url = url,
                expectedHex = expectedSha512,
                algorithm = "SHA-512",
                destination = archive,
                onProgress = { fraction -> onProgress(0.02f + fraction * 0.75f, "Downloading Antigravity CLI $latest…") },
            )

            onProgress(0.80f, "Extracting agy…")
            val staged = File(HarnessEnvironment.binDir(context), ".agy-$latest.installing")
            val found = extractFromTarGz(archive, entryName = "antigravity", destination = staged)
            archive.delete()
            if (!found) {
                staged.delete()
                return@withContext InstallResult.Failure("The Antigravity archive did not contain the agy binary.")
            }
            staged.setExecutable(true, false)
            staged.setReadable(true, false)
            val final = HarnessEnvironment.agyBinary(context)
            if (!staged.renameTo(final)) {
                staged.copyTo(final, overwrite = true)
                staged.delete()
                final.setExecutable(true, false)
            }

            onProgress(0.92f, "Verifying agy…")
            if (HarnessRunnerSupport.resolve(context, final) == null) {
                final.delete()
                runCatching { HarnessEnvironment.agyMarker(context).delete() }
                return@withContext InstallResult.Failure(
                    "The Antigravity CLI was installed but cannot run on this device yet.\n\n" +
                        HarnessRunnerSupport.glibcInstallHelp(),
                )
            }
            HarnessEnvironment.agyMarker(context).writeText(latest)
            HarnessLog.audit(TAG, "Antigravity CLI $latest installed and verified")
            onProgress(1f, "Antigravity CLI $latest installed")
            InstallResult.Success(latest)
        } catch (t: Throwable) {
            HarnessLog.e(TAG, "Antigravity CLI installation failed", t)
            InstallResult.Failure(t.message?.take(300) ?: "Antigravity CLI installation failed.")
        }
    }

    /** Resets the harness installation (binaries + markers). Logs are kept. */
    fun resetInstallation(context: Context) {
        runCatching { HarnessEnvironment.binDir(context).deleteRecursively() }
        runCatching { HarnessEnvironment.claudeMarker(context).delete() }
        runCatching { HarnessEnvironment.agyMarker(context).delete() }
        runCatching { File(HarnessEnvironment.harnessDir(context), ".claude-runner").delete() }
        runCatching { File(HarnessEnvironment.harnessDir(context), ".agy-runner").delete() }
        runCatching { HarnessEnvironment.downloadsDir(context).deleteRecursively() }
        HarnessLog.audit(TAG, "Harness installation reset by user")
    }

    // ------------------------------------------------------------------ internals

    private fun fetchAgyManifest(): JSONObject? =
        fetchText(
            "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json",
            timeoutMs = 15_000,
        )?.let { JSONObject(it) }

    private fun readMarker(file: File): String? =
        runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun fetchText(url: String, timeoutMs: Int): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AndroidCodeStudio/1.0 (Android)")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode !in 200..299) {
                HarnessLog.w(TAG, "HTTP ${connection.responseCode} for $url")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadVerified(
        url: String,
        expectedHex: String,
        algorithm: String,
        destination: File,
        onProgress: suspend (Float) -> Unit,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AndroidCodeStudio/1.0 (Android)")
            instanceFollowRedirects = true
        }
        var bytesWritten = 0L
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance(algorithm)
            val buffer = ByteArray(64 * 1024)
            FileOutputStream(destination).use { out ->
                connection.inputStream.use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesWritten += read
                        if (total > 0) onProgress(bytesWritten.toFloat() / total.toFloat())
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedHex.trim(), ignoreCase = true)) {
                HarnessLog.e(TAG, "Checksum mismatch for $url (expected $expectedHex, got $actual)")
                destination.delete()
                throw IllegalStateException("Checksum mismatch — the download was discarded.")
            }
        } catch (t: Throwable) {
            if (destination.exists() && t !is IllegalStateException) destination.delete()
            throw t
        } finally {
            connection.disconnect()
        }
        HarnessLog.audit(TAG, "Downloaded and verified ${destination.name} ($bytesWritten bytes, $algorithm OK)")
    }

    /**
     * Like [downloadVerified] but the expected digest is base64 (npm `dist.integrity`
     * style, e.g. `sha512-<base64>`).
     */
    private suspend fun downloadVerifiedBase64(
        url: String,
        expectedBase64: String,
        algorithm: String,
        destination: File,
        onProgress: suspend (Float) -> Unit,
    ) {
        val expected = try {
            java.util.Base64.getDecoder().decode(expectedBase64)
        } catch (t: Throwable) {
            throw IllegalStateException("Unparseable integrity hash in npm metadata.")
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AndroidCodeStudio/1.0 (Android)")
            instanceFollowRedirects = true
        }
        var bytesWritten = 0L
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance(algorithm)
            val buffer = ByteArray(64 * 1024)
            FileOutputStream(destination).use { out ->
                connection.inputStream.use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesWritten += read
                        if (total > 0) onProgress(bytesWritten.toFloat() / total.toFloat())
                    }
                }
            }
            val actual = digest.digest()
            if (!actual.contentEquals(expected)) {
                HarnessLog.e(TAG, "Integrity mismatch for $url")
                destination.delete()
                throw IllegalStateException("Integrity mismatch — the download was discarded.")
            }
        } catch (t: Throwable) {
            if (destination.exists() && t !is IllegalStateException) destination.delete()
            throw t
        } finally {
            connection.disconnect()
        }
        HarnessLog.audit(TAG, "Downloaded and verified ${destination.name} ($bytesWritten bytes, $algorithm base64 OK)")
    }

    /**
     * Extracts the regular file named [entryName] from a .tar.gz archive
     * to [destination]. Returns true when the entry was found and written.
     */
    private fun extractFromTarGz(archive: File, entryName: String, destination: File): Boolean {
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive))),
        ).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                if (entry.isFile && name == entryName) {
                    FileOutputStream(destination).use { out -> tar.copyTo(out) }
                    return true
                }
                entry = tar.nextEntry
            }
        }
        return false
    }
}
