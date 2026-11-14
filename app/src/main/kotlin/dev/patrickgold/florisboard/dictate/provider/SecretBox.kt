/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts API keys at rest with an AES-256-GCM key that lives in the Android Keystore.
 *
 * The keyring is stored as one JSON preference. Upstream keeps the keys in it as plain text; here every
 * non-empty key is replaced by `enc1:<base64(iv || ciphertext)>` before it is written and turned back
 * into plain text when it is read, so only [ProviderAccounts.Serializer] ever sees the stored form.
 *
 * The Keystore key never leaves the device. A settings file copied elsewhere (Android backup, a backup
 * export, a new phone) therefore holds keys that cannot be opened there: they read back as empty and the
 * user enters the key again. That is intended.
 *
 * Outside Android (JVM unit tests) there is no Keystore; values pass through unchanged there.
 */
object SecretBox {
    private const val PREFIX = "enc1:"
    private const val KEY_ALIAS = "voicekeyboard_provider_keys_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val isAndroid: Boolean =
        System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
            System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)

    // Avoid a Keystore round trip on every preference write/read: the keyring is re-serialized whenever
    // any account field changes. Plain text -> sealed value, and sealed value -> plain text.
    private val sealedCache = ConcurrentHashMap<String, String>()
    private val openedCache = ConcurrentHashMap<String, String>()

    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

    /** Returns the stored form of [plain]; empty stays empty. */
    fun seal(plain: String): String {
        if (plain.isEmpty() || isSealed(plain) || !isAndroid) return plain
        sealedCache[plain]?.let { return it }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val sealed = PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
            sealedCache[plain] = sealed
            openedCache[sealed] = plain
            sealed
        } catch (e: Exception) {
            // Never include the value in the log.
            flogError { "SecretBox: could not encrypt a provider key (${e.javaClass.simpleName})" }
            plain
        }
    }

    /** Returns the plain text for a stored [value]; plain (legacy) values pass through, unreadable ones are "". */
    fun open(value: String): String {
        if (!isSealed(value)) return value
        openedCache[value]?.let { return it }
        if (!isAndroid) return ""
        return try {
            val raw = Base64.decode(value.substring(PREFIX.length), Base64.NO_WRAP)
            require(raw.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            val plain = String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
            openedCache[value] = plain
            sealedCache[plain] = value
            plain
        } catch (e: Exception) {
            flogError { "SecretBox: a stored provider key could not be decrypted (${e.javaClass.simpleName}); it must be entered again" }
            ""
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
