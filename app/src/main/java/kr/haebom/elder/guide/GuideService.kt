package kr.haebom.elder.guide

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import kr.haebom.elder.GuideInputActivity
import kr.haebom.elder.R
import kr.haebom.elder.family.ElderSettings
import kr.haebom.elder.family.Family
import kr.haebom.elder.family.SensitiveAlerter
import kr.haebom.elder.family.SensitiveConsentActivity
import kr.haebom.elder.family.Sensitivity
import kr.haebom.elder.net.HaebomServer

/**
 * 접근성 서비스 본체. 화면이 바뀔 때마다(0.5초 묶음) 지금 상황을 판단해 세 가지 오버레이를 맞춥니다.
 *   1) 빨간 테두리 표시 (눌러야 할 곳)
 *   2) 큰 자막 막대 (안내 + 들은 말 + 알림)
 *   3) 마이크·SOS 묶음 (가리면 비켜 줌)
 *
 * 상황별 대처
 *   - 안내하던 앱 화면  : 눌러야 할 곳 표시 + 위치를 말로 안내. 오래 못 찾으면 "위로 밀어 보세요"
 *   - 비밀번호·인증 화면 : 표시 없이 "직접 해 주세요" 주의 자막
 *   - 다른 앱으로 나감   : 표시를 지우고 "다시 열면 이어서 안내할게요"
 *   - 키보드가 뜸        : 자막과 마이크를 키보드 위로
 *   - 잠금 화면          : 모두 숨김
 *   - 정부24 등 공공 앱(L1) : 보호자 알림 동의 팝업 / 은행·금융 앱(L2) : 경고 자막 + 바로 알림 (같은 일은 10분에 한 번)
 */
