package kr.haebom.elder.dev

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kr.haebom.elder.guide.DevScreenSink
import kr.haebom.elder.guide.GuideScreen
import kr.haebom.elder.guide.GuideTask
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 디버그 뷰어 전송 (debug 빌드 전용, 기본 꺼짐).
 * - heartbeat: 5초마다, screens: 화면 변화가 멈추고 300ms 뒤 마지막 화면 1번
 * - 보내고 끝: 실패해도 재시도하지 않음. 401이면 다음 전송 때 토큰을 새로 받음
 * - 비밀번호·인증 입력 칸이 있는 화면과 금융 앱 화면은 보내지 않음. 입력 칸 글자도 보내지 않음. 스크린샷 없음
 */
internal class DevViewerClient(private val context: Context) : DevScreenSink {
    private val prefs = context.getSharedPreferences("dev_viewer", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var service: AccessibilityService? = null
    @Volatile private var forceRefresh = false

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            main.removeCallbacks(heartbeatLoop)
            if (value && service != null) main.post(heartbeatLoop)
        }

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }

    @Volatile var lastHeartbeat: String = "아직 보내지 않았어요"
        private set
    @Volatile var lastScreen: String = "아직 보내지 않았어요"
        private set
    val serviceConnected: Boolean get() = service != null

    private val heartbeatLoop = object : Runnable {
        override fun run() {
            if (!enabled || service == null) return
            val body = DevScreenPayload.heartbeat(deviceId, Build.MODEL, "Android ${Build.VERSION.RELEASE}", appVersion())
            io.execute { lastHeartbeat = describe(post("/v1/dev/heartbeat", body)) }
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val sendScreen = Runnable {
        val current = service ?: return@Runnable
        if (!enabled) return@Runnable
        val body = capture(current)
        if (body == null) {
            lastScreen = "보내지 않음: 비밀번호·인증·금융 화면이거나 읽을 수 없는 화면 (${now()})"
            return@Runnable
        }
        io.execute { lastScreen = describe(post("/v1/dev/screens", body)) }
    }

    override fun onConnected(service: AccessibilityService) {
        this.service = service
        main.removeCallbacks(heartbeatLoop)
        if (enabled) main.post(heartbeatLoop)
    }

    override fun onScreenChanged(service: AccessibilityService) {
        if (!enabled) return
        this.service = service
        main.removeCallbacks(sendScreen)
        main.postDelayed(sendScreen, DEBOUNCE_MS)
    }

    override fun onDisconnected() {
        service = null
        main.removeCallbacks(heartbeatLoop)
        main.removeCallbacks(sendScreen)
    }

    @Suppress("DEPRECATION") // 노드 recycle 은 API 33 미만에서 필요
    private fun capture(service: AccessibilityService): String? {
        val root = service.rootInActiveWindow ?: return null
        try {
            val pkg = root.packageName?.toString() ?: return null
            if (GuideTask.isFinance(pkg, "")) return null
            val nodes = mutableListOf<DevNode>()
            var visited = 0
            var blocked = false

            fun visit(node: AccessibilityNodeInfo) {
                if (blocked || ++visited > MAX_VISIT || !node.isVisibleToUser) return
                if (node.isPassword || GuideScreen.isProtectedInput(node.inputType) ||
                    GuideScreen.isProtectedId(node.viewIdResourceName)
                ) {
                    blocked = true
                    return
                }
                val useful = node.text != null || node.contentDescription != null ||
                    node.isClickable || node.isEditable || node.isScrollable
                if (useful && nodes.size < DevScreenPayload.MAX_NODES) {
                    val rect = Rect().also(node::getBoundsInScreen)
                    nodes += DevNode(
                        text = node.text?.toString(),
                        description = node.contentDescription?.toString(),
                        className = DevScreenPayload.shortClass(node.className?.toString()),
                        bounds = if (rect.isEmpty) null else intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = node.isChecked,
                        selected = node.isSelected,
                    )
                }
                for (index in 0 until node.childCount) {
                    if (blocked || visited >= MAX_VISIT) break
                    val child = node.getChild(index) ?: continue
                    try {
                        visit(child)
                    } finally {
                        child.recycle()
                    }
                }
            }

            visit(root)
            if (blocked) return null
            val size = screenSize(service)
            return DevScreenPayload.screen(deviceId, now(), pkg, size.x, size.y, nodes)
        } finally {
            root.recycle()
        }
    }

    /** 성공하면 HTTP 코드와 본문 일부, 보내지 못하면 이유 */
    private fun post(path: String, body: String): String {
        val token = idToken() ?: return "보내지 못함: Firebase 로그인 안 됨 (google-services.json 확인)"
        val connection = (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 9_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 401) forceRefresh = true
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val reply = stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(160)
            "$code $reply".trim()
        } catch (e: Exception) {
            "보내지 못함: ${e.javaClass.simpleName}"
        } finally {
            connection.disconnect()
        }
    }

    private fun idToken(): String? = try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            null
        } else {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser ?: Tasks.await(auth.signInAnonymously(), 10, TimeUnit.SECONDS).user
            val refresh = forceRefresh
            forceRefresh = false
            user?.let { Tasks.await(it.getIdToken(refresh), 10, TimeUnit.SECONDS).token }
        }
    } catch (e: Exception) {
        Log.w(TAG, "ID 토큰을 받지 못했어요: ${e.javaClass.simpleName}")
        null
    }

    private fun describe(result: String) = "${now()} · $result"

    private fun now() = Instant.now().toString()

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    @Suppress("DEPRECATION") // 앱 지원 범위(API 26~) 전체에서 실제 화면 크기
    private fun screenSize(service: AccessibilityService): Point =
        Point().also { service.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(it) }

    companion object {
        /** dev 환경 API Gateway. release 빌드에는 이 코드가 없습니다 */
        const val API_BASE = "https://9a2sj8hqlb.execute-api.ap-northeast-2.amazonaws.com"
        const val VIEWER_URL = "https://d3jcc37k40jy8z.cloudfront.net"
        private const val TAG = "HaebomDevViewer"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DEVICE = "device_id"
        private const val HEARTBEAT_MS = 5_000L
        private const val DEBOUNCE_MS = 300L
        private const val MAX_VISIT = 400
    }
}
