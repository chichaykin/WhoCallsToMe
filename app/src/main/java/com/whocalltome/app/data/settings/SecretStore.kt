package com.whocalltome.app.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)

    fun put(key: String, value: String) {
        if (value.isBlank()) {
            remove(key)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = listOf(cipher.iv, ciphertext).joinToString(":") {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
        preferences.edit().putString(key, encoded).apply()
    }

    fun get(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching {
            val parts = encoded.split(":", limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = preferences.contains(key)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val IPQS_KEY = "ipqs_api_key"
        const val TELLOWS_KEY = "tellows_api_key"
        const val PHONEBLOCK_TOKEN = "phoneblock_bearer_token"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "who_call_to_me_api_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
