package kr.haebom.elder

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 앱이 여는 웹 페이지는 크롬으로 고정합니다.
 * 안내 규칙(눌러야 할 단추 이름·위치)을 크롬 화면 기준으로 맞추기 때문에, 브라우저마다 화면이 달라지지 않게 하려는 것입니다.
 * 크롬이 없거나 꺼져 있을 때만 기기 기본 브라우저로 엽니다.
 */
internal object Browser {
    const val CHROME = "com.android.chrome"

    fun isChromeAvailable(packageManager: PackageManager): Boolean = try {
        packageManager.getApplicationInfo(CHROME, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** 웹 주소를 여는 Intent. 크롬이 있으면 크롬으로 고정 */
    fun intent(packageManager: PackageManager, url: String): Intent {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        return if (isChromeAvailable(packageManager)) view.setPackage(CHROME) else view
    }
}
