package com.dana.ai

import org.json.JSONObject
import javax.net.ssl.HttpsURLConnection
import java.net.URL

/** Optional HTTPS proxy; never place an AI-provider API key in this Android project. */
class CloudIntentClient(private val endpoint: String, private val gatewayToken: String = "") {
    fun infer(phrase: String): DanaAction? {
        require(isValidEndpoint(endpoint)) { "آدرس HTTPS معتبر نیست." }
        if (phrase.length !in 1..500 || SafetyPolicy.explicitlyNegated(phrase)) return null
        var connection: HttpsURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 5500
            readTimeout = 8500
            instanceFollowRedirects = false
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (gatewayToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $gatewayToken")
            doOutput = true
            }
            val active = requireNotNull(connection)
            // Do not send the contact database, local user profile or conversation history.
            val body = JSONObject().put("language", "fa").put("text", phrase)
                .put("actions", "TIME,BATTERY,OPEN_CAMERA,OPEN_CONTACTS,OPEN_APP,CALL,SMS_DRAFT,FLASH_ON,FLASH_OFF,VOLUME_UP,VOLUME_DOWN")
                .toString().toByteArray(Charsets.UTF_8)
            active.outputStream.use { it.write(body) }
            if (active.responseCode != 200 || active.contentLength > 8192) return null
            // Bound network reads; readText().take() would allocate an unlimited response first.
            val response = active.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(1024)
                val result = StringBuilder()
                while (true) {
                    val size = reader.read(buffer)
                    if (size < 0) break
                    result.append(buffer, 0, size)
                    if (result.length > 8192) return null
                }
                result.toString()
            }
            val decision = JSONObject(response)
            if (decision.optBoolean("needs_clarification", false)) return null
            AiDecisionGate.accept(decision.optString("action"), decision.optString("target"),
                decision.optString("message").takeIf { it.isNotBlank() })
        } catch (_: Exception) { null }
        finally { connection?.disconnect() }
    }

    companion object {
        fun isValidEndpoint(raw: String): Boolean = try {
            val u = URL(raw)
            u.protocol == "https" && u.host.isNotBlank() && u.userInfo == null &&
                u.ref == null && u.query == null && u.host != "localhost" &&
                !u.host.equals("127.0.0.1") && raw.length <= 300
        } catch (_: Exception) { false }
    }
}
