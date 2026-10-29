package com.huanban.ar

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class ChatEngine(val context: Context) {
    private val prefs = context.getSharedPreferences("settings", 0)
    private val file = File(context.filesDir, "chat.json")

    private fun key(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey("huanban_api", null)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "huanban_api",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    fun saveKey(value: String) {
        if (value.isBlank()) {
            prefs.edit().remove("apiKey").apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        prefs
            .edit()
            .putString(
                "apiKey",
                Base64.getEncoder().encodeToString(c.iv + c.doFinal(value.toByteArray())),
            )
            .apply()
    }

    fun readKey(): String {
        val v = prefs.getString("apiKey", null) ?: return ""
        val b = Base64.getDecoder().decode(v)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return String(c.doFinal(b.copyOfRange(12, b.size)))
    }

    @Synchronized
    fun history(): JSONArray =
        try {
            if (file.exists()) JSONArray(file.readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }

    @Synchronized
    fun add(who: String, content: String) {
        val old = history()
        val a = JSONArray()
        for (i in maxOf(0, old.length() - 99) until old.length()) a.put(old.getJSONObject(i))
        a.put(JSONObject().put("role", who).put("content", content.take(8000)))
        val tmp = File(context.filesDir, "chat.tmp")
        tmp.writeText(a.toString())
        check(tmp.renameTo(file))
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    fun respond(input: String): String {
        val base = prefs.getString("apiBase", "")?.trim().orEmpty()
        val model = prefs.getString("apiModel", "").orEmpty()
        val personality = prefs.getString("personality", "温和、简洁、关心学习与生活").orEmpty()
        if (base.isBlank() || model.isBlank())
            return "【本地规则】" +
                when {
                    input.contains("你好") -> "你好，我在这里。今天想一起做点什么？"
                    input.contains("累") || input.contains("休息") -> "那就先休息一会儿，喝点水，再慢慢来。"
                    input.contains("跳舞") -> "可以到舞蹈模式选择轻舞或踏步，我陪你活动一下。"
                    input.contains("学习") -> "先选一个小目标吧，完成后再看下一步。"
                    else ->
                        if (personality.contains("活泼")) "收到！可以摸摸我、换个动作，或者说说你现在的心情。"
                        else "我听到了。现在是本地规则互动；可在设置中配置 AI 接口，获得开放式对话。"
                }
        val url = URL(base.trimEnd('/') + "/chat/completions")
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) {
            "AI 地址必须使用 HTTPS"
        }
        val k = readKey()
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (k.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $k")
            val messages =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "你是虚拟角色幻伴。请用简体中文自然交流。性格：$personality")
                    )
            val h = history()
            for (i in maxOf(0, h.length() - 12) until h.length()) {
                val m = h.getJSONObject(i)
                messages.put(
                    JSONObject()
                        .put("role", m.getString("role"))
                        .put("content", m.getString("content"))
                )
            }
            conn.outputStream.use {
                it.write(
                    JSONObject()
                        .put("model", model)
                        .put("messages", messages)
                        .put("stream", false)
                        .toString()
                        .toByteArray()
                )
            }
            val code = conn.responseCode
            require(code in 200..299) { "AI 服务返回 HTTP $code，请检查接口配置" }
            val bytes =
                conn.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(out.size() + count <= 262144) { "AI 响应过大" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
            return "【AI 接口】" +
                JSONObject(String(bytes))
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .take(8000)
        } finally {
            conn.disconnect()
        }
    }
}
