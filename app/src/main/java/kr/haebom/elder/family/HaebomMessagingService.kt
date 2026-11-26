package kr.haebom.elder.family

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * 서버가 보내는 FCM data 메시지 받기. 키 이름은 서버·보호자 앱과 같은 `kind` 형식.
 *   { "kind": "link", "change": "created" | "deleted", "elderId", "guardianId" } → 가족 목록 다시 받기
 * (S3 이후 { "kind": "approval", "id", "status", ... } 도 여기서 받습니다)
 */
class HaebomMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"]
        Log.d(TAG, "FCM 받음: kind=$kind change=${message.data["change"]}")
        when (kind) {
            "link" -> Family.gateway?.refresh()
        }
    }

    override fun onNewToken(token: String) {
        (Family.gateway as? ServerFamilyGateway)?.registerDevice(token)
    }

    private companion object {
        const val TAG = "HaebomFcm"
    }
}
