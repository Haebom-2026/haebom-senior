package kr.haebom.elder

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import kr.haebom.elder.guide.GuideService

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.open_accessibility_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.open_practice).setOnClickListener {
            startActivity(Intent(this, GuidePracticeActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val manager = getSystemService(AccessibilityManager::class.java)
        val guide = ComponentName(this, GuideService::class.java)
        val enabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                val info = service.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == guide
            }

        findViewById<TextView>(R.id.guide_status).setText(
            if (enabled) R.string.guide_enabled else R.string.guide_disabled,
        )
        findViewById<Button>(R.id.open_practice).isEnabled = enabled
    }
}
