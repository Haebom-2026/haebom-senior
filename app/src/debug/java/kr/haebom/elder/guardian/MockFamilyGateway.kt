package kr.haebom.elder.guardian

import android.os.Handler
import android.os.Looper
import kr.haebom.elder.family.ConnectionCode
import kr.haebom.elder.family.DisconnectResult
import kr.haebom.elder.family.FamilyGateway
import kr.haebom.elder.family.FamilyLink
import kr.haebom.elder.family.NoticeOutcome

/** 고령자 앱 화면이 쓰는 FamilyGateway 를 기기 안 Mock 백엔드로 구현 (debug 전용). */
internal class MockFamilyGateway(
    private val repo: MockGuardianRepository,
    private val elderId: String = MockBackend.ELDER_ID,
) : FamilyGateway {
    private val main = Handler(Looper.getMainLooper())

    override val backendName = "Mock (기기 안 연습)"

    override fun observe(listener: () -> Unit): AutoCloseable = repo.observe(listener)

    override fun links(): List<FamilyLink> = repo.linksForElder(elderId)

    override fun activeCode(): ConnectionCode? = repo.activeCode(elderId)

    override fun issueCode(done: (ConnectionCode?) -> Unit) = answer(done) { repo.issueCode(elderId) }

    override fun renameGuardian(guardianId: String, label: String, done: (Boolean) -> Unit) =
        answer(done) { repo.renameGuardian(elderId, guardianId, label) }

    override fun disconnect(guardianId: String, releaseCode: String, done: (DisconnectResult) -> Unit) =
        answer(done) { repo.disconnectByElder(elderId, guardianId, releaseCode) }

    override fun reportSensitiveEntry(
        appName: String,
        packageName: String,
        category: String,
        done: (NoticeOutcome) -> Unit,
    ) = answer(done) { repo.reportSensitiveEntry(elderId, appName, packageName, category) }

    /** 서버처럼 결과를 다음 메인 루프에서 돌려줌 (화면 코드가 비동기 흐름을 그대로 쓰게) */
    private fun <T> answer(done: (T) -> Unit, work: () -> T) {
        main.post { done(work()) }
    }
}

/** debug 빌드 전체가 같이 쓰는 Mock 백엔드 하나 (고령자 화면과 보호자 흉내 화면이 같은 데이터를 봄) */
internal object MockBackend {
    const val ELDER_ID = "mock-elder"
    val repo = MockGuardianRepository()
}
