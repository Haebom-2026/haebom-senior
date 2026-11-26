package kr.haebom.elder.guide

/**
 * 화면 위 말하기로 "들은 말"을 넣는 자리. 접근성 서비스가 켜져 있을 때 [handler] 가 있음.
 * debug 빌드의 adb 시험 진입점(DevCommandActivity, --ez inplace true)이 음성 없이 같은 처리를 시험할 때 씀.
 */
internal object SpokenCommandHook {
    @Volatile
    var handler: ((String) -> Unit)? = null
}
