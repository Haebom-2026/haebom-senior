package kr.haebom.elder.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGuideTest {
    private fun label(t: String, top: Int) =
        ScreenNode(text = t, className = "TextView", bounds = intArrayOf(60, top, 1020, top + 60))

    private fun button(t: String, top: Int, enabled: Boolean = true, checked: Boolean = false) =
        ScreenNode(text = t, className = "Button", bounds = intArrayOf(60, top, 1020, top + 150), clickable = true, enabled = enabled, checked = checked)

    private fun field(top: Int, hint: String? = null, filled: Boolean = false, secret: Boolean = false) =
        ScreenNode(hint = hint, className = "EditText", bounds = intArrayOf(60, top, 1020, top + 140), clickable = true, editable = true, filled = filled, secret = secret)

    @Test
    fun ordinaryScreensAreNotLoginScreens() {
        assertNull(AuthGuide.step(listOf(label("주민등록표 등본 발급", 300), button("발급하기", 2300))))
        // 비밀 칸 없는 검색창만 있는 화면
        assertNull(AuthGuide.step(listOf(field(200, hint = "검색어 입력"), button("검색", 200))))
    }

    @Test
    fun severalLoginMethodsAreListedNotChosen() {
        val step = AuthGuide.step(
            listOf(
                label("로그인 방법을 선택하세요", 300),
                button("간편인증", 500), button("공동인증서", 700), button("금융인증서", 900), button("아이디 로그인", 1100),
            ),
        )!!
        assertEquals(AuthGuide.Kind.CHOOSE_METHOD, step.kind)
        assertNull(step.target)
        assertEquals("간편인증, 공동인증서, 금융인증서, 아이디 로그인", step.arg)
    }

    @Test
    fun gov24MemberOrGuestChoiceFromTheRealScreen() {
        // 정부24 등본 '발급하기' 뒤 실제 화면 (2026-11-27 폰): 단추는 설명(desc)에만 이름이 있음
        val step = AuthGuide.step(
            listOf(
                label("회원/비회원 신청가능 서비스입니다.", 940),
                ScreenNode(description = "회원 신청하기", bounds = intArrayOf(120, 1216, 966, 1369), clickable = true),
                ScreenNode(description = "비회원 신청하기", bounds = intArrayOf(120, 1396, 966, 1546), clickable = true),
                label("비회원으로 신청하시더라도, 일부 서비스는 간편인증 또는 인증서(공동, 금융)가 별도로 필요합니다.", 1687),
                button("공동인증서 등록 레이어 닫기", 718),
                ScreenNode(description = "로그인", bounds = intArrayOf(783, 229, 876, 346), clickable = true),
            ),
        )!!
        assertEquals(AuthGuide.Kind.CHOOSE_METHOD, step.kind)
        assertEquals("회원 신청하기, 비회원 신청하기", step.arg)
    }

    @Test
    fun loginFormGoesFieldByFieldAndNeverFills() {
        val id = field(600, hint = "아이디")
        val pw = field(800, hint = "비밀번호", secret = true)
        val login = button("로그인", 1000)
        val screen = listOf(label("로그인", 300), id, pw, login)

        val first = AuthGuide.step(screen)!!
        assertEquals(AuthGuide.Kind.TYPE_FIELD, first.kind)
        assertSame(id, first.target)
        assertEquals("아이디", first.arg)

        val second = AuthGuide.step(listOf(label("로그인", 300), id.copy(filled = true), pw, login))!!
        assertEquals(AuthGuide.Kind.TYPE_SECRET, second.kind)
        assertSame(pw, second.target)

        val done = AuthGuide.step(listOf(label("로그인", 300), id.copy(filled = true), pw.copy(filled = true), login))!!
        assertEquals(AuthGuide.Kind.SUBMIT, done.kind)
        assertSame(login, done.target)
    }

    @Test
    fun secretFieldNamedByTheLabelAboveIt() {
        // 힌트가 없는 칸: 바로 위 글자를 이름으로
        val code = field(700, secret = true)
        val step = AuthGuide.step(listOf(label("인증번호", 600), code, button("확인", 1000)))!!
        assertEquals(AuthGuide.Kind.TYPE_SECRET, step.kind)
        assertEquals("인증번호", step.arg)
    }

    @Test
    fun greyButtonMeansSomethingIsMissing() {
        val step = AuthGuide.step(
            listOf(field(600, hint = "비밀번호", secret = true, filled = true), button("다음", 1000, enabled = false)),
        )!!
        assertEquals(AuthGuide.Kind.CHECK_FIELDS, step.kind)
    }

    @Test
    fun agreeBeforeSubmitting() {
        val agree = button("전체 동의", 900)
        val step = AuthGuide.step(
            listOf(field(600, hint = "비밀번호", secret = true, filled = true), agree, button("확인", 1200)),
        )!!
        assertEquals(AuthGuide.Kind.AGREE, step.kind)
        assertSame(agree, step.target)
        // 이미 동의했으면 다음으로
        val next = AuthGuide.step(
            listOf(field(600, hint = "비밀번호", secret = true, filled = true), agree.copy(checked = true), button("확인", 1200)),
        )!!
        assertEquals(AuthGuide.Kind.SUBMIT, next.kind)
    }

    @Test
    fun waitingForTheAuthOnThePhone() {
        val done = button("인증 완료", 1500)
        val step = AuthGuide.step(listOf(label("카카오톡으로 인증 요청을 보냈습니다.", 600), done))!!
        assertEquals(AuthGuide.Kind.CONFIRM_ON_PHONE, step.kind)
        assertSame(done, step.target)
        val noButton = AuthGuide.step(listOf(label("앱에서 인증을 진행해 주세요", 600)))!!
        assertEquals(AuthGuide.Kind.CONFIRM_ON_PHONE, noButton.kind)
        assertNull(noButton.target)
    }

    @Test
    fun oneMethodIsJustPressed() {
        val easy = button("간편인증", 800)
        val step = AuthGuide.step(listOf(label("본인 확인이 필요해요", 400), easy))!!
        assertEquals(AuthGuide.Kind.START, step.kind)
        assertSame(easy, step.target)
    }

    @Test
    fun sanitizedSecretsKeepPlaceButNotText() {
        // 이름표 '계좌번호' 아래 칸: 글자를 지우고 비밀 칸으로, 화면은 서버에 보내지 않음
        val result = SensitiveText.sanitize(
            listOf(label("계좌번호", 600), ScreenNode(text = "110-123-456789", className = "EditText", bounds = intArrayOf(60, 680, 1020, 820), editable = true, filled = true)),
        )
        assertTrue(result.blocked)
        val secret = result.nodes.single { it.editable }
        assertTrue(secret.secret)
        assertNull(secret.text)
        assertTrue(secret.filled)
    }

    @Test
    fun methodChoiceOffersEveryMethodAndPressesTheChosenOne() {
        val pass = button("통신사PASS", 600)
        val kakao = button("카카오톡", 800)
        val naver = button("네이버", 1000)
        val screen = listOf(label("인증서를 선택해 주세요", 400), pass, kakao, naver)
        val ask = AuthGuide.step(screen)!!
        assertEquals(AuthGuide.Kind.CHOOSE_METHOD, ask.kind)
        assertEquals(listOf("통신사PASS", "카카오톡", "네이버"), ask.options.map(AuthGuide::optionName))
        // 어르신이 카카오톡을 골랐으면 그 단추를 짚음
        val chosen = AuthGuide.step(screen, chosen = listOf("간편인증", "카카오톡"))!!
        assertEquals(AuthGuide.Kind.PRESS_CHOSEN, chosen.kind)
        assertSame(kakao, chosen.target)
    }
}
