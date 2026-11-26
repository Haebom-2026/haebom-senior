package kr.haebom.elder

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import kr.haebom.elder.guide.GuideCaption
import kr.haebom.elder.guide.GuideSession
import kr.haebom.elder.guide.GuideTask
import kr.haebom.elder.ui.ElderUi

/** 메인 화면·말하기 창에서 안내 시작 (정부24는 확인 창을 먼저 띄움) */
internal fun startGuide(
    activity: Activity,
    task: GuideTask,
    foregroundPackage: String? = null,
    done: () -> Unit = {},
    announce: Boolean = true,
    query: String? = null,
    utterance: String = "",
) {
    fun launch() {
        if (launchGuide(activity, task, foregroundPackage, announce, query, utterance)) {
            done()
        } else {
            showGuideMessage(activity, R.string.guide_app_unavailable, done)
        }
    }

    if (task == GuideTask.GOV24 && !(foregroundPackage != null && GuideTask.forPackage(foregroundPackage) == task)) {
        ElderUi.dialog(
            activity, activity.getString(R.string.guide_gov24_limited),
            positive = activity.getString(R.string.guide_continue), onPositive = ::launch,
            negative = activity.getString(R.string.cancel), onNegative = done,
        )
    } else {
        launch()
    }
}

/**
 * 안내 세션을 시작하고 필요하면 앱을 엶. 확인 창 없이 어디서나(접근성 서비스 포함) 부를 수 있음.
 * 지금 화면이 이미 그 앱이면 열지 않고 이어서 안내. 앱을 열 수 없으면 false.
 */
internal fun launchGuide(
    context: Context,
    task: GuideTask,
    foregroundPackage: String?,
    announce: Boolean,
    query: String?,
    utterance: String,
): Boolean {
    GuideSession.stop()
    if (foregroundPackage != null && GuideTask.forPackage(foregroundPackage) == task) {
        if (announce) GuideCaption.post(context.getString(R.string.caption_guide_resumed), GuideCaption.Tone.INFO)
        GuideSession.start(task, foregroundPackage, query, utterance)
        return true
    }

    val intent = task.newIntent(context.packageManager)
    val destination = intent?.resolveActivity(context.packageManager)?.packageName
    if (intent == null || destination == null || destination == "android") {
        GuideCaption.post(context.getString(R.string.guide_app_unavailable), GuideCaption.Tone.WARN)
        return false
    }
    if (announce) {
        val name = context.getString(task.nameText)
        GuideCaption.post(context.getString(R.string.caption_guide_started, name), GuideCaption.Tone.INFO)
    }
    GuideSession.start(task, destination, query, utterance)
    return try {
        context.startActivity(intent.setPackage(destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        GuideSession.stop()
        GuideCaption.post(context.getString(R.string.guide_app_unavailable), GuideCaption.Tone.WARN)
        false
    }
}

/**
 * 앱이 모르는 일을 화면 안내 AI(#16)로 안내. 해봄 화면에서 말했으면 홈 화면에서 시작
 * (예: "카카오톡 열어 줘" → 홈 화면에서 카카오톡 아이콘을 짚음).
 */
internal fun startAiGuide(context: Context, utterance: String, foregroundPackage: String?, done: () -> Unit = {}) {
    GuideSession.stop()
    val (goal, slots) = GuideTask.aiRequestFor(utterance)
    GuideSession.startAi(utterance, goal, slots)
    if (foregroundPackage == null || foregroundPackage == context.packageName) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(home)
        } catch (_: ActivityNotFoundException) {
            // 홈 화면을 못 열면 지금 화면에서 안내
        }
    }
    done()
}

/** 폰에 깔린(홈 화면에 보이는) 앱들의 (이름, 패키지) */
internal fun launcherApps(context: Context): List<Pair<String, String>> = runCatching {
    val pm = context.packageManager
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(home, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
}.getOrDefault(emptyList())

/** 말한 이름의 앱을 열기만 함. 연 앱의 이름, 폰에 없거나 못 열면 null */
internal fun openAppByName(context: Context, spokenName: String): String? {
    val app = kr.haebom.elder.guide.Confirm.findApp(spokenName, launcherApps(context)) ?: return null
    val launch = context.packageManager.getLaunchIntentForPackage(app.second) ?: return null
    GuideSession.stop()
    return try {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        app.first
    } catch (_: ActivityNotFoundException) {
        null
    }
}

internal fun showGuideMessage(activity: Activity, message: Int, done: () -> Unit = {}) {
    ElderUi.dialog(activity, activity.getString(message), positive = activity.getString(R.string.ok), onPositive = done, onCancel = done)
}
