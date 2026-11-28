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
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal AES-256-GCM keychain backed by the Android Keystore.
 *
 * Stores harness secrets (e.g. the Claude subscription token produced by
 * `claude setup-token`) encrypted with a hardware-backed key. Ciphertext lives
 * in a private SharedPreferences file; the key itself never leaves the Keystore.
 * This mirrors the security posture of Mobile-Harness without changing how the
 * rest of the IDE stores its (legacy, plain-text) AI keys.
 */
object HarnessKeystore {

    private const val TAG = "HarnessKeystore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "android_code_studio_harness_secret_key_v1"
    private const val PREFS = "harness_secure_prefs"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        HarnessLog.audit(TAG, "Creating new harness keystore key")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /** Encrypt and store [value] under [name]. Existing values are replaced. */
    fun save(context: Context, name: String, value: String): Boolean {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            prefs(context).edit().putString(name, packed).apply()
            HarnessLog.registerSecret(value)
            HarnessLog.audit(TAG, "Secret '$name' stored (encrypted, ${value.length} chars)")
            true
        } catch (t: Throwable) {
            HarnessLog.e(TAG, "Failed to store secret '$name'", t)
            false
        }
    }

    /** Decrypt and return the stored value for [name], or null if missing/corrupt. */
    fun read(context: Context, name: String): String? {
        return try {
            val packed = prefs(context).getString(name, null) ?: return null
            val ivPart = packed.substringBefore(':', missingDelimiterValue = "")
            val bodyPart = packed.substringAfter(':', missingDelimiterValue = "")
            if (ivPart.isBlank() || bodyPart.isBlank()) return null
            val iv = Base64.decode(ivPart, Base64.NO_WRAP)
            val ciphertext = Base64.decode(bodyPart, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8).also { secret ->
                // Keep the redaction list warm across process restarts: anything we hand out
                // must never reach the logs verbatim.
                HarnessLog.registerSecret(secret)
            }
        } catch (t: Throwable) {
            HarnessLog.e(TAG, "Failed to read secret '$name'", t)
            null
        }
    }

    fun has(context: Context, name: String): Boolean = prefs(context).contains(name)

    fun clear(context: Context, name: String) {
        prefs(context).edit().remove(name).apply()
        HarnessLog.audit(TAG, "Secret '$name' deleted")
    }
}
