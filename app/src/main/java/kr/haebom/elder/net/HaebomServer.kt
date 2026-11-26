package kr.haebom.elder.net

import android.content.Context
import kr.haebom.elder.BuildConfig
import kr.haebom.elder.family.Family
import kr.haebom.elder.family.ServerFamilyGateway

/**
 * 해봄 서버 연결 한 곳. [BuildConfig.API_BASE] 가 비어 있으면(release, 서버 주소 미정) 아무것도 켜지 않음.
 * - [api]: 서버 호출 (보호자 연동, 화면 안내 AI #16)
 * - [Family.gateway]: 보호자 연동 (S1)
 */
internal object HaebomServer {
    @Volatile
    var api: HaebomApi? = null
        private set

    fun install(context: Context) {
        if (api != null || BuildConfig.API_BASE.isBlank()) return
        val app = context.applicationContext
        val server = HaebomApi(app, BuildConfig.API_BASE)
        api = server
        Family.gateway = ServerFamilyGateway(app, server).also { it.start() }
    }
}
