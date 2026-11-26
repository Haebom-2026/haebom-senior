package kr.haebom.elder.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyRulesTest {
    @Test
    fun sensitiveAppsAreMatchedByPackageOnly() {
        assertEquals(SensitiveApps.CATEGORY_PUBLIC, SensitiveApps.category("kr.go.minwon.m"))
        assertEquals(SensitiveApps.CATEGORY_FINANCE, SensitiveApps.category("viva.republica.toss"))
        assertEquals(SensitiveApps.CATEGORY_FINANCE, SensitiveApps.category("com.kakaobank.channel"))
        assertEquals(SensitiveApps.CATEGORY_FINANCE, SensitiveApps.category("com.example.newbank"))
        assertNull(SensitiveApps.category("com.kakao.talk"))
        assertNull(SensitiveApps.category("com.google.android.youtube"))
        assertNull(SensitiveApps.category(null))
    }

    @Test
    fun phoneNumbersAreCheckedAndShownInAFriendlyWay() {
        assertEquals("01012345678", ElderSettings.normalizePhone("010-1234-5678"))
        assertTrue(ElderSettings.isValidPhone("010 1234 5678"))
        assertFalse(ElderSettings.isValidPhone("12"))
        assertEquals("010-1234-5678", ElderSettings.displayPhone("01012345678"))
        assertEquals("02-1234-5678", ElderSettings.displayPhone("0212345678"))
    }

    @Test
    fun guardianLabelFallsBackToNameThenDefault() {
        val base = FamilyLink("e", "g", "김해봄", null, null, 0L)
        assertEquals(FamilyLink.DEFAULT_GUARDIAN_LABEL, base.labelForElder)
        assertEquals("김준표", base.copy(guardianName = "김준표").labelForElder)
        assertEquals("큰딸", base.copy(guardianLabel = "큰딸", guardianName = "김준표").labelForElder)
        assertEquals("김해봄", base.labelForGuardian)
    }
}
