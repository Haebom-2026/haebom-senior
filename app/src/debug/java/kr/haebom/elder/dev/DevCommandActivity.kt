package kr.haebom.elder.dev

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kr.haebom.elder.GuideInputActivity

/**
 * (debug 빌드 전용) adb 로 "들은 말"을 말하기 창에 넘겨 음성 없이 시험하는 진입점.
 * adb shell 의 한국어 입력이 안 돼서 만듦. DUMP 권한(셸만 가짐)으로 잠가 다른 앱은 부를 수 없음.
 *
 *   adb shell "am start --user 0 -n kr.haebom.elder/.dev.DevCommandActivity --es command '토스로 송금해 줘' --es foreground com.google.android.youtube"
 *   --ez inplace true 면 말하기 창 대신 화면 위 말하기로 들은 것처럼 처리
 */
class DevCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent.getStringExtra("command")
        val inPlace = intent.getBooleanExtra("inplace", false)
        val hook = kr.haebom.elder.guide.SpokenCommandHook.handler
        if (!command.isNullOrBlank() && inPlace && hook != null) {
            // 화면 위 말하기로 들은 것처럼 (접근성 서비스가 처리)
            hook(command)
        } else if (!command.isNullOrBlank()) {
            startActivity(
                GuideInputActivity.intent(this, foregroundPackage = intent.getStringExtra("foreground"), command = command)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}
