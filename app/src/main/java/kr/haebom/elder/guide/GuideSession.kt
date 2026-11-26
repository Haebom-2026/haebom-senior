package kr.haebom.elder.guide

internal object GuideSession {
    /**
     * [query]: 어르신이 말한 검색어(유튜브). 검색창이 열리면 해봄이 한 번 대신 넣어 줌.
     * [queryFilled]: 이미 넣었으면 true (어르신이 고쳐 쓴 글을 다시 덮어쓰지 않으려고)
     */
    data class Active(
        val task: GuideTask,
        val packageName: String,
        val query: String? = null,
        val queryFilled: Boolean = false,
    )

    var active: Active? = null
        private set

    var onChange: (() -> Unit)? = null

    fun start(task: GuideTask, packageName: String, query: String? = null) {
        active = Active(task, packageName, query)
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
