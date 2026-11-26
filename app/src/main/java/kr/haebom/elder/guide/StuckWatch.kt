package kr.haebom.elder.guide

/**
 * 안내 중 막힘 알아채기 (안드로이드 없이 단위 테스트).
 * 같은 안내([serial])에서 화면 지문이 [limitMs] 동안 그대로면 "막힘". 안내 하나에 한 번만 묻기.
 * 화면이 바뀌면(눌렀거나 입력했으면) 다시 셈.
 */
internal class StuckWatch(private val limitMs: Long = LIMIT_MS) {
    private var fingerprint: String? = null
    private var serial = -1L
    private var since = 0L
    private val asked = mutableSetOf<Long>()

    /** 지금 본 화면. 바뀌었으면 시계를 다시 맞추고 true */
    fun seen(fingerprint: String, serial: Long, now: Long): Boolean {
        if (fingerprint == this.fingerprint && serial == this.serial) return false
        this.fingerprint = fingerprint
        this.serial = serial
        since = now
        return true
    }

    /** 이 안내에서 아직 묻지 않았고, 같은 화면이 [limitMs] 넘게 그대로인지 */
    fun due(serial: Long, now: Long): Boolean =
        serial == this.serial && serial !in asked && fingerprint != null && now - since >= limitMs

    fun markAsked(serial: Long) {
        asked += serial
    }

    companion object {
        const val LIMIT_MS = 45_000L
    }
}
