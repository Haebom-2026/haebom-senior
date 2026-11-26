package kr.haebom.elder.dev

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** 디버그 뷰어 전송 켜기/끄기와 마지막 전송 결과 (debug 빌드 전용). */
class DevViewerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = DevViewerInitializer.client
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        content.addView(TextView(this).apply {
            text = "디버그 뷰어 (dev 전용)"
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            textSize = 16f
            text = "켜면 접근성으로 읽은 화면 정보(글자·버튼 위치)를 dev 서버로 보내 웹 뷰어에서 볼 수 있어요.\n" +
                "· 비밀번호·인증 입력 칸이 있는 화면, 금융 앱 화면은 보내지 않아요.\n" +
                "· 입력 칸에 적힌 글자와 스크린샷은 보내지 않아요.\n" +
                "· 뷰어: ${DevViewerClient.VIEWER_URL}"
        })
        content.addView(Switch(this).apply {
            text = "디버그 뷰어로 화면 보내기"
            textSize = 18f
            isChecked = client?.enabled == true
            isEnabled = client != null
            setOnCheckedChangeListener { _, on -> client?.enabled = on }
        })
        status = TextView(this).apply { textSize = 15f }
        content.addView(status)
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun render() {
        val client = DevViewerInitializer.client
        status.text = if (client == null) {
            "전송기를 찾지 못했어요."
        } else {
            "기기 ID: ${client.deviceId}\n" +
                "서버: ${DevViewerClient.API_BASE}\n" +
                "화면 안내 서비스: ${if (client.serviceConnected) "켜짐" else "꺼짐 (접근성에서 켜야 보내요)"}\n" +
                "heartbeat: ${client.lastHeartbeat}\n" +
                "화면: ${client.lastScreen}"
        }
    }
}
