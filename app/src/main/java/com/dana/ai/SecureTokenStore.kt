package com.dana.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protects the optional AI gateway bearer token with a non-exportable Android Keystore key. */
class SecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("dana_gateway_secrets", Context.MODE_PRIVATE)
    private val alias = "dana_ai_gateway_aes_v1"
    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    fun setToken(value: String): Boolean = try {
        if (value.isBlank()) { clear(); true }
        else {
            require(value.length <= 256 && value.none { it.isISOControl() })
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val bytes = Base64.encodeToString(
                cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            prefs.edit().putString("encrypted_token", "$iv:$bytes").commit()
        }
    } catch (_: Exception) { false }

    fun getToken(): String {
        return try {
            val saved = prefs.getString("encrypted_token", null) ?: return ""
            val segments = saved.split(':', limit = 2)
            if (segments.size != 2) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, Base64.decode(segments[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(segments[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun configured(): Boolean = prefs.contains("encrypted_token")
    fun clear() { prefs.edit().remove("encrypted_token").commit() }
}
