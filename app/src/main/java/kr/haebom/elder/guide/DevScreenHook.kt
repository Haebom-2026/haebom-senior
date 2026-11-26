package kr.haebom.elder.guide

import android.accessibilityservice.AccessibilityService

/**
 * 디버그 뷰어로 화면을 보내는 코드가 붙는 자리.
 * 전송 코드는 debug 소스셋(`kr.haebom.elder.dev`)에만 있고, release 빌드에서는 [sink]가 항상 null이라 아무 일도 하지 않습니다.
 */
internal interface DevScreenSink {
    fun onConnected(service: AccessibilityService)
    fun onScreenChanged(service: AccessibilityService)
    fun onDisconnected()
}

internal object DevScreenHook {
    @Volatile
    var sink: DevScreenSink? = null
}
