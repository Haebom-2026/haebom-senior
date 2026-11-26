package kr.haebom.elder.guardian

import android.content.Context
import kr.haebom.elder.BuildConfig
import kr.haebom.elder.family.Family
import kr.haebom.elder.family.FamilyGateway
import kr.haebom.elder.family.ServerFamilyGateway
import kr.haebom.elder.net.HaebomApi

/** debug 빌드에서 보호자 연동을 실제 서버(dev)로 할지, 기기 안 Mock 으로 할지 고름 (기본: 실제 서버) */
internal object DebugFamilyBackends {
    private const val PREFS = "debug_family"
    private const val KEY_SERVER = "use_server"

    fun useServer(context: Context): Boolean =
        BuildConfig.API_BASE.isNotBlank() &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SERVER, true)

    fun setUseServer(context: Context, server: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SERVER, server).apply()
        install(context)
    }

    fun install(context: Context) {
        val app = context.applicationContext
        Family.gateway = create(app)
    }

    private fun create(context: Context): FamilyGateway =
        if (useServer(context)) {
            ServerFamilyGateway(context, HaebomApi(context, BuildConfig.API_BASE)).also { it.start() }
        } else {
            MockFamilyGateway(MockBackend.repo)
        }
}
