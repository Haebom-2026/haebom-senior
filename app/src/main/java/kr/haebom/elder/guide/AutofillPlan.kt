package kr.haebom.elder.guide

/** 입력 칸 아래 "자동완성 입력" 단추에 보여 줄 글자 정하기 */
internal object AutofillPlan {
    const val MAX_CHIPS = 3

    /**
     * - 짚은 요소가 입력 칸이 아니면 없음
     * - 말한 검색어(slots.query)를 아직 안 넣었으면 그것을 맨 앞에
     * - AI 가 suggest 로 준 추천어를 뒤에 (같은 글자는 한 번만, 최대 [MAX_CHIPS]개)
     */
    fun chips(reply: AiReply, target: ScreenNode?, active: GuideSession.Active?): List<String> {
        if (target?.editable != true) return emptyList()
        // 비밀 칸(비밀번호·인증번호·계좌번호 등)에는 해봄이 글자를 넣지 않음: AI 가 넣을 글자를 줘도 버림
        if (target.secret) return emptyList()
        val spoken = (active?.query ?: active?.slots?.get("query"))
            ?.takeIf { active?.queryFilled == false }
        // AI 가 준 넣을 글자(typeText, 없으면 chips)는 그대로: 이름·번호·"화면 자동 꺼짐" 같은 값을 자르지 않음
        val suggested = if (reply.type == AiReply.SUGGEST) listOfNotNull(reply.typeText) + reply.chips else emptyList()
        // 어르신이 말한 검색어는 다듬음 ("임영웅 검색 해줘" → "임영웅").
        // 유튜브 검색 안내에서는 AI 글자도 검색어만 (AI 가 말한 문장 그대로 줄 때가 있어서)
        val trimSuggested = active?.task == GuideTask.YOUTUBE
        return (
            listOfNotNull(spoken?.let { GuideTask.searchWords(it) ?: it }) +
                suggested.map { if (trimSuggested) GuideTask.searchWords(it) ?: it else it }
            )
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { GuideTask.normalize(it) }
            .take(MAX_CHIPS)
    }
}
