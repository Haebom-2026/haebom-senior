package kr.haebom.elder.guide

internal object GuideSession {
    data class Active(val task: GuideTask, val packageName: String)

    var active: Active? = null
        private set

    var onChange: (() -> Unit)? = null

    fun start(task: GuideTask, packageName: String) {
        active = Active(task, packageName)
        onChange?.invoke()
    }

    fun stop() {
        if (active == null) return
        active = null
        onChange?.invoke()
    }
}
