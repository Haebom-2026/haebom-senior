package kr.haebom.elder

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import kr.haebom.elder.guide.GuideCaption
import kr.haebom.elder.guide.GuideSession
import kr.haebom.elder.guide.GuideTask

internal fun startGuide(
    activity: Activity,
    task: GuideTask,
    foregroundPackage: String? = null,
    done: () -> Unit = {},
    announce: Boolean = true,
    query: String? = null,
    utterance: String = "",
) {
    GuideSession.stop()
    if (foregroundPackage != null && GuideTask.forPackage(foregroundPackage) == task) {
        if (announce) GuideCaption.post(activity.getString(R.string.caption_guide_resumed), GuideCaption.Tone.INFO)
        GuideSession.start(task, foregroundPackage, query, utterance)
        done()
        return
    }

    val intent = task.newIntent(activity.packageManager)
    val destination = intent?.resolveActivity(activity.packageManager)?.packageName
    if (intent == null || destination == null || destination == "android") {
        GuideCaption.post(activity.getString(R.string.guide_app_unavailable), GuideCaption.Tone.WARN)
        showGuideMessage(activity, R.string.guide_app_unavailable, done)
        return
    }

    fun launch() {
        if (announce) {
            val name = activity.getString(task.nameText)
            GuideCaption.post(activity.getString(R.string.caption_guide_started, name), GuideCaption.Tone.INFO)
        }
        GuideSession.start(task, destination, query, utterance)
        try {
            activity.startActivity(intent.setPackage(destination))
            done()
        } catch (_: ActivityNotFoundException) {
            GuideSession.stop()
            showGuideMessage(activity, R.string.guide_app_unavailable, done)
        }
    }

    if (task == GuideTask.GOV24) {
        AlertDialog.Builder(activity)
            .setMessage(R.string.guide_gov24_limited)
            .setPositiveButton(R.string.guide_continue) { _, _ -> launch() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> done() }
            .setOnCancelListener { done() }
            .show()
    } else {
        launch()
    }
}

/**
 * 앱이 모르는 일을 화면 안내 AI(#16)로 안내. 해봄 화면에서 말했으면 홈 화면에서 시작
 * (예: "카카오톡 열어 줘" → 홈 화면에서 카카오톡 아이콘을 짚음).
 */
internal fun startAiGuide(activity: Activity, utterance: String, foregroundPackage: String?, done: () -> Unit = {}) {
    GuideSession.stop()
    val (goal, slots) = GuideTask.aiRequestFor(utterance)
    GuideSession.startAi(utterance, goal, slots)
    if (foregroundPackage == null || foregroundPackage == activity.packageName) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(home)
        } catch (_: ActivityNotFoundException) {
            // 홈 화면을 못 열면 지금 화면에서 안내
        }
    }
    done()
}

internal fun showGuideMessage(activity: Activity, message: Int, done: () -> Unit = {}) {
    AlertDialog.Builder(activity)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok) { _, _ -> done() }
        .setOnCancelListener { done() }
        .show()
}
