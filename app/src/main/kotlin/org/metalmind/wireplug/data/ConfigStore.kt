package org.metalmind.wireplug.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the configuration. Secrets (the private key, preshared key and
 * proxy password) are encrypted with an AES key held in the Android
 * Keystore (S1); the rest is plain JSON in private SharedPreferences.
 * Backups and device transfers are disabled in the manifest.
 */
class ConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wireplug_config", Context.MODE_PRIVATE)
    private val crypto = SecretBox()

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    val current: AppConfig get() = _config.value

    @Synchronized
    fun update(transform: (AppConfig) -> AppConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        save(next)
        _config.value = next
    }

    private fun load(): AppConfig {
        val secrets = prefs.getString("secrets", null)?.let { enc ->
            runCatching { JSONObject(crypto.decrypt(enc)) }.getOrNull()
        } ?: JSONObject()
        val tunnel = TunnelSettings.fromJson(
            prefs.getString("tunnel", null)?.let { JSONObject(it) },
            privateKey = secrets.optString("privateKey"),
            presharedKey = secrets.optString("presharedKey"),
        )
        val home = HomeSettings.fromJson(prefs.getString("home", null)?.let { JSONObject(it) })
        val forwards = prefs.getString("forwards", null)?.let { s ->
            val a = JSONArray(s)
            (0 until a.length()).map { Forward.fromJson(a.getJSONObject(it)) }
        } ?: emptyList()
        val proxyJson = prefs.getString("proxy", null)?.let { JSONObject(it) }
        val proxy = if (proxyJson == null) ProxySettings() else ProxySettings(
            enabled = proxyJson.optBoolean("enabled"),
            port = proxyJson.optInt("port", 8888),
            username = proxyJson.optString("username", "wireplug"),
            password = secrets.optString("proxyPassword").ifEmpty { generatePassword() },
        )
        val behaviour = Behaviour(
            mainSwitch = prefs.getBoolean("mainSwitch", false),
            startAtBoot = prefs.getBoolean("startAtBoot", false),
            notificationDetail = prefs.getBoolean("notificationDetail", true),
        )
        return AppConfig(tunnel, home, forwards, proxy, behaviour)
    }

    private fun save(c: AppConfig) {
        val secrets = JSONObject().apply {
            put("privateKey", c.tunnel.privateKey)
            put("presharedKey", c.tunnel.presharedKey)
            put("proxyPassword", c.proxy.password)
        }
        prefs.edit()
            .putString("secrets", crypto.encrypt(secrets.toString()))
            .putString("tunnel", c.tunnel.toPublicJson().toString())
            .putString("home", c.home.toJson().toString())
            .putString("forwards", c.forwardsToJson().toString())
            .putString("proxy", JSONObject().apply {
                put("enabled", c.proxy.enabled)
                put("port", c.proxy.port)
                put("username", c.proxy.username)
            }.toString())
            .putBoolean("mainSwitch", c.behaviour.mainSwitch)
            .putBoolean("startAtBoot", c.behaviour.startAtBoot)
            .putBoolean("notificationDetail", c.behaviour.notificationDetail)
            .commit()
    }
}

/** AES-256-GCM with a non-exportable key in the Android Keystore. */
private class SecretBox {
    private val alias = "wireplug-config"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}