class GuideService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val settings by lazy { ElderSettings(this) }
    private var connected = false
    private var refreshScheduled = false
    private val refresh = Runnable {
        refreshScheduled = false
        refreshScreen()
    }
    private val captionTick = Runnable { renderCaption() }

    private var highlight: View? = null
    private var highlightedTarget: Target? = null
    private var captionHost: FrameLayout? = null
    private var captionView: CaptionBarView? = null
    private var captionAtTop: Boolean? = null
    private var captionBox: Box? = null
    private var keyboardTop: Int? = null
    /** 지금 화면의 작업 단추 후보들 (짚은 단추가 없을 때 자막·마이크가 가리지 않게) */
    private var candidateBoxes: List<Box> = emptyList()

    /** 지금 화면에 맞는 안내 자막 (잠깐 자막이 없을 때 보임) */
    private var guideMessage: Pair<String, GuideCaption.Tone>? = null
    private var spokenGuide: String? = null
    private var spokenSerial = 0L
    private var noTargetSince = 0L
    private var leftAppSince = 0L
    private var lastForeground: String? = null

    private val floatingControls by lazy {
        FloatingGuideControls(
            context = this,
            onMicrophone = { onMicrophone() },
            onSos = { openInput(sos = true) },
            onStop = {
                GuideSession.stop()
                GuideCaption.post(getString(R.string.caption_guide_stopped), GuideCaption.Tone.INFO)
            },
        )
    }
    private var voice: GuideVoice? = null
    private val sessionChanged: () -> Unit = {
        resetAi()
        noTargetSince = 0L
        leftAppSince = 0L
        clearHighlight()
        scheduleRefresh()
    }
    private val captionChanged: (GuideCaption.Message?) -> Unit = { renderCaption() }

    override fun onServiceConnected() {
        connected = true
        GuideSession.onChange = sessionChanged
        GuideCaption.listener = captionChanged
        DevScreenHook.sink?.onConnected(this)
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        DevScreenHook.sink?.onScreenChanged(this)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let(::onForegroundApp)
        }
        scheduleRefresh()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingControls.hide()
        clearHighlight()
        removeCaption()
        scheduleRefresh()
    }

    override fun onInterrupt() {
        clearHighlight()
    }

    override fun onUnbind(intent: Intent): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        voice?.close()
        voice = null
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        if (!connected || refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed(refresh, SCREEN_UPDATE_INTERVAL_MS)
    }

    // ───────────────────────── 상황 판단 ─────────────────────────

    private fun refreshScreen() {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked) {
            floatingControls.hide()
            clearHighlight()
            removeCaption()
            return
        }
        keyboardTop = findKeyboardTop()
        val foreground = activeScreenPackage()
        syncFloatingControls(foreground)

        candidateBoxes = emptyList()
        val decision = decide(foreground)
        guideMessage = decision.message
        if (decision.target != highlightedTarget) {
            clearHighlight()
            decision.target?.let(::showHighlight)
        }
        renderCaption()
    }

    private data class Decision(val target: Target?, val message: Pair<String, GuideCaption.Tone>?)

    private fun decide(foreground: String?): Decision {
        val active = GuideSession.active
        if (foreground == packageName) {
            // 해봄 화면: 연습 화면의 단추만 표시
            return Decision(findPracticeTarget(), null)
        }
        if (active == null || foreground == null) return Decision(null, null)
        if (foreground in PASS_THROUGH) return Decision(highlightedTarget, guideMessage)

        val task = active.task
        val taskPackage = active.packageName
        if (task != null && taskPackage != null && foreground != taskPackage) {
            if (leftAppSince == 0L) leftAppSince = SystemClock.uptimeMillis()
            // 잠깐 다른 창이 스치는 것은 무시하고, 1.5초 넘게 나가 있으면 안내
            if (SystemClock.uptimeMillis() - leftAppSince < LEFT_APP_GRACE_MS) {
                scheduleLater(LEFT_APP_GRACE_MS)
                return Decision(null, null)
            }
            val name = appLabel(taskPackage)
            return Decision(null, getString(R.string.caption_left_app, name) to GuideCaption.Tone.WARN)
        }
        leftAppSince = 0L

        val query = active.query
        val extraLabels = query?.let { setOf(GuideTask.normalize(it)) } ?: emptySet()
        val read = readScreens(task, extraLabels) ?: return Decision(null, guideMessage)
        val screen = read.local
        val captured = read.captured
        val windowId = read.windowId
        screen?.let { local ->
            candidateBoxes = local.controls.filter { it.clickable }
                .map { Box(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) }
        }

        // 비밀번호·인증 화면: 표시 없이 직접 하시도록 (AI 에도 보내지 않음)
        val step = screen?.let { task?.findStep(it.controls, it.hasPasswordField, query, it.hasSearchField) }
        if (captured?.blocked == true || screen?.hasPasswordField == true ||
            step?.caption == R.string.guide_authentication_stop
        ) {
            noTargetSince = 0L
            return Decision(null, getString(R.string.guide_authentication_stop) to GuideCaption.Tone.WARN)
        }

        // 검색창이 열리면 말씀하신 검색어를 한 번 대신 넣어 드림 (자동 완성)
        if (task != null && screen != null && query != null && !active.queryFilled && screen.hasSearchField) {
            GuideSession.markQueryFilled()
            if (fillSearchField(task, foreground, query)) {
                scheduleLater(FILL_SETTLE_MS)
            }
        }

        // 화면 안내 AI(#16): 서버가 있으면 AI 가 다음 행동을 정하고, 답하지 못하면 아래 로컬 안내로
        if (ai != null && captured != null) {
            aiDecision(active, captured, windowId, foreground)?.let { return it }
        }
        if (task == null || screen == null || step == null) {
            // AI 만으로 안내하는 일인데 AI 가 답하지 못함
            return Decision(null, (aiFailedSay ?: getString(R.string.caption_ai_failed)) to GuideCaption.Tone.WARN)
        }

        val control = step.control
        if (control != null) {
            noTargetSince = 0L
            val target = Target(windowId, foreground, control.bounds, step.caption, step.arg)
            return Decision(target, captionText(target) to GuideCaption.Tone.GUIDE)
        }
        // 누를 곳을 못 찾음: 기다렸다가 스크롤 안내를 덧붙임
        if (noTargetSince == 0L) noTargetSince = SystemClock.uptimeMillis()
        var text = captionString(step.caption, step.arg)
        if (screen.hasScrollable && SystemClock.uptimeMillis() - noTargetSince > SCROLL_TIP_AFTER_MS) {
            text += " " + getString(R.string.caption_scroll_tip)
        } else if (screen.hasScrollable) {
            scheduleLater(SCROLL_TIP_AFTER_MS)
        }
        return Decision(null, text to GuideCaption.Tone.GUIDE)
    }

    // ───────────────────────── 화면 안내 AI (#16) ─────────────────────────

    private data class AiPending(val requestId: Int, val fingerprint: String)

    private val ai: AiGuideClient? by lazy { HaebomServer.api?.let(::AiGuideClient) }
    private var aiPending: AiPending? = null
    /** 화면 지문 → 그 화면에 대한 AI 답 */
    private var aiAnswer: Pair<String, AiReply>? = null
    /** AI 가 답하지 못한 화면 지문 (같은 화면에서는 로컬 안내를 씀) */
    private var aiFailedFingerprint: String? = null
    private var aiFailedSay: String? = null
    private val aiHistory = ArrayDeque<String>()
    private var aiDoneScheduled = false

    private fun resetAi() {
        aiPending = null
        aiAnswer = null
        aiFailedFingerprint = null
        aiFailedSay = null
        aiHistory.clear()
        aiDoneScheduled = false
    }

    /**
     * 지금 화면에 대한 AI 안내. 답이 없으면 물어보고 "살펴보고 있어요" 자막.
     * null 이면 이 화면은 AI 가 답하지 못해 로컬 안내를 씀.
     */
    private fun aiDecision(active: GuideSession.Active, captured: CapturedScreen, windowId: Int, foreground: String): Decision? {
        val fingerprint = captured.fingerprint
        if (fingerprint == aiFailedFingerprint) return null
        val answer = aiAnswer?.takeIf { it.first == fingerprint }?.second
        if (answer != null) return applyAi(answer, captured, windowId, foreground)

        if (aiPending?.fingerprint != fingerprint) requestAi(active, captured, fingerprint)
        return Decision(null, getString(R.string.caption_ai_waiting) to GuideCaption.Tone.INFO)
    }

    private fun requestAi(active: GuideSession.Active, captured: CapturedScreen, fingerprint: String) {
        val client = ai ?: return
        val size = screenSize()
        val screenJson = ScreenPayload.screenObject(captured.packageName, size.x, size.y, captured.nodes) ?: return
        val id = client.request(
            active.goal, active.utterance, active.slots, screenJson, aiHistory.toList(), settings.deviceId,
        ) { requestId, outcome -> onAiOutcome(requestId, fingerprint, outcome) }
        aiPending = AiPending(id, fingerprint)
    }

    private fun onAiOutcome(requestId: Int, fingerprint: String, outcome: AiOutcome) {
        // 더 새 요청이 있거나 안내가 바뀌었으면 버림
        if (aiPending?.requestId != requestId) return
        aiPending = null
        val reply = (outcome as? AiOutcome.Reply)?.reply
        if (reply == null || reply.isFallback) {
            aiFailedFingerprint = fingerprint
            aiFailedSay = reply?.say
        } else {
            aiAnswer = fingerprint to reply
            if (reply.say.isNotBlank() && aiHistory.lastOrNull() != reply.say) {
                aiHistory.addLast(reply.say)
                while (aiHistory.size > ScreenPayload.MAX_HISTORY) aiHistory.removeFirst()
            }
        }
        scheduleRefresh()
    }

    private fun applyAi(reply: AiReply, captured: CapturedScreen, windowId: Int, foreground: String): Decision {
        val say = reply.say.ifBlank { getString(R.string.caption_here) }
        return when (reply.type) {
            AiReply.DONE -> {
                if (!aiDoneScheduled) {
                    aiDoneScheduled = true
                    val session = GuideSession.active
                    handler.postDelayed({ if (GuideSession.active === session) GuideSession.stop() }, AI_DONE_LINGER_MS)
                }
                Decision(null, say to GuideCaption.Tone.DONE)
            }
            AiReply.HIGHLIGHT, AiReply.SUGGEST -> {
                val b = AiReply.findTarget(reply, captured.nodes)?.bounds
                if (b == null) {
                    // 짚으라는 요소가 지금 화면에 없음: 다시 물어봄
                    aiAnswer = null
                    scheduleRefresh()
                    Decision(null, getString(R.string.caption_ai_waiting) to GuideCaption.Tone.INFO)
                } else {
                    val target = Target(windowId, foreground, Rect(b[0], b[1], b[2], b[3]), text = say)
                    Decision(target, say to GuideCaption.Tone.GUIDE)
                }
            }
            else -> Decision(null, say to GuideCaption.Tone.GUIDE)
        }
    }

    private data class ScreenRead(val local: GuideScreen?, val captured: CapturedScreen?, val windowId: Int)

    /** 로컬 안내용 단추 목록(아는 일일 때)과 AI 로 보낼 화면을 한 번에 읽음 */
    @Suppress("DEPRECATION") // 노드 recycle 은 API 33 미만에서 필요
    private fun readScreens(task: GuideTask?, extraLabels: Set<String>): ScreenRead? {
        val root = rootInActiveWindow ?: return null
        return try {
            ScreenRead(
                local = task?.let { GuideScreen.read(root, it, extraLabels) },
                captured = if (ai != null) CapturedScreen.read(root) else null,
                windowId = root.windowId,
            )
        } finally {
            root.recycle()
        }
    }

    /** 작업의 검색창([GuideTask.searchFieldIds])에 검색어를 넣음. 넣었으면 true */
    @Suppress("DEPRECATION")
    private fun fillSearchField(task: GuideTask, pkg: String, query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            for (id in task.searchFieldIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                try {
                    val field = nodes.firstOrNull { it.isVisibleToUser && it.isEditable && !it.isPassword } ?: continue
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                    }
                    val done = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    android.util.Log.i(TAG, "검색어 넣기 ($id): ${if (done) "성공" else "실패"}")
                    return done
                } finally {
                    nodes.forEach { it.recycle() }
                }
            }
            return false
        } finally {
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun findPracticeTarget(): Target? {
        val root = rootInActiveWindow ?: return null
        try {
            for (id in listOf(R.id.practice_finish, R.id.practice_next)) {
                val nodes = root.findAccessibilityNodeInfosByViewId(resources.getResourceName(id))
                try {
                    for (node in nodes) {
                        if (!node.isVisibleToUser || !node.isEnabled) continue
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty) return Target(root.windowId, packageName, bounds)
                    }
                } finally {
                    nodes.forEach { it.recycle() }
                }
            }
            return null
        } finally {
            root.recycle()
        }
    }

    /** 민감 앱에 들어오면 보호자에게 알릴지 묻기 (어르신이 "예"를 골라야만 보냄) */
    /**
     * 민감 앱에 들어왔을 때 (단계는 [Sensitivity.fromApp]).
     * - L1 공공(정부24 등): "보호자에게 알릴까요?" 물어봄 (설정에서 끌 수 있음)
     * - L2 금융: 막지 않고 경고 자막 + 동의 없이 바로 보호자 알림
     * 같은 일은 10분에 한 번만.
     */
    private fun onForegroundApp(pkg: String) {
        if (pkg == lastForeground) return
        lastForeground = pkg
        if (pkg == packageName) return
        val assessment = Sensitivity.fromApp(pkg)
        when {
            assessment.level.alertNow -> {
                if (!SensitiveAlerter.claim(SensitiveAlerter.keyFor(assessment, pkg))) return
                GuideCaption.post(getString(R.string.alert_finance_app), GuideCaption.Tone.WARN, durationMs = 8_000)
                handler.postDelayed({ SensitiveAlerter.send(this, assessment, appLabel(pkg), pkg) }, 3_000)
            }
            assessment.level.askFirst -> {
                if (!settings.sensitiveAlerts || Family.gateway == null) return
                if (!SensitiveAlerter.claim("ask:$pkg")) return
                startActivity(
                    SensitiveConsentActivity.intent(this, pkg, appLabel(pkg), assessment.category)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    // ───────────────────────── 오버레이 ─────────────────────────

    private fun syncFloatingControls(activePackage: String?) {
        if (activePackage != null && activePackage != packageName &&
            activePackage != "com.android.systemui" && activePackage != "android"
        ) {
            floatingControls.setSosVisible(settings.sosEnabled)
            floatingControls.setGuiding(GuideSession.active != null)
            floatingControls.show()
        } else {
            floatingControls.hide()
        }
    }

    private fun showHighlight(target: Target) {
        val view = GuideHighlightView(this, target.bounds, getString(R.string.caption_here))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
        } catch (_: WindowManager.BadTokenException) {
            return // 서비스가 다시 시작되는 중이면 오버레이 토큰이 없을 수 있음
        }
        highlight = view
        highlightedTarget = target
        // 자막·버튼이 어두운 막 아래로 가지 않게 다시 위로
        captionHost?.let { reattachCaption() }
        floatingControls.bringToFront()
    }

    private fun clearHighlight() {
        val view = highlight ?: return
        highlight = null
        highlightedTarget = null
        getSystemService(WindowManager::class.java).removeViewImmediate(view)
    }

    /** 잠깐 자막 > 안내 자막 순으로 하나를 골라 띄우고, 새 문장이면 읽어 줌 */
    private fun renderCaption() {
        handler.removeCallbacks(captionTick)
        val transient = GuideCaption.active()
        val message: Pair<String, GuideCaption.Tone>? = transient?.let { it.text to it.tone } ?: guideMessage
        if (message == null) {
            removeCaption()
            updateFloatingAvoid()
            return
        }
        ensureCaption()
        captionView?.show(message.first, message.second)
        placeCaption()

        if (transient != null) {
            if (transient.speak && transient.serial != spokenSerial) {
                spokenSerial = transient.serial
                speak(transient.text)
            }
            val remaining = transient.until - GuideCaption.now()
            if (remaining in 1..60_000) handler.postDelayed(captionTick, remaining + 50)
        } else if (message.first != spokenGuide) {
            spokenGuide = message.first
            // "살펴보고 있어요"는 화면이 바뀔 때마다 뜨므로 읽지 않음
            if (message.first != getString(R.string.caption_ai_waiting)) speak(message.first)
        }
    }

    private fun ensureCaption() {
        if (captionHost != null) return
        val bar = CaptionBarView(this)
        val host = FrameLayout(this).apply {
            val side = dp(12)
            setPadding(side, dp(8), side, dp(8))
            addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> onCaptionLaidOut(v) }
        }
        captionView = bar
        captionHost = host
        captionAtTop = null
        try {
            getSystemService(WindowManager::class.java).addView(host, captionParams(atTop = false))
        } catch (_: WindowManager.BadTokenException) {
            captionHost = null
            captionView = null
            return
        }
        floatingControls.bringToFront()
    }

    private fun reattachCaption() {
        val host = captionHost ?: return
        val wm = getSystemService(WindowManager::class.java)
        val params = host.layoutParams as? WindowManager.LayoutParams ?: return
        wm.removeViewImmediate(host)
        try {
            wm.addView(host, params)
        } catch (_: WindowManager.BadTokenException) {
            captionHost = null
            captionView = null
        }
    }

    private fun placeCaption() {
        val host = captionHost ?: return
        val atTop = OverlayPlanner.captionAtTop(
            screenSize().y, highlightedTarget?.box(), keyboardTop,
            others = candidateBoxes,
            captionHeight = captionBox?.height ?: dp(DEFAULT_CAPTION_HEIGHT_DP),
            inset = dp(24),
        )
        val params = captionParams(atTop)
        captionAtTop = atTop
        getSystemService(WindowManager::class.java).updateViewLayout(host, params)
    }

    private fun captionParams(atTop: Boolean): WindowManager.LayoutParams {
        val screen = screenSize()
        val keyboardLift = keyboardTop?.let { (screen.y - it).coerceAtLeast(0) } ?: 0
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            y = if (atTop) dp(36) else dp(24) + keyboardLift
        }
    }

    private fun onCaptionLaidOut(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        captionBox = Box(location[0], location[1], location[0] + view.width, location[1] + view.height)
        updateFloatingAvoid()
    }

    private fun removeCaption() {
        handler.removeCallbacks(captionTick)
        val host = captionHost ?: return
        captionHost = null
        captionView = null
        captionBox = null
        captionAtTop = null
        getSystemService(WindowManager::class.java).removeViewImmediate(host)
    }

    /** 마이크 묶음이 가리면 안 되는 곳: 눌러야 할 곳, 자막, 키보드 */
    private fun updateFloatingAvoid() {
        val screen = screenSize()
        val areas = listOfNotNull(
            highlightedTarget?.box()?.inflate(dp(28)),
            *candidateBoxes.takeIf { highlightedTarget == null }.orEmpty().toTypedArray(),
            captionBox.takeIf { captionHost != null },
            keyboardTop?.let { Box(0, it, screen.x, screen.y) },
        )
        floatingControls.avoid(areas)
    }

    private fun findKeyboardTop(): Int? = try {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { window ->
            Rect().also(window::getBoundsInScreen).top.takeIf { it > 0 }
        }
    } catch (_: SecurityException) {
        null
    }

    // ───────────────────────── 기타 ─────────────────────────

    @Suppress("DEPRECATION")
    private fun activeScreenPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    private fun openInput(sos: Boolean, command: String? = null, foreground: String? = activeScreenPackage()) {
        stopListening(announce = false)
        floatingControls.hide()
        startActivity(
            GuideInputActivity.intent(this, sos, foreground, command).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // ───────────────────────── 화면 위에서 바로 말하기 ─────────────────────────

    private val listener by lazy { VoiceListener(this) }
    /** 말하기를 누른 때의 앱 (들은 말을 이 화면 기준으로 판단) */
    private var listeningForeground: String? = null

    /** 말하기 단추: 듣는 중이 아니면 지금 화면 그대로 듣기 시작, 듣는 중이면 종료 */
    private fun onMicrophone() {
        if (listener.isListening) {
            stopListening(announce = true)
            return
        }
        val foreground = activeScreenPackage()
        if (!listener.canListenInPlace()) {
            // 마이크 권한이 아직 없거나 기기 내 인식이 없으면 예전처럼 말하기 창에서
            openInput(sos = false, foreground = foreground)
            return
        }
        voice?.stop() // 안내 음성이 마이크에 들어가지 않게
        listeningForeground = foreground
        val started = listener.start(object : VoiceListener.Callback {
            override fun onReady() {
                GuideCaption.post(getString(R.string.caption_listening), GuideCaption.Tone.LISTEN, durationMs = LISTEN_CAPTION_MS)
            }

            override fun onPartial(text: String) = GuideCaption.heard(text)

            override fun onFinal(text: String) {
                floatingControls.setListening(false)
                handleSpoken(text, listeningForeground)
            }

            override fun onFailed(error: Int) {
                floatingControls.setListening(false)
                val message = when (error) {
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH -> R.string.caption_no_speech
                    android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_device_unavailable
                    else -> R.string.caption_unrecognized
                }
                GuideCaption.post(getString(message), GuideCaption.Tone.WARN)
            }
        })
        if (started) {
            floatingControls.setListening(true)
        } else {
            openInput(sos = false, foreground = foreground)
        }
    }

    private fun stopListening(announce: Boolean) {
        if (!listener.isListening) return
        listener.stop()
        floatingControls.setListening(false)
        if (announce) GuideCaption.post(getString(R.string.caption_listen_stopped), GuideCaption.Tone.INFO)
    }

    /** 들은 말 처리: 바로 할 수 있는 일은 지금 화면에서, 확인 창이 필요한 일은 말하기 창으로 */
    private fun handleSpoken(spoken: String, foreground: String?) {
        when (val route = CommandRoute.of(spoken, foreground, aiAvailable = HaebomServer.api != null)) {
            CommandRoute.Empty -> GuideCaption.post(getString(R.string.caption_no_speech), GuideCaption.Tone.WARN)
            CommandRoute.Unrecognized -> {
                GuideCaption.heard(spoken)
                GuideCaption.post(getString(R.string.caption_unrecognized), GuideCaption.Tone.WARN)
            }
            CommandRoute.NeedsWindow -> openInput(sos = false, command = spoken, foreground = foreground)
            CommandRoute.Ai -> {
                GuideCaption.post(
                    getString(R.string.caption_heard_task, spoken, getString(R.string.caption_ai_started)),
                    GuideCaption.Tone.INFO,
                )
                kr.haebom.elder.startAiGuide(this, spoken, foreground)
            }
            is CommandRoute.Task -> {
                GuideCaption.post(
                    getString(R.string.caption_heard_task, spoken, getString(route.task.nextStepText())),
                    GuideCaption.Tone.INFO,
                )
                kr.haebom.elder.launchGuide(this, route.task, foreground, announce = false, route.query, spoken)
            }
        }
    }

    private fun captionText(target: Target): String {
        val text = target.caption?.let { captionString(it, target.captionArg) } ?: getString(R.string.caption_here)
        val bounds = target.bounds ?: return text
        val screen = screenSize()
        val columns = listOf(
            listOf(R.string.guide_top_left, R.string.guide_top_center, R.string.guide_top_right),
            listOf(R.string.guide_center_left, R.string.guide_center, R.string.guide_center_right),
            listOf(R.string.guide_bottom_left, R.string.guide_bottom_center, R.string.guide_bottom_right),
        )
        val row = (bounds.centerY() * 3 / screen.y.coerceAtLeast(1)).coerceIn(0, 2)
        val column = (bounds.centerX() * 3 / screen.x.coerceAtLeast(1)).coerceIn(0, 2)
        val position = getString(R.string.guide_location, getString(columns[row][column]))
        return getString(R.string.guide_caption_with_location, text, position)
    }

    private fun captionString(res: Int, arg: String?): String =
        if (arg == null) getString(res) else getString(res, arg)

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun speak(text: String) {
        if (voice == null) voice = GuideVoice(this)
        voice?.speak(text)
    }

    private fun scheduleLater(delayMs: Long) {
        handler.postDelayed({ scheduleRefresh() }, delayMs)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point =
        Point().also { getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(it) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun disconnect() {
        stopListening(announce = false)
        if (connected) DevScreenHook.sink?.onDisconnected()
        connected = false
        if (GuideSession.onChange === sessionChanged) GuideSession.onChange = null
        if (GuideCaption.listener === captionChanged) GuideCaption.listener = null
        handler.removeCallbacksAndMessages(null)
        refreshScheduled = false
        floatingControls.hide()
        clearHighlight()
        removeCaption()
        voice?.stop()
    }

    private data class Target(
        val windowId: Int,
        val packageName: String,
        val bounds: Rect?,
        val caption: Int? = null,
        val captionArg: String? = null,
        /** AI 가 준 문장 (자막은 이 문장을 그대로 씀) */
        val text: String? = null,
    ) {
        fun box(): Box? = bounds?.let { Box(it.left, it.top, it.right, it.bottom) }
    }

    private companion object {
        const val SCREEN_UPDATE_INTERVAL_MS = 500L
        const val LEFT_APP_GRACE_MS = 1_500L
        const val SCROLL_TIP_AFTER_MS = 8_000L
        const val FILL_SETTLE_MS = 700L
        /** AI 가 "다 됐어요"라고 한 뒤 자막을 잠시 보여 주고 안내를 마침 */
        const val AI_DONE_LINGER_MS = 6_000L
        /** "듣고 있어요" 자막 (인식이 끝나면 다른 자막으로 바뀜) */
        const val LISTEN_CAPTION_MS = 15_000L
        const val DEFAULT_CAPTION_HEIGHT_DP = 120
        const val TAG = "HaebomGuide"

        /** 안내 중에 잠깐 떠도 "앱에서 나갔다"고 보지 않는 창 */
        val PASS_THROUGH = setOf(
            "com.android.systemui", "android",
            "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard",
        )
    }
}
