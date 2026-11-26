package kr.haebom.elder.dev

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import kr.haebom.elder.guardian.DebugFamilyBackends
import kr.haebom.elder.guide.DevScreenHook

/** debug 빌드에서만 앱 시작 시 디버그 뷰어 전송기와 보호자 연동(실제 서버 또는 Mock)을 연결합니다 (데이터는 제공하지 않음). */
internal class DevViewerInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        client = DevViewerClient(app).also { DevScreenHook.sink = it }
        DebugFamilyBackends.install(app)
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        var client: DevViewerClient? = null
            private set
    }
}
