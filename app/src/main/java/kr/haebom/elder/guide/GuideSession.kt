package kr.haebom.elder.guide

internal object GuideSession {
    /**
     * 안내 중인 일.
     * - [task]: 앱이 아는 일(연락처·유튜브·정부24). null 이면 AI 만으로 안내하는 일 (예: "카카오톡 열어 줘")
     * - [packageName]: [task] 가 안내하는 앱. AI 만으로 안내할 때는 null (어느 앱이든 따라감)
     * - [utterance]·[goal]·[slots]: 화면 안내 AI(#16)에 보내는 값.
     *   단추로 시작해 말한 문장이 없으면 [GuideTask.defaultUtterance] 로 할 일을 분명히 알려 줌
     * - [query]: 어르신이 말한 검색어(유튜브). 검색창이 열리면 자동완성 입력 단추로 보여 줌
     * - [queryFilled]: 이미 넣었으면 true
     */
    data class Active(
        val task: GuideTask?,
        val packageName: String?,
        val query: String? = null,
        val queryFilled: Boolean = false,
        val utterance: String = "",
        val goal: String = task?.aiGoal ?: GuideTask.GOAL_UNKNOWN,
        val slots: Map<String, String> = task?.defaultSlots(query).orEmpty(),
        /** (서버 v4) 바로 앞 AI 질문에 고른 답. 다음 요청에 한 번 보내고 비움 */
        val userReply: String = "",
    )

    var active: Active? = null
        private set

    /** 안내를 새로 시작하거나 대답으로 이어 갈 때마다 1씩 늘어남 (지난 AI 답·늦은 동작을 버리는 데 씀) */
    var serial = 0L
        private set

    var onChange: (() -> Unit)? = null

    fun start(task: GuideTask, packageName: String, query: String? = null, utterance: String = "") {
        serial++
        active = Active(task, packageName, query, utterance = utterance.ifBlank { task.defaultUtterance(query) })
        onChange?.invoke()
    }

    /** 앱이 모르는 일: 화면 안내 AI 로만 안내 */
    fun startAi(utterance: String, goal: String, slots: Map<String, String>) {
        serial++
        active = Active(task = null, packageName = null, utterance = utterance, goal = goal, slots = slots)
        onChange?.invoke()
    }

    /**
     * AI 가 되물은 말에 어르신이 대답함: 같은 안내를 그 대답을 붙여 이어 감.
     * 유튜브에서 검색어가 아직 없었으면 대답을 검색어로 씀 ("어떤 영상을 찾을까요?" → "임영웅")
     */
    fun answer(text: String) {
        val current = active ?: return
        val reply = text.trim().takeIf { it.isNotEmpty() } ?: return
        // 대답이 "임영웅 검색해 줘" 처럼 문장이어도 검색어("임영웅")만
        val query = current.query ?: (GuideTask.searchWords(reply) ?: reply).takeIf { current.task == GuideTask.YOUTUBE }
        // 같은 안내를 이어 가므로 serial 은 그대로 (안내 기록을 지우지 않음). 지난 질문은 부르는 쪽이 지움
        active = current.copy(
            utterance = (current.utterance + " / " + reply).take(200),
            query = query,
            queryFilled = current.queryFilled && query == current.query,
            slots = current.slots + (if (query != null && current.task == GuideTask.YOUTUBE) mapOf("query" to query) else emptyMap()),
            userReply = reply.take(100),
        )
        onChange?.invoke()
    }

    /** 보낸 대답을 비움 (다음 요청부터는 보내지 않음, 화면 표시는 그대로) */
    fun consumeUserReply(): String {
        val current = active ?: return ""
        if (current.userReply.isEmpty()) return ""
        active = current.copy(userReply = "")
        return current.userReply
    }

    /** 검색어를 넣었다고 표시 (화면 표시는 그대로 두므로 [onChange] 를 부르지 않음) */
    fun markQueryFilled() {
        active = active?.copy(queryFilled = true)
    }

    fun stop() {
        if (active == null) return
        // 왜 끝났는지 추적용 (부른 곳)
        android.util.Log.i("HaebomGuide", "안내 끝: ${Throwable().stackTrace.getOrNull(1)}")
        active = null
        onChange?.invoke()
    }
}
