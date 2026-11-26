package kr.haebom.elder.guide

/**
 * 화면에 그릴 이동 방향 화살표.
 * - [UP]·[DOWN]·[LEFT]·[RIGHT]: **손가락을 미는 방향** (서버 #16 swipe 의 dir 과 같은 뜻)
 * - [HOME]: 홈 화면으로 (화면 아래 가운데를 가리킴)
 * - [BACK]: 뒤로 가기 (화면 아래를 가리킴)
 */
internal enum class DirectionHint {
    UP, DOWN, LEFT, RIGHT, HOME, BACK;

    companion object {
        private val HOME_WORDS = listOf("홈화면", "홈버튼", "홈으로", "홈단추", "처음화면")
        private val BACK_WORDS = listOf("뒤로가", "뒤로버튼", "뒤로단추", "이전화면", "뒤로돌아")

        /** 서버 swipe 의 dir → 화살표 */
        fun fromSwipe(dir: String?): DirectionHint? = when (dir) {
            "up" -> UP
            "down" -> DOWN
            "left" -> LEFT
            "right" -> RIGHT
            else -> null
        }

        /**
         * AI 답 → 화살표. swipe 는 dir 그대로, 짚을 요소가 없는 말 안내는 문장에서 홈·뒤로를 찾음.
         * 요소를 짚는 답(highlight·suggest)은 빨간 테두리를 쓰므로 화살표 없음.
         */
        fun from(reply: AiReply): DirectionHint? {
            if (reply.type == AiReply.SWIPE) return fromSwipe(reply.dir)
            if (reply.pointsAtTarget || reply.type == AiReply.DONE) return null
            val text = reply.say.filterNot(Char::isWhitespace)
            return when {
                HOME_WORDS.any { it in text } -> HOME
                BACK_WORDS.any { it in text } -> BACK
                else -> null
            }
        }

        /**
         * 밀던 중 찾던 글자([seekText])가 화면에 나타났는지. 나타났으면 그 요소 (눌리는 것 먼저).
         * 글자가 같거나, 짧은 찾는 말이 요소 글자에 들어 있으면 같은 것으로 봄 (띄어쓰기 무시).
         */
        fun findSeek(seekText: String?, nodes: List<ScreenNode>): ScreenNode? {
            val wanted = seekText?.filterNot(Char::isWhitespace)?.takeIf { it.length >= 2 } ?: return null
            val matches = nodes.filter { node ->
                val bounds = node.bounds
                bounds != null && bounds[2] > bounds[0] && bounds[3] > bounds[1] &&
                    listOfNotNull(node.text, node.description).any { label ->
                        val compact = label.filterNot(Char::isWhitespace)
                        compact == wanted || (compact.length <= wanted.length + 12 && wanted in compact)
                    }
            }
            return matches.firstOrNull { it.clickable } ?: matches.firstOrNull()
        }
    }
}
