package kr.haebom.elder.guide

/**
 * 화면을 서버로 보낼 때의 본문: 화면 안내 AI(#16 `POST /v1/elder/guide`)와
 * 디버그 뷰어(`POST /v1/dev/screens`, `POST /v1/dev/heartbeat`)가 같은 노드 형식을 씁니다.
 * 안드로이드 API를 쓰지 않아 JVM 단위 테스트로 검증합니다.
 */
internal data class ScreenNode(
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

internal object ScreenPayload {
    const val MAX_NODES = 60
    const val MAX_TEXT = 100
    const val MAX_HISTORY = 10
    private val MOVING_CLASSES = setOf("SeekBar", "ProgressBar")
    private const val MAX_PACKAGE = 200

    /** 뷰어에 보낼 화면 JSON. 보낼 수 없는 화면이면 null */
    fun screen(
        deviceId: String,
        capturedAt: String,
        packageName: String,
        width: Int,
        height: Int,
        nodes: List<ScreenNode>,
    ): String? {
        if (!isValidDeviceId(deviceId)) return null
        val screen = screenObject(packageName, width, height, nodes) ?: return null
        return "{\"deviceId\":${quote(deviceId)},\"capturedAt\":${quote(capturedAt)},\"screen\":$screen}"
    }

    /** 화면 객체만 ({pkg,width,height,nodes}). #16 guide 요청의 screen 과 같은 모양 */
    fun screenObject(packageName: String, width: Int, height: Int, nodes: List<ScreenNode>): String? {
        if (packageName.isEmpty() || packageName.length > MAX_PACKAGE) return null
        if (width !in 1..10_000 || height !in 1..10_000) return null
        val items = nodes.take(MAX_NODES).mapIndexed { index, node -> node(index, node) }
        return "{\"pkg\":${quote(packageName)},\"width\":$width,\"height\":$height,\"nodes\":[${items.joinToString(",")}]}"
    }

    /**
     * #16 POST /v1/elder/guide 요청 본문.
     * [history] 지금까지 안내한 문장 (오래된 것부터, 최대 10개), [deviceId] dev 디버그 뷰어 기록용 (prod 는 무시)
     */
    fun guide(
        requestId: Int,
        goal: String,
        utterance: String,
        slots: Map<String, String>,
        screen: String,
        history: List<String> = emptyList(),
        deviceId: String? = null,
    ): String {
        val slotJson = slots.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k.take(40))}:${quote(v.take(100))}" }
        val historyJson = history.takeLast(MAX_HISTORY).joinToString(",", "[", "]") { quote(it.take(200)) }
        val device = deviceId?.takeIf(::isValidDeviceId)?.let { ",\"deviceId\":${quote(it)}" }.orEmpty()
        return "{\"requestId\":$requestId,\"goal\":${quote(goal.take(40))},\"utterance\":${quote(utterance.take(200))}," +
            "\"slots\":$slotJson,\"history\":$historyJson,\"screen\":$screen$device}"
    }

    /**
     * 화면 지문: 같은 화면인지 비교할 때 씀 (늦은 AI 응답 버리기, 다시 물어볼지 정하기).
     * 누를 수 있는 요소·입력 칸의 글자만 보고, 숫자는 빼서 재생 시간처럼 계속 바뀌는 글자에 흔들리지 않게 함.
     */
    fun fingerprint(packageName: String, nodes: List<ScreenNode>): String {
        val parts = nodes.take(MAX_NODES)
            .filter { (it.clickable || it.editable) && it.className !in MOVING_CLASSES }
            .map { node ->
                val label = if (node.editable) "" else node.text.orEmpty()
                "${node.className}|$label|${node.description.orEmpty()}".filterNot(Char::isDigit)
            }
        return packageName + "#" + parts.joinToString("").hashCode().toUInt().toString(16)
    }

    fun heartbeat(deviceId: String, model: String, os: String, appVersion: String): String =
        "{\"deviceId\":${quote(deviceId)},\"model\":${quote(model)},\"os\":${quote(os)}," +
            "\"appVersion\":${quote(appVersion)}}"

    fun isValidDeviceId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{8,64}"))

    /** className 의 마지막 부분 (`android.widget.Button` → `Button`) */
    fun shortClass(name: String?): String? = name?.substringAfterLast('.')?.takeIf { it.isNotBlank() }

    private fun node(index: Int, node: ScreenNode): String {
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
