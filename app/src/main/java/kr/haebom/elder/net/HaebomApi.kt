package kr.haebom.elder.net

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 해봄 서버 호출 (서버 계약서 docs/api/s1-users-links.md 1장 공통 규칙).
 * - Authorization: Bearer <Firebase 익명 로그인 ID 토큰>
 * - 401 → 토큰을 새로 받아 1번만 다시
 * - 403 profile-required → #2 프로필 등록 후 1번만 다시
 * - 연결 4초, 응답 9초
 * 네트워크를 쓰므로 반드시 백그라운드 스레드에서 부릅니다.
 */
internal class HaebomApi(context: Context, private val base: String) {
    private val app = context.applicationContext

    data class Reply(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
        /** 오류 본문의 error.reason (401 은 형식이 달라 null) */
        val reason: String?
            get() = runCatching { JSONObject(body).getJSONObject("error").getString("reason") }.getOrNull()
        fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
    }

    val configured: Boolean get() = base.isNotBlank()

    fun call(method: String, path: String, body: String? = null, idempotencyKey: String? = null): Reply {
        var reply = send(method, path, body, idempotencyKey, refreshToken = false)
        if (reply.code == 401) reply = send(method, path, body, idempotencyKey, refreshToken = true)
        if (reply.code == 403 && reply.reason == "profile-required" && ensureProfile()) {
            reply = send(method, path, body, idempotencyKey, refreshToken = false)
        }
        return reply
    }

    /** #2: 고령자로 등록. 이미 등록돼 있으면 서버가 그대로 돌려주므로 여러 번 불러도 됨 */
    fun ensureProfile(name: String = ""): Boolean {
        val body = JSONObject().put("role", "elder").put("name", name).toString()
        val reply = send("POST", "/v1/users/me", body, null, refreshToken = false)
        Log.d(TAG, "프로필 등록 → ${reply.code}")
        return reply.ok
    }

    private fun send(method: String, path: String, body: String?, idempotencyKey: String?, refreshToken: Boolean): Reply {
        if (!configured) return Reply(-1, "server-not-configured")
        val token = idToken(refreshToken) ?: return Reply(-1, "no-auth")
        val connection = (URL(base + path).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 4_000
            connection.readTimeout = 9_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            Reply(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            Log.w(TAG, "$method $path 실패: ${e.javaClass.simpleName}")
            Reply(-1, e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /** 지금 로그인한 익명 사용자의 uid (없으면 로그인) */
    fun uid(): String? = user()?.uid

    private fun user() = try {
        if (FirebaseApp.getApps(app).isEmpty()) {
            null
        } else {
            val auth = FirebaseAuth.getInstance()
            auth.currentUser ?: Tasks.await(auth.signInAnonymously(), 10, TimeUnit.SECONDS).user
        }
    } catch (e: Exception) {
        Log.w(TAG, "익명 로그인 실패: ${e.javaClass.simpleName}")
        null
    }

    private fun idToken(refresh: Boolean): String? = try {
        user()?.let { Tasks.await(it.getIdToken(refresh), 10, TimeUnit.SECONDS).token }
    } catch (e: Exception) {
        Log.w(TAG, "ID 토큰 실패: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        const val TAG = "HaebomApi"
    }
}
