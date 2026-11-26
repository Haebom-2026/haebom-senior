package kr.haebom.elder.dev

/**
 * 디버그 뷰어(`POST /v1/dev/screens`, `POST /v1/dev/heartbeat`)로 보내는 본문.
 * 안드로이드 API를 쓰지 않아 JVM 단위 테스트로 검증합니다. 노드 필드는 임시 규격입니다.
 */
internal data class DevNode(
    val text: String? = null,
    val description: String? = null,
    val className: String? = null,
    val bounds: IntArray? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
)

internal object DevScreenPayload {
    const val MAX_NODES = 60
    const val MAX_TEXT = 100
    private const val MAX_PACKAGE = 200

    /** 뷰어에 보낼 화면 JSON. 보낼 수 없는 화면이면 null */
    fun screen(
        deviceId: String,
        capturedAt: String,
        packageName: String,
        width: Int,
        height: Int,
        nodes: List<DevNode>,
    ): String? {
        if (!isValidDeviceId(deviceId) || packageName.isEmpty() || packageName.length > MAX_PACKAGE) return null
        if (width !in 1..10_000 || height !in 1..10_000) return null
        val items = nodes.take(MAX_NODES).mapIndexed { index, node -> node(index, node) }
        return buildString {
            append("{\"deviceId\":").append(quote(deviceId))
            append(",\"capturedAt\":").append(quote(capturedAt))
            append(",\"screen\":{\"pkg\":").append(quote(packageName))
            append(",\"width\":").append(width)
            append(",\"height\":").append(height)
            append(",\"nodes\":[").append(items.joinToString(",")).append("]}}")
        }
    }

    fun heartbeat(deviceId: String, model: String, os: String, appVersion: String): String =
        "{\"deviceId\":${quote(deviceId)},\"model\":${quote(model)},\"os\":${quote(os)}," +
            "\"appVersion\":${quote(appVersion)}}"

    fun isValidDeviceId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{8,64}"))

    /** className 의 마지막 부분 (`android.widget.Button` → `Button`) */
    fun shortClass(name: String?): String? = name?.substringAfterLast('.')?.takeIf { it.isNotBlank() }

    private fun node(index: Int, node: DevNode): String {
        val fields = mutableListOf("\"i\":$index")
        // 입력 칸에 적힌 글자는 보내지 않습니다 (어르신이 입력한 내용)
        if (!node.editable) node.text.clip()?.let { fields += "\"t\":${quote(it)}" }
        node.description.clip()?.let { fields += "\"d\":${quote(it)}" }
        node.className.clip()?.let { fields += "\"c\":${quote(it)}" }
        node.bounds?.takeIf { it.size == 4 }?.let { fields += "\"b\":[${it.joinToString(",")}]" }
        if (node.clickable) fields += "\"clickable\":true"
        if (node.editable) fields += "\"editable\":true"
        if (node.scrollable) fields += "\"scrollable\":true"
        if (node.checked) fields += "\"checked\":true"
        if (node.selected) fields += "\"selected\":true"
        return fields.joinToString(",", "{", "}")
    }

    private fun String?.clip(): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)

    internal fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
