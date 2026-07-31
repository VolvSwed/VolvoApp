package club.volvoswed.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MobileAuthClient(private val baseUrl: String) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun exchangeCode(code: String, callback: (Result<SessionTokens>) -> Unit) {
        post("/mobile/auth/exchange", JSONObject().put("code", code), callback)
    }

    fun refresh(refreshToken: String, callback: (Result<SessionTokens>) -> Unit) {
        post(
            "/mobile/auth/refresh",
            JSONObject().put("refresh_token", refreshToken),
            callback
        )
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun post(
        path: String,
        body: JSONObject,
        callback: (Result<SessionTokens>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val connection = URL("${baseUrl.trimEnd('/')}$path")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 20_000
                    connection.doOutput = true
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { output ->
                        output.write(body.toString().toByteArray(Charsets.UTF_8))
                    }

                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val json = responseBody.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
                    if (status !in 200..299) {
                        throw AuthException(
                            json.optString("error", "Сервер вернул ошибку $status"),
                            status
                        )
                    }

                    SessionTokens(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.getString("refresh_token"),
                        accessExpiresAt = json.getLong("access_expires_at"),
                        refreshExpiresAt = json.getLong("refresh_expires_at")
                    )
                } finally {
                    connection.disconnect()
                }
            }
            mainHandler.post { callback(result) }
        }
    }

    class AuthException(message: String, val status: Int) : Exception(message)
}
