package kr.haebom.elder.guide

import java.util.Locale

/**
 * 어떤 앱으로 할지 말하지 않은 일 ("노래 틀어 줘", "택시 불러 줘", "길 찾아 줘")의 앱 고르기 (안드로이드 없이 단위 테스트).
 * 폰에 깔린 앱부터 보고: 하나면 그 앱으로 바로, 여럿이면 어르신이 고르게, 없으면 인터넷 검색으로.
 * 앱을 하나로 정해 두지 않음 (멜론을 쓰시는 분도, 지니를 쓰시는 분도 있으므로).
 */
internal object AppChoice {
    enum class Kind { MUSIC, TAXI, MAP }

    data class App(val label: String, val packageName: String)

    private data class Known(val packages: Set<String>, val labelWords: List<String>)

    private val KNOWN = mapOf(
        Kind.MUSIC to Known(
            setOf(
                "com.google.android.youtube", "com.google.android.apps.youtube.music", "com.iloen.melon",
                "com.ktmusic.geniemusic", "skplanet.musicmate", "com.neowiz.android.bugs", "com.spotify.music", "com.naver.vibe",
            ),
            listOf("멜론", "melon", "지니", "genie", "flo", "플로", "벅스", "bugs", "spotify", "스포티파이", "vibe", "바이브", "youtubemusic", "유튜브뮤직"),
        ),
        Kind.TAXI to Known(
            setOf("com.kakao.taxi"),
            listOf("카카오t", "kakaot", "우티", "타다", "tada", "아이엠택시", "i.m"),
        ),
        Kind.MAP to Known(
            setOf("com.nhn.android.nmap", "net.daum.android.map", "com.google.android.apps.maps", "com.skt.tmap.ku", "com.skt.skaf.l001mtm091"),
            listOf("네이버지도", "카카오맵", "티맵", "tmap", "구글지도", "googlemaps"),
        ),
    )

    private val MUSIC_WORDS = listOf("노래", "음악", "트로트", "가요", "뮤직", "곡")
    private val MUSIC_VERBS = listOf("틀어", "들려", "재생", "듣고싶", "들을래", "켜")
    private val TAXI_WORDS = listOf("택시")
    private val MAP_WORDS = listOf("길찾", "길좀찾", "가는길", "길안내", "지도")

    /** 앱 이름을 말했으면 고를 필요 없음 */
    private val NAMED_APPS = listOf(
        "유튜브", "youtube", "멜론", "지니", "플로", "벅스", "스포티파이", "카카오t", "우티", "타다", "네이버지도", "카카오맵", "티맵", "구글지도",
    )

    /** 앱을 고르게 할 일이면 그 종류, 아니면 null */
    fun kindOf(command: String): Kind? {
        val text = norm(command)
        if (NAMED_APPS.any { it in text }) return null
        return when {
            MUSIC_WORDS.any { it in text } && MUSIC_VERBS.any { it in text } -> Kind.MUSIC
            TAXI_WORDS.any { it in text } -> Kind.TAXI
            MAP_WORDS.any { it in text } -> Kind.MAP
            else -> null
        }
    }

    /** 폰에 깔린 앱 [launcher] (이름, 패키지) 중 [kind] 에 맞는 앱. 패키지가 알려진 앱 먼저, 같은 앱은 한 번 */
    fun installed(kind: Kind, launcher: List<Pair<String, String>>): List<App> {
        val known = KNOWN.getValue(kind)
        return launcher
            .filter { (label, pkg) -> pkg in known.packages || known.labelWords.any { it in norm(label) } }
            .distinctBy { it.second }
            .sortedBy { (_, pkg) -> if (pkg in known.packages) known.packages.indexOf(pkg) else known.packages.size }
            .map { (label, pkg) -> App(label, pkg) }
    }

    /** 음악 검색어: "임영웅 노래 틀어 줘" → "임영웅 노래". "노래 틀어 줘" 처럼 무엇인지 없으면 null */
    fun musicQuery(command: String): String? =
        GuideTask.searchWords(command)?.takeUnless { norm(it) in setOf("노래", "음악", "아무노래", "음악좀", "노래좀") }

    private fun norm(value: String): String = value.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
}
