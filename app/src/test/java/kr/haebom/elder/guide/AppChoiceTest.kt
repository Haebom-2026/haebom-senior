package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppChoiceTest {
    private val launcher = listOf(
        "카카오톡" to "com.kakao.talk",
        "멜론" to "com.iloen.melon",
        "YouTube" to "com.google.android.youtube",
        "지니뮤직" to "com.ktmusic.geniemusic",
        "카카오 T" to "com.kakao.taxi",
        "네이버 지도" to "com.nhn.android.nmap",
    )

    @Test
    fun onlyInstalledAppsOfThatKindAreOffered() {
        assertEquals(listOf("YouTube", "멜론", "지니뮤직"), AppChoice.installed(AppChoice.Kind.MUSIC, launcher).map { it.label })
        assertEquals(listOf("카카오 T"), AppChoice.installed(AppChoice.Kind.TAXI, launcher).map { it.label })
        assertEquals(listOf("네이버 지도"), AppChoice.installed(AppChoice.Kind.MAP, launcher).map { it.label })
        assertTrue(AppChoice.installed(AppChoice.Kind.MUSIC, listOf("카카오톡" to "com.kakao.talk")).isEmpty())
    }

    @Test
    fun unknownPackagesAreFoundByName() {
        // 패키지를 모르는 음악 앱도 이름으로 찾음
        val apps = AppChoice.installed(AppChoice.Kind.MUSIC, listOf("FLO" to "com.example.flo", "벅스" to "com.example.bugs"))
        assertEquals(listOf("FLO", "벅스"), apps.map { it.label })
    }

    @Test
    fun musicQueryKeepsWhatToPlay() {
        assertEquals("임영웅 노래", AppChoice.musicQuery("임영웅 노래 틀어 줘"))
        assertEquals("트로트", AppChoice.musicQuery("트로트 틀어 줘"))
        assertNull(AppChoice.musicQuery("노래 틀어 줘"))
    }
}
