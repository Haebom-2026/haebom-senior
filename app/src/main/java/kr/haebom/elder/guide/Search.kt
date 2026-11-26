package kr.haebom.elder.guide

import java.net.URLEncoder

/**
 * 검색 말 해석 (안드로이드 없이 단위 테스트).
 * - 인터넷 검색: "네이버에서 ○○ 찾아 줘", "인터넷에 ○○ 검색해 줘", "구글에 ○○"
 * - 주변 장소: "주변 맛집", "근처 우체국 어디야", "가까운 약국 알려 줘", "병원 가고 싶어"
 * - 애매한 말: 어디서 찾을지 고르게 할 후보 순서 ("임영웅 찾아 줘" → 유튜브·인터넷, "약국 찾아 줘" → 지도·인터넷)
 */
internal object Search {
    enum class Where { YOUTUBE, WEB, MAP }
    enum class Engine { NAVER, GOOGLE }

    data class Web(val query: String, val engine: Engine)

    private val WEB_APPS = Regex("""(?i)(인터넷|네이버|구글|google|naver|포털|검색창)\s*(에서|에|으로|로)?""")
    private val FILLERS = setOf("좀", "한번", "검색", "정보", "영상", "동영상")

    private val NEAR = listOf("주변", "근처", "가까운", "가까이", "근방", "동네", "여기서", "이근처")
    private val NEAR_WORDS = Regex("""(우리\s*)?(주변|근처|가까운|가까이|근방|동네|여기서|이\s*근처)\s*(에|에서|의)?""")
    private val WHERE_WORDS = Regex("""\s*(어디\s*(있어|야|예요|에요|있나요|있니|인지|있는지)?|가는\s*길|위치)\s*""")
    /** 가고 싶다는 말 (띄어쓰기 없이): "병원 가고 싶어" 는 장소 찾기 */
    private val GO = listOf("가고싶", "가려고", "가려면", "갈래", "가야돼", "가야해", "가야되", "가는법", "가볼래", "가자")
    /** 검색어에서 빼는 가고 싶다는 말 ("병원에 가고 싶어" → "병원") */
    private val GO_WORDS = Regex("""(에)?\s*(가고\s*싶\S*|가려고(\s*해\S*|\s*하\S*)?|가려면|갈래\S*|가야\s*(돼|해|되)\S*|가는\s*법|가\s*볼래\S*|가자)\s*""")
    private val PLACE = listOf(
        "맛집", "식당", "음식점", "밥집", "병원", "약국", "우체국", "은행", "카페", "커피", "헬스장", "체육관", "편의점",
        "주유소", "마트", "시장", "미용실", "이발소", "경찰서", "파출소", "주민센터", "행정복지센터", "보건소", "치과",
        "한의원", "내과", "정형외과", "안과", "이비인후과", "정류장", "지하철역", "역", "공원", "목욕탕", "사우나",
        "경로당", "노인정", "복지관", "세탁소", "철물점", "꽃집", "빵집",
    )
    private val KNOW_VERBS = listOf("알려", "뭐야", "뭔지", "어때", "얼마")
    private val WATCH_VERBS = listOf("찾아", "검색", "틀어", "보여", "들려", "재생", "보고싶", "듣고싶")
    /** 다른 곳(앱·설정)을 말했으면 검색으로 짐작하지 않음 */
    private val OTHER_PLACE = listOf("에서", "앱", "어플", "설정", "열어", "켜")

    /** "네이버에서 ○○ 찾아 줘" → ○○. 인터넷 검색 말이 아니면 null */
    fun web(command: String): Web? {
        val normalized = GuideTask.normalize(command)
        val mentionsWeb = listOf("인터넷", "네이버", "구글", "google", "naver", "포털", "검색창").any { it in normalized }
        if (!mentionsWeb) return null
        // 찾아 달라는 말이 있어야 검색: "네이버에 로그인하게 도와줘" 는 검색이 아니라 AI 안내
        if (WATCH_VERBS.none { it in normalized } && KNOW_VERBS.none { it in normalized }) return null
        val engine = if ("구글" in normalized || "google" in normalized) Engine.GOOGLE else Engine.NAVER
        return clean(command, WEB_APPS)?.let { Web(it, engine) }
    }

    /** "근처 우체국 어디야" → "우체국", "병원 가고 싶어" → "병원". 주변 장소 말이 아니면 null */
    fun nearby(command: String): String? {
        val normalized = GuideTask.normalize(command)
        val near = NEAR.any { it in normalized }
        val place = PLACE.any { it in normalized }
        val asksWhere = "어디" in normalized
        val wantsToGo = GO.any { it in normalized }
        if (!(near && (place || asksWhere || WATCH_VERBS.any { it in normalized } || "알려" in normalized)) &&
            !(place && (asksWhere || wantsToGo))
        ) {
            return null
        }
        var text = NEAR_WORDS.replace(command, " ")
        text = WHERE_WORDS.replace(text, " ")
        text = GO_WORDS.replace(text, " ")
        return clean(text, null)
    }

    /**
     * 어디서 찾을지 모르는 말의 후보 (가능성 높은 순). 검색 말이 아니면 빈 목록.
     * 예: "임영웅 찾아 줘" → [YOUTUBE, WEB], "약국 찾아 줘" → [MAP, WEB], "오늘 날씨 알려 줘" → [WEB, YOUTUBE]
     */
    fun guess(command: String): Pair<String, List<Where>>? {
        val normalized = GuideTask.normalize(command)
        if (OTHER_PLACE.any { it in normalized }) return null
        val watch = WATCH_VERBS.any { it in normalized }
        val know = KNOW_VERBS.any { it in normalized }
        if (!watch && !know) return null
        val query = clean(command, null) ?: return null
        val order = when {
            PLACE.any { it in normalized } -> listOf(Where.MAP, Where.WEB)
            know && !watch -> listOf(Where.WEB, Where.YOUTUBE)
            else -> listOf(Where.YOUTUBE, Where.WEB)
        }
        return query to order
    }

    fun webUrl(web: Web): String = when (web.engine) {
        Engine.NAVER -> "https://search.naver.com/search.naver?query=" + encode(web.query)
        Engine.GOOGLE -> "https://www.google.com/search?q=" + encode(web.query)
    }

    /** 지도 앱별 "내 주변 검색" 주소 (깔린 앱 순서대로 시도) */
    fun mapUrls(query: String, appPackage: String): List<Pair<String, String?>> = listOf(
        "nmap://search?query=${encode(query)}&appname=$appPackage" to "com.nhn.android.nmap",
        "kakaomap://search?q=${encode(query)}" to "net.daum.android.map",
        "geo:0,0?q=${encode(query)}" to "com.google.android.apps.maps",
        "https://map.naver.com/p/search/${encode(query)}" to null,
    )

    /** 검색어만 남김 (앱 이름·요청하는 말·꾸밈말 제외). 유튜브 검색어와 같은 규칙 */
    private fun clean(command: String, appWords: Regex?): String? {
        val text = if (appWords != null) appWords.replace(command, " ") else command
        return GuideTask.searchWords(text)?.split(" ")?.filter { it !in FILLERS }?.joinToString(" ")?.takeIf { it.isNotBlank() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
