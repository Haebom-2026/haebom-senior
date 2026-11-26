package kr.haebom.elder.guide

internal object GuideSession {
    /**
     * 안내 중인 일.
     * - [task]: 앱이 아는 일(연락처·유튜브·정부24). null 이면 AI 만으로 안내하는 일 (예: "카카오톡 열어 줘")
     * - [packageName]: [task] 가 안내하는 앱. AI 만으로 안내할 때는 null (어느 앱이든 따라감)
     * - [utterance]·[goal]·[slots]: 화면 안내 AI(#16)에 보내는 값
     * - [query]: 어르신이 말한 검색어(유튜브). 검색창이 열리면 해봄이 한 번 대신 넣어 줌.
     * - [queryFilled]: 이미 넣었으면 true (어르신이 고쳐 쓴 글을 다시 덮어쓰지 않으려고)
     */
    data class Active(
        val task: GuideTask?,
        val packageName: String?,
        val query: String? = null,
        val queryFilled: Boolean = false,
        val utterance: String = "",
        val goal: String = task?.aiGoal ?: GuideTask.GOAL_UNKNOWN,
        val slots: Map<String, String> = query?.let { mapOf("query" to it) }.orEmpty(),
        /** 어르신이 "멈추기"를 누름: 표시를 숨기고 AI 도 부르지 않음 */
        val paused: Boolean = false,
    )

    var active: Active? = null
        private set

    /** 안내를 새로 시작할 때마다 1씩 늘어남 (끝난 안내의 늦은 동작을 버리는 데 씀) */
    var serial = 0L
        private set

    var onChange: (() -> Unit)? = null

    fun start(task: GuideTask, packageName: String, query: String? = null, utterance: String = "") {
        serial++
        active = Active(task, packageName, query, utterance = utterance)
        onChange?.invoke()
    }

    /** 앱이 모르는 일: 화면 안내 AI 로만 안내 */
    fun startAi(utterance: String, goal: String, slots: Map<String, String>) {
        serial++
        active = Active(task = null, packageName = null, utterance = utterance, goal = goal, slots = slots)
        onChange?.invoke()
    }

    /** 잠시 멈춤 ↔ 이어서 */
    fun setPaused(paused: Boolean) {
        val current = active ?: return
        if (current.paused == paused) return
        active = current.copy(paused = paused)
        onChange?.invoke()
    }

    /** 검색어를 넣었다고 표시 (화면 표시는 그대로 두므로 [onChange] 를 부르지 않음) */
    fun markQueryFilled() {
        active = active?.copy(queryFilled = true)
    }

    fun stop() {
        if (active == null) return
        active = null
        onChange?.invoke()
    }
}
