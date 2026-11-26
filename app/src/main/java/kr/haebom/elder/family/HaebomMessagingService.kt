package kr.haebom.elder.family

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kr.haebom.elder.R

/**
 * 서버가 보내는 FCM data 메시지 받기. 키 이름은 서버·보호자 앱과 같은 `kind` 형식.
 *   { "kind": "link", "change": "created" | "deleted", "elderId", "guardianId" } → 가족 목록 다시 받기
 *   { "kind": "challenge", "change": "sent" | "cheered" | "cancelled", "id", "guardianId" } → 도전 목록 다시 받기 (가족의 도전, S4 제안)
 *     cancelled(보호자가 취소)는 목록만 갱신하고 아무 말도 하지 않음
 *   { "kind": "approval", "id", "status", "respondedBy"?, "respondedByLabel"? } → 기다리던 보호자 승인 결과 (S3)
 * 도전 알림은 서버의 화면 알림 한 번뿐: 해봄은 다시 띄우거나 재촉하지 않음 (앱이 켜져 있으면 자막 한 줄)
 */
class HaebomMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"]
        Log.d(TAG, "FCM 받음: kind=$kind change=${message.data["change"]}")
        when (kind) {
            "link" -> Family.gateway?.refresh()
            "approval" -> Approvals.onPush(message.data)
            "challenge" -> Challenges.gateway?.refresh { list ->
                val change = message.data["change"]
                if (list == null || (change != "sent" && change != "cheered")) return@refresh
                val guardian = message.data["guardianId"]
                val label = Family.gateway?.links()?.firstOrNull { it.guardianId == guardian }?.labelForElder ?: getString(R.string.challenge_family)
                val text = getString(if (change == "sent") R.string.challenge_caption_sent else R.string.challenge_caption_cheered, Challenges.subject(label))
                kr.haebom.elder.guide.GuideCaption.post(text, kr.haebom.elder.guide.GuideCaption.Tone.INFO)
            }
        }
    }

    override fun onNewToken(token: String) {
        (Family.gateway as? ServerFamilyGateway)?.registerDevice(token)
    }

    private companion object {
        const val TAG = "HaebomFcm"
    }
}
