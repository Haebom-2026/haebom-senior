package kr.haebom.elder

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import kr.haebom.elder.guide.GuideSession
import kr.haebom.elder.guide.GuideTask

internal fun startGuide(
    activity: Activity,
    task: GuideTask,
    foregroundPackage: String? = null,
    done: () -> Unit = {},
) {
    GuideSession.stop()
    if (foregroundPackage != null && GuideTask.forPackage(foregroundPackage) == task) {
        GuideSession.start(task, foregroundPackage)
        done()
        return
    }

    val intent = task.newIntent(activity.packageManager)
    val destination = intent?.resolveActivity(activity.packageManager)?.packageName
    if (intent == null || destination == null || destination == "android") {
        showGuideMessage(activity, R.string.guide_app_unavailable, done)
        return
    }

    fun launch() {
        GuideSession.start(task, destination)
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

internal fun showGuideMessage(activity: Activity, message: Int, done: () -> Unit = {}) {
    AlertDialog.Builder(activity)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok) { _, _ -> done() }
        .setOnCancelListener { done() }
        .show()
}
