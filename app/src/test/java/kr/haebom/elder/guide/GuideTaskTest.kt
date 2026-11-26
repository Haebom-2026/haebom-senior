package kr.haebom.elder.guide

import kr.haebom.elder.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTaskTest {
    @Test
    fun contextualRequestsUseTheForegroundApp() {
        assertEquals(GuideTask.YOUTUBE, GuideTask.forCommand("트로트 검색해 줘", "com.google.android.youtube"))
        assertEquals(GuideTask.CONTACTS, GuideTask.forCommand("이 화면 안내해 줘", "com.android.contacts"))
        assertNull(GuideTask.forCommand("이 화면 안내해 줘", "com.example.unsupported"))
    }

    @Test
    fun passwordScreenDoesNotReturnAControl() {
        val step = GuideTask.CONTACTS.findStep(emptyList(), hasPasswordField = true)
        assertEquals(R.string.guide_authentication_stop, step.caption)
        assertNull(step.control)
    }

    @Test
    fun protectedRequestsHaveLocalGuardsAndOnlyKnownUiLabelsAreKept() {
        assertTrue(GuideTask.isFinance("viva.republica.toss", "이 화면 안내"))
        assertTrue(GuideTask.isPrivateCommand("인증번호 넣어 줘"))
        assertFalse(GuideTask.isHelpCommand("이 화면 좀 도와줘"))
        assertFalse("01012345678" in GuideTask.CONTACTS.allowedLabels)
    }
}
