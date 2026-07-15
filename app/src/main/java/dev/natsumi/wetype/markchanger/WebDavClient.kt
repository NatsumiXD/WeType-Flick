package dev.natsumi.wetype.markchanger

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebDavClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {

    private val remotePath = "/wetype-markchanger/symbols.json"

    data class Config(
        val url: String = "",
        val username: String = "",
        val password: String = "",
        val isPrimary: Boolean = true
    )

    private fun fullUrl(): String {
        val base = serverUrl.trimEnd('/')
        return "$base$remotePath"
    }

    private fun dirUrl(): String {
        val base = serverUrl.trimEnd('/')
        return "$base/wetype-markchanger/"
    }

    private fun authHeader(): String {
        val credentials = "$username:$password"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    private fun disableSslVerification() {
        try {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (_: Exception) {}
    }

    private fun HttpURLConnection.setup() {
        setRequestProperty("Authorization", authHeader())
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connectTimeout = 15_000
        readTimeout = 15_000
    }

    fun testConnection(): Result<String> = runCatching {
        disableSslVerification()
        val conn = URL(dirUrl()).openConnection() as HttpURLConnection
        conn.setup()
        conn.requestMethod = "PROPFIND"
        conn.setFixedLengthStreamingMode(0)
        conn.setRequestProperty("Depth", "0")
        conn.doOutput = true
        conn.connect()
        try {
            val code = conn.responseCode
            if (code in 200..299 || code == 404) {
                "连接成功 (${conn.responseMessage ?: code})"
            } else {
                throw Exception("HTTP $code: ${conn.responseMessage ?: ""}")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun upload(jsonStr: String): Result<String> = runCatching {
        disableSslVerification()
        ensureDirectory()
        val conn = URL(fullUrl()).openConnection() as HttpURLConnection
        conn.setup()
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.connect()
        try {
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(jsonStr) }
            val code = conn.responseCode
            if (code in 200..299) {
                "上传成功"
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw Exception("HTTP $code: $err")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun download(): Result<String> = runCatching {
        disableSslVerification()
        val conn = URL(fullUrl()).openConnection() as HttpURLConnection
        conn.setup()
        conn.requestMethod = "GET"
        conn.connect()
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                val bytes = conn.inputStream.readBytes()
                String(bytes, Charsets.UTF_8)
            } else {
                throw Exception("HTTP $code: 文件不存在或无权限")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun ensureDirectory() {
        try {
            val conn = URL(dirUrl()).openConnection() as HttpURLConnection
            conn.setup()
            conn.requestMethod = "MKCOL"
            conn.connect()
            conn.disconnect()
        } catch (_: Exception) {}
    }

    companion object {
        private const val PREFS_KEY = "webdav_config"

        fun saveConfig(context: android.content.Context, config: Config) {
            val json = org.json.JSONObject().apply {
                put("url", config.url)
                put("username", config.username)
                put("password", config.password)
                put("isPrimary", config.isPrimary)
            }
            context.getSharedPreferences("webdav_config", android.content.Context.MODE_PRIVATE)
                .edit().putString(PREFS_KEY, json.toString()).apply()
        }

        fun loadConfig(context: android.content.Context): Config {
            val str = context.getSharedPreferences("webdav_config", android.content.Context.MODE_PRIVATE)
                .getString(PREFS_KEY, null) ?: return Config()
            return try {
                val json = org.json.JSONObject(str)
                Config(
                    url = json.optString("url", ""),
                    username = json.optString("username", ""),
                    password = json.optString("password", ""),
                    isPrimary = json.optBoolean("isPrimary", true)
                )
            } catch (_: Exception) {
                Config()
            }
        }
    }
}
