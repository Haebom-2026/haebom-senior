package kr.haebom.elder

import android.app.Application
import kr.haebom.elder.net.HaebomServer

/** 앱 시작: 서버 주소가 있으면 서버 연동(보호자 연결·화면 안내 AI)을 켭니다 */
class HaebomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HaebomServer.install(this)
    }
}
