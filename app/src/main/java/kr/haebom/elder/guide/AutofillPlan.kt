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
        val spoken = (active?.query ?: active?.slots?.get("query"))
            ?.takeIf { active?.queryFilled == false }
        val suggested = if (reply.type == AiReply.SUGGEST) reply.chips else emptyList()
        // 단추에는 검색어만: AI 추천어나 대답이 문장("임영웅 검색 해줘")이어도 앞부분만 넣음
        return (listOfNotNull(spoken) + suggested)
            .map { GuideTask.searchWords(it) ?: it.trim() }
            .filter(String::isNotEmpty)
            .distinctBy { GuideTask.normalize(it) }
            .take(MAX_CHIPS)
    }
}
