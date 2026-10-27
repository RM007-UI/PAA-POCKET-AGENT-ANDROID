package com.rm007.pocketagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts API credentials at rest with an AES-GCM key protected by Android Keystore. */
class SecurePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("pocket_agent_settings", Context.MODE_PRIVATE)

    fun endpoint(): String = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
    fun model(): String = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun apiKey(): String = readSecret(KEY_API)
    fun githubToken(): String = readSecret(KEY_GITHUB_TOKEN)
    fun githubRepo(): String = prefs.getString(KEY_GITHUB_REPO, "") ?: ""
    fun githubBranch(): String = prefs.getString(KEY_GITHUB_BRANCH, "main") ?: "main"
    fun hasGithubToken(): Boolean = githubToken().isNotBlank()

    fun saveProvider(endpoint: String, model: String) {
        prefs.edit().putString(KEY_ENDPOINT, endpoint.trim())
            .putString(KEY_MODEL, model.trim()).apply()
    }

    fun saveApiKey(value: String) = saveSecret(KEY_API, value)

    fun saveGithubSettings(repo: String, branch: String, token: String) {
        prefs.edit()
            .putString(KEY_GITHUB_REPO, repo.trim())
            .putString(KEY_GITHUB_BRANCH, branch.trim().ifBlank { "main" })
            .apply()
        saveSecret(KEY_GITHUB_TOKEN, token)
    }

    private fun saveSecret(prefKey: String, value: String) {
        if (value.isBlank()) return
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        prefs.edit().putString(prefKey, Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    private fun readSecret(prefKey: String): String {
        val encoded = prefs.getString(prefKey, null) ?: return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return ""
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL = "gpt-4.1-mini"
        private const val KEY_ALIAS = "pocket_agent_secrets_v2"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_API = "api_key_encrypted"
        private const val KEY_GITHUB_REPO = "github_repo"
        private const val KEY_GITHUB_BRANCH = "github_branch"
        private const val KEY_GITHUB_TOKEN = "github_token_encrypted"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
    }
}
