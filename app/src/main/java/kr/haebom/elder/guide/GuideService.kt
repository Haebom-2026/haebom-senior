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
import kr.haebom.elder.family.Assessment
import kr.haebom.elder.family.ElderSettings
import kr.haebom.elder.family.Family
import kr.haebom.elder.family.SensitiveAlerter
import kr.haebom.elder.family.SensitiveApps
import kr.haebom.elder.family.Sensitivity
import kr.haebom.elder.net.HaebomServer
import kr.haebom.elder.ui.ElderUi

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
 *   - 정부24 등 공공 앱(L1) : 보호자 알림 동의 팝업 / 은행·금융 앱(L2) : 경고 자막 + 바로 알림 (같은 일은 5분마다 다시)
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
            onSos = { openSos(activeScreenPackage()) },
            onStop = {
                GuideSession.stop()
                GuideCaption.post(getString(R.string.caption_guide_stopped), GuideCaption.Tone.INFO)
            },
        )
    }
    private var voice: GuideVoice? = null
    /** 비밀번호·인증 화면이라 잠깐 멈춘 상태 (벗어나면 "이어서 안내할게요") */
    private var sensitivePaused = false
    /** AI 상태가 어느 안내의 것인지 (멈춤·이어서는 같은 안내라 답을 그대로 씀) */
    private var aiSerial = -1L
    private val sessionChanged: () -> Unit = {
        if (GuideSession.active == null || GuideSession.serial != aiSerial) {
            resetAi()
            aiSerial = GuideSession.serial
        }
        noTargetSince = 0L
        leftAppSince = 0L
        clearHighlight()
        clearArrow()
        scheduleRefresh()
    }
    private val captionChanged: (GuideCaption.Message?) -> Unit = { renderCaption() }
    /** 들은 말을 지금 앞 화면 기준으로 처리 (시험 진입점이 잠깐 앞에 왔다 사라지므로 조금 뒤에) */
    private val spokenHook: (String) -> Unit = { text ->
        handler.postDelayed({ handleSpoken(text, activeScreenPackage()) }, 700)
    }

    override fun onServiceConnected() {
        connected = true
        GuideSession.onChange = sessionChanged
        GuideCaption.listener = captionChanged
        SpokenCommandHook.handler = spokenHook
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
        clearArrow()
        removeCaption()
        scheduleRefresh()
    }

    override fun onInterrupt() {
        clearHighlight()
        clearArrow()
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
            clearArrow()
            removeCaption()
            return
        }
        keyboardTop = findKeyboardTop()
        val foreground = activeScreenPackage()
        syncFloatingControls(foreground)

        candidateBoxes = emptyList()
        val decision = decide(foreground)
        guideMessage = decision.message
        // 안내 단계: '잘 해요'는 테두리·화살표·자동완성 없이 자막 힌트만
        val level = settings.proficiency
        val shownTarget = decision.target.takeIf { level.highlight != Proficiency.Highlight.NONE }
        if (shownTarget != highlightedTarget) {
            clearHighlight()
            shownTarget?.let(::showHighlight)
        }
        // 짚을 단추가 있으면 빨간 테두리만, 없을 때 이동 방향 화살표
        val arrow = decision.arrow.takeIf { decision.target == null && level.arrows }
        val arrowAt = decision.arrowAt.takeIf { arrow != null }
        if (arrow != shownArrow || arrowAt != shownArrowAt) {
            clearArrow()
            arrow?.let { showArrow(it, arrowAt) }
        }
        // 자동완성 입력 단추: 입력 칸을 짚었을 때만, 그 바로 아래
        val anchor = shownTarget?.bounds
        if (decision.chips.isNotEmpty() && anchor != null && level.autofill) {
            chipAnchor = Rect(anchor)
            autofill.show(decision.chips, anchor)
        } else {
            chipAnchor = null
            autofill.hide()
        }
        renderCaption()
    }

    // ───────────────────────── 자동완성 입력 단추 ─────────────────────────

    private val autofill by lazy { AutofillChips(this, ::onAutofillPicked) }
    private var chipAnchor: Rect? = null

    /** 어르신이 "자동완성 입력"을 누름: 짚어 둔 입력 칸에 그 글자를 넣음 */
    private fun onAutofillPicked(text: String) {
        val anchor = chipAnchor ?: return
        val done = fillEditableAt(anchor, text)
        autofill.hide()
        if (done) {
            GuideSession.markQueryFilled()
            scheduleLater(FILL_SETTLE_MS)
        } else {
            GuideCaption.post(getString(R.string.caption_autofill_failed), GuideCaption.Tone.WARN)
        }
        scheduleRefresh()
    }

    /** [anchor] 자리의 입력 칸(없으면 지금 글자를 쓰는 칸)에 [text] 를 넣음. 비밀번호 칸에는 넣지 않음 */
    @Suppress("DEPRECATION")
    private fun fillEditableAt(anchor: Rect, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            var best: AccessibilityNodeInfo? = null
            var visited = 0
            fun visit(node: AccessibilityNodeInfo): Boolean {
                if (++visited > 1_500) return false
                if (node.isPassword) return false
                if (node.isEditable && node.isVisibleToUser) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (Rect.intersects(bounds, anchor)) {
                        best = AccessibilityNodeInfo.obtain(node)
                        return true
                    }
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try {
                        if (visit(child)) return true
                    } finally {
                        child.recycle()
                    }
                }
                return false
            }
            visit(root)
            val field = best ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }
                ?: return false
            return try {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also {
                    android.util.Log.i(TAG, "자동완성 입력: ${if (it) "성공" else "실패"}")
                }
            } finally {
                field.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * [arrow]: 이동 방향 화살표 (상하좌우 밀기, 홈, 뒤로)
     * [chips]: [target](입력 칸) 바로 아래에 보여 줄 "자동완성 입력" 단추 글자
     */
    private data class Decision(
        val target: Target?,
        val message: Pair<String, GuideCaption.Tone>?,
        val arrow: DirectionHint? = null,
        val chips: List<String> = emptyList(),
        /** (서버 v4 swipe target) 화살표를 그릴 영역 */
        val arrowAt: Rect? = null,
    )

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
        val step = screen?.let {
            task?.findStep(it.controls, it.hasPasswordField, query, it.hasSearchField, active.queryFilled)
        }
        if (captured?.blocked == true || screen?.hasPasswordField == true ||
            step?.caption == R.string.guide_authentication_stop
        ) {
            // 민감 정보는 직접: 표시·AI 없이 잠깐 멈춤 (안내는 끝내지 않음)
            noTargetSince = 0L
            sensitivePaused = true
            return Decision(null, getString(R.string.guide_authentication_stop) to GuideCaption.Tone.WARN)
        }
        if (sensitivePaused) {
            // 직접 입력을 마치고 민감한 입력이 없는 화면으로 넘어옴: 이어서 안내
            sensitivePaused = false
            justAuthenticated = true
            GuideCaption.post(getString(R.string.caption_sensitive_done), GuideCaption.Tone.INFO, durationMs = 3_000)
        }

        // 검색창이 열림 + 아직 검색어를 안 넣음: 검색창을 짚고 아래 "자동완성 입력" 단추 (어르신이 눌러야 넣음).
        // 앱이 확실히 아는 화면이라 AI 를 부르지 않음
        val field = screen?.searchFieldBounds
        if (step != null && step.chips.isNotEmpty() && field != null) {
            noTargetSince = 0L
            val say = captionString(step.caption, step.arg)
            val target = Target(windowId, foreground, Rect(field), text = say)
            return Decision(target, say to GuideCaption.Tone.GUIDE, chips = step.chips)
        }

        // 목적지에 도착했으면 서버를 더 부르지 않고 마침
        goalReached(active, foreground, step)?.let { return finishGuide(it) }

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
        var arrow: DirectionHint? = null
        if (screen.hasScrollable && SystemClock.uptimeMillis() - noTargetSince > SCROLL_TIP_AFTER_MS) {
            text += " " + getString(R.string.caption_scroll_tip)
            arrow = DirectionHint.UP // 아래 내용을 보려면 위로 밀기
        } else if (screen.hasScrollable) {
            scheduleLater(SCROLL_TIP_AFTER_MS)
        }
        return Decision(null, text to GuideCaption.Tone.GUIDE, arrow)
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
    /** AI 가 방금 되물었음 ("어떤 영상을 찾을까요?"): 다음에 들은 말은 그 대답으로 이어 감 */
    private var aiAsked = false
    /** 아직 읽지 않은 AI 의 물음 (읽고 나면 자동으로 듣기) */
    private var aiAskSay: String? = null
    private var aiDoneScheduled = false
    /** 밀기 안내 중 찾는 글자 (화면에 나타나면 서버를 다시 부르지 않고 바로 짚음) */
    private var aiSeek: String? = null
    /** 찾는 글자를 짚은 화면 (다른 화면으로 넘어가면 찾기를 끝냄) */
    private var aiSeekFoundOn: String? = null
    /** 마지막 밀기 화살표 (새 화면 답을 기다리는 동안에도 유지해서 깜빡이지 않게) */
    private var aiLastSwipe: DirectionHint? = null

    private fun resetAi() {
        aiPending = null
        aiAnswer = null
        aiFailedFingerprint = null
        aiFailedSay = null
        aiHistory.clear()
        aiDoneScheduled = false
        aiAsked = false
        aiAskSay = null
        sensitivePaused = false
        aiSeenFingerprint = null
        aiSeek = null
        aiSeekFoundOn = null
        aiLastSwipe = null
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

        // 밀다가 찾던 글자가 나타나면 바로 짚음 (서버를 부르지 않음)
        seekDecision(captured, windowId, foreground)?.let { return it }

        // 화면이 바뀌는 중(빈 화면)이거나 막 바뀌었으면 잠시 기다렸다가 물음 (같은 화면을 두 번 묻지 않게)
        val now = SystemClock.uptimeMillis()
        if (fingerprint != aiSeenFingerprint) {
            aiSeenFingerprint = fingerprint
            aiSeenAt = now
        }
        val settled = now - aiSeenAt >= AI_SETTLE_MS
        if (captured.nodes.isNotEmpty() && settled && aiPending?.fingerprint != fingerprint) {
            requestAi(active, captured, fingerprint)
        } else if (!settled) {
            scheduleLater(AI_SETTLE_MS)
        }
        return Decision(null, getString(R.string.caption_ai_waiting) to GuideCaption.Tone.INFO, aiLastSwipe)
    }

    /** 마지막으로 본 화면 지문과 처음 본 시각 (화면이 멈췄는지 판단) */
    private var aiSeenFingerprint: String? = null
    private var aiSeenAt = 0L

    /** "다 됐어요" 자막을 잠시 보여 주고(읽어 주고) 안내를 마침 */
    private fun finishGuide(say: String): Decision {
        if (!aiDoneScheduled) {
            aiDoneScheduled = true
            settings.countDone()
            val serial = GuideSession.serial
            handler.postDelayed({
                if (GuideSession.serial == serial) {
                    GuideSession.stop()
                    maybeSuggestLessGuidance()
                }
            }, AI_DONE_LINGER_MS)
        }
        return Decision(null, say to GuideCaption.Tone.DONE)
    }

    private fun proficiencyName(level: Proficiency): Int = when (level) {
        Proficiency.BEGINNER -> R.string.level_beginner
        Proficiency.FAMILIAR -> R.string.level_familiar
        Proficiency.EXPERT -> R.string.level_expert
    }

    private fun proficiencyHelp(level: Proficiency): Int = when (level) {
        Proficiency.BEGINNER -> R.string.level_beginner_help
        Proficiency.FAMILIAR -> R.string.level_familiar_help
        Proficiency.EXPERT -> R.string.level_expert_help
    }

    /**
     * (v4) 서버가 "여기서 멈춤"(송금 확정·본인인증·가짜 사이트·3번 실패): 이 화면에서는 짚지 않고 주의 문장만.
     * 안내는 끝내지 않음 — 어르신이 직접 하고 화면이 바뀌면 이어서 안내 (같은 화면이면 서버를 다시 부르지 않음)
     */
    private fun stopForSafety(say: String): Decision = Decision(null, say to GuideCaption.Tone.WARN)

    /** 지금 단계에서 안내를 여러 번 끝까지 마쳤으면 "안내를 줄여 볼까요?" 한 번 물어봄 */
    private fun maybeSuggestLessGuidance() {
        val level = settings.proficiency
        val next = level.next ?: return
        if (!Proficiency.shouldSuggest(level, settings.doneAtLevel, settings.suggestedAt(level))) return
        settings.markSuggested(level)
        val nextName = getString(proficiencyName(next))
        card.show(
            key = "level-up",
            title = getString(R.string.levelup_title),
            message = getString(R.string.levelup_message, Proficiency.SUGGEST_AFTER_DONE, nextName),
            detail = getString(proficiencyHelp(next)),
            actions = listOf(
                OverlayCard.Action(getString(R.string.levelup_yes, nextName)) {
                    settings.proficiency = next
                    GuideCaption.post(getString(R.string.levelup_done, nextName), GuideCaption.Tone.DONE)
                },
                OverlayCard.Action(getString(R.string.levelup_no), ElderUi.Kind.QUIET) {
                    GuideCaption.post(getString(R.string.levelup_kept), GuideCaption.Tone.INFO)
                },
            ),
            accent = ElderUi.GREEN,
        )
    }

    /**
     * 앱이 직접 알 수 있는 도착: 끝났다는 문장, 아니면 null.
     * - "카카오톡 열어 줘" → 지금 화면이 그 앱
     * - "유튜브에 임영웅 검색해 줘" → 그 검색어의 결과 화면
     */
    private fun goalReached(active: GuideSession.Active, foreground: String, step: GuideStep?): String? {
        val query = active.query
        if (query != null && step?.caption == R.string.guide_youtube_results) {
            return getString(R.string.caption_done_search, query)
        }
        val app = active.slots["app"]
        if (active.goal == GuideTask.GOAL_OPEN_APP && app != null && GuideTask.isSameApp(app, appLabel(foreground))) {
            return getString(R.string.caption_done_open_app, appLabel(foreground))
        }
        return null
    }

    private fun seekDecision(captured: CapturedScreen, windowId: Int, foreground: String): Decision? {
        val seek = aiSeek ?: return null
        val foundOn = aiSeekFoundOn
        if (foundOn != null && foundOn != captured.fingerprint) {
            // 찾은 것을 누르고 다음 화면으로 넘어감: 찾기 끝
            aiSeek = null
            aiSeekFoundOn = null
            aiLastSwipe = null
            return null
        }
        val b = DirectionHint.findSeek(seek, captured.nodes)?.bounds ?: return null
        aiSeekFoundOn = captured.fingerprint
        aiLastSwipe = null
        val say = getString(R.string.caption_seek_found, seek)
        return Decision(Target(windowId, foreground, Rect(b[0], b[1], b[2], b[3]), text = say), say to GuideCaption.Tone.GUIDE)
    }

    private fun requestAi(active: GuideSession.Active, captured: CapturedScreen, fingerprint: String) {
        val client = ai ?: return
        val size = screenSize()
        val screenJson = ScreenPayload.screenObject(captured.packageName, size.x, size.y, captured.nodes) ?: return
        val state = stateSlot(captured)
        val fields = captured.nodes.filter { it.editable }
        android.util.Log.i(
            TAG,
            "AI 요청: 노드 ${captured.nodes.size}개, 입력 칸 ${fields.size}개(채워짐 ${fields.count { it.filled }}), 상태='${state[STATE_SLOT].orEmpty()}'",
        )
        val id = client.request(
            settings.proficiency.aiMode,
            active.goal, active.utterance, active.slots + (SAFETY_SLOT to SAFETY_NOTE) + (FORM_SLOT to FORM_NOTE) + state, screenJson, aiHistory.toList(), settings.deviceId, GuideSession.consumeUserReply(),
        ) { requestId, outcome -> onAiOutcome(requestId, fingerprint, outcome) }
        aiPending = AiPending(id, fingerprint)
    }

    /** 화면 상태 요약 (로그인·입력 중인 칸·못 누르는 단추·키보드·방금 인증) → slots.state */
    private fun stateSlot(captured: CapturedScreen): Map<String, String> {
        val summary = ScreenState.summary(captured.nodes, keyboardOpen = keyboardTop != null, justAuthenticated = justAuthenticated)
        justAuthenticated = false
        return if (summary.isEmpty()) emptyMap() else mapOf(STATE_SLOT to summary)
    }

    /** 비밀번호·인증 화면을 막 벗어남 (다음 AI 요청에 한 번 알림) */
    private var justAuthenticated = false

    private fun onAiOutcome(requestId: Int, fingerprint: String, outcome: AiOutcome) {
        // 더 새 요청이 있거나 안내가 바뀌었으면 버림
        if (aiPending?.requestId != requestId) return
        aiPending = null
        val reply = (outcome as? AiOutcome.Reply)?.reply
        val knownTask = GuideSession.active?.task != null
        // 앱이 아는 일에서 막연히 되묻기만 하면(예: 정부24 "무엇을 도와드릴까요?") 앱의 안내로 계속.
        // 화면 내용을 묻는 질문(로그인 뒤 "주소가 같나요?")은 그대로 읽고 대답을 들음
        val genericAskInKnownTask = knownTask && reply?.isGenericAsk == true
        aiAsked = reply?.type == AiReply.ASK && !genericAskInKnownTask
        aiAskSay = reply?.say?.takeIf { aiAsked && it.isNotBlank() }
        if (reply == null || reply.isFallback || genericAskInKnownTask) {
            aiFailedFingerprint = fingerprint
            aiFailedSay = reply?.say?.takeUnless { reply.type == AiReply.ASK }
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
        if (reply.type == AiReply.SWIPE) {
            // 밀기: 화살표 + 찾는 글자 기억 (이미 화면에 있으면 바로 짚음)
            aiSeek = reply.seekText
            aiSeekFoundOn = null
            aiLastSwipe = DirectionHint.fromSwipe(reply.dir)
            seekDecision(captured, windowId, foreground)?.let { return it }
            // (v4) 밀 영역이 있으면 화살표를 그 자리에
            val area = AiReply.findTarget(reply, captured.nodes)?.bounds?.let { Rect(it[0], it[1], it[2], it[3]) }
            return Decision(null, say to GuideCaption.Tone.GUIDE, aiLastSwipe, arrowAt = area)
        }
        aiSeek = null
        aiSeekFoundOn = null
        aiLastSwipe = null
        return when {
            reply.type == AiReply.DONE -> finishGuide(say)
            reply.isStopped -> stopForSafety(say)
            else -> applyAiStep(reply, say, captured, windowId, foreground)
        }
    }

    private fun applyAiStep(reply: AiReply, say: String, captured: CapturedScreen, windowId: Int, foreground: String): Decision {
        return when (reply.type) {
            AiReply.HIGHLIGHT, AiReply.SUGGEST -> {
                val node = AiReply.findTarget(reply, captured.nodes)
                val b = node?.bounds
                if (b == null) {
                    // 짚으라는 요소가 지금 화면에 없음: 다시 물어봄
                    aiAnswer = null
                    scheduleRefresh()
                    Decision(null, getString(R.string.caption_ai_waiting) to GuideCaption.Tone.INFO)
                } else {
                    val target = Target(windowId, foreground, Rect(b[0], b[1], b[2], b[3]), text = say)
                    // 입력 칸을 짚으면 아래에 자동완성 입력 단추 (말한 검색어 + AI 추천어)
                    val chips = AutofillPlan.chips(reply, node, GuideSession.active)
                    Decision(target, say to GuideCaption.Tone.GUIDE, chips = chips)
                }
            }
            AiReply.ASK -> {
                // (v4) 답 단추 + "모르겠어요". 말로 대답해도 됨
                if (reply.options.isNotEmpty()) showAskOptions(reply)
                Decision(null, say to GuideCaption.Tone.GUIDE)
            }
            // 말 안내: "홈으로 가세요" → 홈 화살표, "뒤로 가세요" → 뒤로 화살표
            else -> Decision(null, say to GuideCaption.Tone.GUIDE, DirectionHint.from(reply))
        }
    }

    /** 지금 보여 주는 AI 질문의 답 단추들 (말로 대답할 때 맞춰 봄) */
    private var askOptions: List<String> = emptyList()

    private fun showAskOptions(reply: AiReply) {
        askOptions = reply.options + getString(R.string.ask_unknown)
        card.show(
            key = "ask:${reply.requestId}",
            title = getString(R.string.ask_title),
            message = reply.say,
            actions = askOptions.mapIndexed { index, option ->
                OverlayCard.Action(option, if (index == 0) ElderUi.Kind.PRIMARY else ElderUi.Kind.SECONDARY) {
                    stopListening(announce = false)
                    answerAsk(option)
                }
            },
        )
    }

    /** AI 질문에 대답: 같은 안내를 이어 가고, 다음 요청에 userReply·history 로 보냄 */
    private fun answerAsk(reply: String) {
        askOptions = emptyList()
        card.hide()
        // 지난 질문(같은 화면의 ask 답)을 지워 대답을 담아 다시 묻게 함
        aiAnswer = null
        aiAsked = false
        aiAskSay = null
        GuideCaption.post(getString(R.string.caption_answer_taken, reply), GuideCaption.Tone.INFO)
        GuideSession.answer(reply)
        aiHistory.addLast(getString(R.string.history_elder_answer, reply))
        while (aiHistory.size > ScreenPayload.MAX_HISTORY) aiHistory.removeFirst()
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

    /**
     * 민감 앱에 들어왔을 때 (단계는 [Sensitivity.fromApp]).
     * - L1 공공(정부24 등): "보호자에게 알릴까요?" 카드([ConsentCard], 다른 앱 위에 떠서 앱이 덮지 못함). 설정에서 끌 수 있음
     * - L2 금융: 막지 않고 경고 자막 + 동의 없이 바로 보호자 알림
     * 같은 일은 5분마다 다시 (들어올 때 5분이 지났으면 다시 경고·질문).
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
                ConsentPrompt.show(card, this, pkg, appLabel(pkg), assessment.category)
            }
        }
    }

    private val card by lazy { OverlayCard(this) }

    // ───────────────────────── 오버레이 ─────────────────────────

    private fun syncFloatingControls(activePackage: String?) {
        if (activePackage != null && activePackage != packageName &&
            activePackage != "com.android.systemui" && activePackage != "android"
        ) {
            floatingControls.setSosVisible(settings.sosEnabled)
            floatingControls.setGuiding(GuideSession.active != null)
            syncMicMode()
            floatingControls.show()
        } else {
            floatingControls.hide()
        }
    }

    private fun showHighlight(target: Target) {
        val light = settings.proficiency.highlight == Proficiency.Highlight.LIGHT
        val view = GuideHighlightView(this, target.bounds, getString(R.string.caption_here), light)
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
        card.bringToFront()
    }

    private fun clearHighlight() {
        autofill.hide() // 자동완성 단추는 짚은 입력 칸에 붙어 있음
        val view = highlight ?: return
        highlight = null
        highlightedTarget = null
        getSystemService(WindowManager::class.java).removeViewImmediate(view)
    }

    private var arrowView: View? = null
    private var shownArrow: DirectionHint? = null

    private var shownArrowAt: Rect? = null

    private fun showArrow(hint: DirectionHint, at: Rect? = null) {
        val label = getString(
            when (hint) {
                DirectionHint.UP -> R.string.arrow_up
                DirectionHint.DOWN -> R.string.arrow_down
                DirectionHint.LEFT -> R.string.arrow_left
                DirectionHint.RIGHT -> R.string.arrow_right
                DirectionHint.HOME -> R.string.arrow_home
                DirectionHint.BACK -> R.string.arrow_back
            },
        )
        val view = DirectionArrowView(this, hint, label, at?.centerX(), at?.centerY())
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
            return
        }
        arrowView = view
        shownArrow = hint
        shownArrowAt = at
        captionHost?.let { reattachCaption() }
        floatingControls.bringToFront()
        card.bringToFront()
    }

    private fun clearArrow() {
        val view = arrowView ?: return
        arrowView = null
        shownArrow = null
        shownArrowAt = null
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
            if (message.first == aiAskSay) {
                // AI 의 물음: 다 읽은 뒤 바로 대답을 들음 (단추를 누르지 않아도 되게)
                aiAskSay = null
                val serial = GuideSession.serial
                android.util.Log.i(TAG, "AI 물음 읽기 → 끝나면 듣기")
                speak(message.first) {
                    android.util.Log.i(TAG, "AI 물음 다 읽음 (asked=$aiAsked, listening=${listener.isListening})")
                    if (GuideSession.serial == serial && aiAsked && !listener.isListening) {
                        startListening(fallbackToWindow = false)
                    }
                }
            } else if (message.first != getString(R.string.caption_ai_waiting) &&
                (settings.proficiency.speakSteps || message.second == GuideCaption.Tone.WARN)
            ) {
                // '잘 해요' 단계는 매 단계는 조용히(자막만), 주의는 읽어 줌
                speak(message.first)
            }
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
        card.bringToFront()
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

    /** 마이크 단추 모양: 듣는 중 > 안내 중(멈추기/이어서) > 말하기 */
    private fun syncMicMode() {
        val active = GuideSession.active
        floatingControls.setMicMode(
            when {
                listener.isListening -> FloatingGuideControls.MicMode.LISTENING
                active == null -> FloatingGuideControls.MicMode.SPEAK
                else -> FloatingGuideControls.MicMode.SPEAK_AGAIN
            },
        )
    }

    /**
     * 마이크 단추
     * - 듣는 중: 듣기 종료
     * - 그 밖(안내 중이면 "다시 말하기"): 지금 화면 그대로 듣기 시작
     */
    private fun onMicrophone() {
        if (listener.isListening) {
            stopListening(announce = true)
            return
        }
        startListening(fallbackToWindow = true)
    }

    /** 지금 화면 그대로 듣기 시작. [fallbackToWindow] 면 바로 들을 수 없을 때 말하기 창을 엶 */
    private fun startListening(fallbackToWindow: Boolean) {
        val foreground = activeScreenPackage()
        if (!listener.canListenInPlace()) {
            if (!fallbackToWindow) return
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
                syncMicMode()
                handleSpoken(text, listeningForeground)
            }

            override fun onFailed(error: Int) {
                syncMicMode()
                val message = when (error) {
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH -> R.string.caption_no_speech
                    android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_device_unavailable
                    else -> R.string.caption_unrecognized
                }
                // 예/아니요를 묻는 중이면 카드는 그대로, 단추로 고르시게
                GuideCaption.post(getString(if (pendingConfirm != null) R.string.confirm_not_heard else message), GuideCaption.Tone.WARN)
            }
        })
        if (started) {
            syncMicMode()
        } else if (fallbackToWindow) {
            openInput(sos = false, foreground = foreground)
        }
    }

    private fun stopListening(announce: Boolean) {
        if (!listener.isListening) return
        listener.stop()
        syncMicMode()
        if (announce) GuideCaption.post(getString(R.string.caption_listen_stopped), GuideCaption.Tone.INFO)
    }

    /**
     * 들은 말 처리: 모두 지금 화면 그대로. 확인이 필요한 일(사기 의심·금융·비밀번호·정부24)도
     * 해봄 앱 화면으로 넘어가지 않고 떠 있는 카드([OverlayCard])로 물음.
     */
    private fun handleSpoken(spoken: String, foreground: String?) {
        // "이렇게 할까요?" 물은 뒤의 짧은 대답: 예면 그 일을, 아니요면 취소. 그 밖의 말은 새 요청
        pendingChoices?.let { choices ->
            Confirm.pick(spoken, choices.keys.toList())?.let { where ->
                pendingChoices = null
                pendingConfirm = null
                pendingChoices = null
                card.hide()
                choices.getValue(where)()
                return
            }
        }
        pendingConfirm?.let { pending ->
            if (spoken.isBlank()) {
                // 대답을 못 들음: 카드는 그대로 두고 단추로 고르시게
                GuideCaption.post(getString(R.string.confirm_not_heard), GuideCaption.Tone.INFO)
                return
            }
            when (Confirm.answer(spoken)) {
                Confirm.Answer.YES -> {
                    pendingConfirm = null
                    pendingChoices = null
                    card.hide()
                    pending()
                    return
                }
                Confirm.Answer.NO -> {
                    pendingConfirm = null
                    pendingChoices = null
                    card.hide()
                    GuideCaption.post(getString(R.string.confirm_declined), GuideCaption.Tone.INFO)
                    return
                }
                Confirm.Answer.OTHER -> {
                    pendingConfirm = null
                    pendingChoices = null
                    card.hide()
                }
            }
        }
        val route = CommandRoute.of(spoken, foreground, aiAvailable = HaebomServer.api != null)
        // AI 가 되물은 뒤의 말은 새 일이 아니라 그 대답: 같은 안내를 이어 감
        if (GuideSession.active != null && aiAsked && (route == CommandRoute.Ai || route == CommandRoute.Unrecognized)) {
            // 답 단추가 있으면 말한 것과 맞는 단추로 ("네" → "네", "몰라" → "모르겠어요")
            val picked = AskAnswer.match(spoken, askOptions, getString(R.string.ask_unknown))
            answerAsk(picked ?: spoken)
            return
        }
        when (route) {
            CommandRoute.Empty -> GuideCaption.post(getString(R.string.caption_no_speech), GuideCaption.Tone.WARN)
            CommandRoute.Unrecognized -> {
                GuideCaption.heard(spoken)
                GuideCaption.post(getString(R.string.caption_unrecognized), GuideCaption.Tone.WARN)
            }
            is CommandRoute.Scam -> warnScam(route.assessment, foreground)
            CommandRoute.Help -> openSos(foreground)
            CommandRoute.Private -> {
                // 안내는 끝내지 않음: 비밀번호 칸이 있는 동안은 표시를 숨기고, 직접 입력하고 넘어가면 이어서 안내
                GuideCaption.post(getString(R.string.caption_private), GuideCaption.Tone.WARN, durationMs = 10_000)
                card.show(
                    key = "private",
                    title = getString(R.string.alert_private_title),
                    message = getString(R.string.guide_authentication_stop),
                    actions = listOf(OverlayCard.Action(getString(R.string.ok)) {}),
                )
            }
            is CommandRoute.Finance -> warnFinance(spoken, route.assessment, foreground)
            // 분명한 말은 묻지 않고 바로
            CommandRoute.Gov24 -> {
                GuideCaption.post(getString(R.string.guide_gov24_limited), GuideCaption.Tone.INFO)
                kr.haebom.elder.launchGuide(this, GuideTask.GOV24, foreground, announce = false, null, spoken)
            }
            is CommandRoute.Task -> {
                GuideCaption.post(
                    getString(R.string.caption_heard_task, spoken, getString(route.task.nextStepText())),
                    GuideCaption.Tone.INFO,
                )
                kr.haebom.elder.launchGuide(this, route.task, foreground, announce = false, route.query, spoken)
            }
            // 애매한 말은 짐작한 뜻을 예/아니요로 물음
            is CommandRoute.WebSearch -> openWebSearch(route.web)
            is CommandRoute.MapSearch -> openMapSearch(route.query)
            is CommandRoute.Guess -> askWhere(spoken, route.query, route.options, foreground)
            CommandRoute.Ai -> confirmAi(spoken, foreground)
        }
    }

    // ───────────────────────── "이렇게 할까요?" 확인 ─────────────────────────

    /** 예를 기다리는 일 (말로 "네" 해도, 카드의 [예]를 눌러도 실행) */
    private var pendingConfirm: (() -> Unit)? = null
    /** 고르기 카드: 말로 "인터넷", "지도" 라고 골라도 됨 */
    private var pendingChoices: Map<Search.Where, () -> Unit>? = null

    // ───────────────────────── 검색 (인터넷·지도·유튜브) ─────────────────────────

    /** 인터넷 검색: 크롬으로 네이버(또는 구글) 검색 결과를 바로 엶 */
    private fun openWebSearch(web: Search.Web) {
        GuideSession.stop()
        try {
            startActivity(kr.haebom.elder.Browser.intent(packageManager, Search.webUrl(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GuideCaption.post(getString(R.string.caption_web_search, web.query), GuideCaption.Tone.DONE)
        } catch (_: android.content.ActivityNotFoundException) {
            GuideCaption.post(getString(R.string.guide_app_unavailable), GuideCaption.Tone.WARN)
        }
    }

    /** 주변 장소: 깔린 지도 앱(네이버 지도 → 카카오맵 → 구글 지도 → 웹)에서 내 위치 주변으로 검색. 위치는 지도 앱이 씀 */
    private fun openMapSearch(query: String) {
        GuideSession.stop()
        for ((url, pkg) in Search.mapUrls(query, packageName)) {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) {
                if (packageManager.getLaunchIntentForPackage(pkg) == null) continue
                intent.setPackage(pkg)
            }
            try {
                startActivity(intent)
                GuideCaption.post(getString(R.string.caption_map_search, query), GuideCaption.Tone.DONE)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // 다음 지도 앱으로
            }
        }
        GuideCaption.post(getString(R.string.guide_app_unavailable), GuideCaption.Tone.WARN)
    }

    /** 애매한 검색 말: 어디서 찾을지 고르게 함 (가능성 높은 곳이 맨 위, 말로 "네"면 맨 위) */
    private fun askWhere(spoken: String, query: String, options: List<Search.Where>, foreground: String?) {
        val actions: Map<Search.Where, () -> Unit> = options.associateWith { where ->
            when (where) {
                Search.Where.YOUTUBE -> {
                    {
                        GuideCaption.post(getString(GuideTask.YOUTUBE.nextStepText()), GuideCaption.Tone.INFO)
                        kr.haebom.elder.launchGuide(this, GuideTask.YOUTUBE, foreground, announce = false, query, spoken)
                        Unit
                    }
                }
                Search.Where.WEB -> {
                    { openWebSearch(Search.Web(query, Search.Engine.NAVER)) }
                }
                Search.Where.MAP -> {
                    { openMapSearch(query) }
                }
            }
        }
        val first = actions.getValue(options.first())
        pendingConfirm = first
        pendingChoices = actions
        fun label(where: Search.Where): String = getString(
            when (where) {
                Search.Where.YOUTUBE -> R.string.choice_youtube
                Search.Where.WEB -> R.string.choice_web
                Search.Where.MAP -> R.string.choice_map
            },
        )
        card.show(
            key = "where:$query",
            title = getString(R.string.confirm_title, spoken),
            message = getString(R.string.choice_where, query),
            actions = options.map { where ->
                OverlayCard.Action(label(where), if (where == options.first()) ElderUi.Kind.PRIMARY else ElderUi.Kind.SECONDARY) {
                    pendingConfirm = null
                    pendingChoices = null
                    stopListening(announce = false)
                    actions.getValue(where)()
                }
            } + OverlayCard.Action(getString(R.string.cancel), ElderUi.Kind.QUIET) {
                pendingConfirm = null
                pendingChoices = null
                stopListening(announce = false)
                GuideCaption.post(getString(R.string.confirm_declined), GuideCaption.Tone.INFO)
            },
        )
        val question = getString(R.string.choice_where_spoken, query, label(options.first()))
        GuideCaption.post(question, GuideCaption.Tone.INFO, speak = false)
        voice?.stop()
        speak(question) {
            if (pendingConfirm === first && !listener.isListening) startListening(fallbackToWindow = false)
        }
    }

    /**
     * 알아들은 뜻을 먼저 묻고 예일 때만 [action]. 질문을 읽어 준 뒤 마이크를 켜서 "네/아니요" 대답도 받음.
     * [detail] 은 카드에 덧붙이는 설명.
     */
    private fun confirmThen(spoken: String, question: String, detail: String? = null, action: () -> Unit) {
        pendingChoices = null
        pendingConfirm = action
        card.show(
            key = "confirm:$question",
            title = getString(R.string.confirm_title, spoken),
            message = question,
            detail = detail,
            actions = listOf(
                OverlayCard.Action(getString(R.string.confirm_yes)) {
                    pendingConfirm = null
                    pendingChoices = null
                    stopListening(announce = false)
                    action()
                },
                OverlayCard.Action(getString(R.string.confirm_no), ElderUi.Kind.QUIET) {
                    pendingConfirm = null
                    pendingChoices = null
                    stopListening(announce = false)
                    GuideCaption.post(getString(R.string.confirm_declined), GuideCaption.Tone.INFO)
                },
            ),
        )
        // 자막에는 띄우되 읽기는 여기서: 다 읽으면 바로 "네/아니요"를 들음
        GuideCaption.post(question, GuideCaption.Tone.INFO, speak = false)
        voice?.stop()
        speak(question) {
            if (pendingConfirm === action && !listener.isListening) startListening(fallbackToWindow = false)
        }
    }

    /**
     * 앱이 모르는 일. "○○ 열어 줘" 이고 폰에 그 앱이 있으면 분명한 말이라 묻지 않고 바로 엶
     * (홈 화면에서 아이콘을 찾는 안내보다 빠름). 그 밖에는 애매하니 화면을 보며 안내할지 물음.
     */
    private fun confirmAi(spoken: String, foreground: String?) {
        val app = GuideTask.appSlot(spoken)?.let { name -> Confirm.findApp(name, launcherApps()) }
        val launch = app?.let { packageManager.getLaunchIntentForPackage(it.second) }
        if (app != null && launch != null) {
            GuideSession.stop()
            GuideCaption.post(getString(R.string.caption_done_open_app, app.first), GuideCaption.Tone.DONE)
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        confirmThen(spoken, getString(R.string.confirm_ai_guide)) {
            GuideCaption.post(getString(R.string.caption_ai_started), GuideCaption.Tone.INFO)
            kr.haebom.elder.startAiGuide(this, spoken, foreground)
        }
    }

    /** 폰에 깔린(홈 화면에 보이는) 앱들의 (이름, 패키지) */
    private fun launcherApps(): List<Pair<String, String>> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(home, 0).map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
    }.getOrDefault(emptyList())

    /** L3 사기 의심: 묻지 않고 바로 보호자 알림, 안내 멈춤, 지금 화면 위에 경고 카드 */
    private fun warnScam(assessment: Assessment, foreground: String?) {
        GuideSession.stop()
        GuideCaption.post(getString(R.string.alert_scam), GuideCaption.Tone.WARN, durationMs = 12_000)
        val pkg = foreground?.takeIf { it != packageName }.orEmpty()
        SensitiveAlerter.send(this, assessment, if (pkg.isEmpty()) "" else appLabel(pkg), pkg)
        card.show(
            key = "scam",
            title = getString(R.string.alert_scam_title),
            message = getString(R.string.alert_scam),
            actions = listOf(
                OverlayCard.Action(getString(R.string.sos), ElderUi.Kind.DANGER) { openSos(foreground) },
                OverlayCard.Action(getString(R.string.alert_stop), ElderUi.Kind.QUIET) {},
            ),
            accent = ElderUi.RED,
        )
    }

    /** L2 금융: 막지 않음. 바로 보호자 알림(5분에 한 번) + 경고 카드, [계속하기]면 말한 은행 앱을 엶 */
    private fun warnFinance(spoken: String, assessment: Assessment, foreground: String?) {
        GuideSession.stop() // 하던 안내는 끝내고, [계속하기]를 누르면 금융 안내를 새로 시작
        val bankPackage = Sensitivity.financeAppFor(spoken)?.takeIf { packageManager.getLaunchIntentForPackage(it) != null }
        val target = bankPackage ?: foreground?.takeIf { SensitiveApps.category(it) == SensitiveApps.CATEGORY_FINANCE }
        if (SensitiveAlerter.claim(SensitiveAlerter.keyFor(assessment, target))) {
            SensitiveAlerter.send(this, assessment, target?.let(::appLabel).orEmpty(), target.orEmpty())
        }
        GuideCaption.post(getString(R.string.alert_finance_command), GuideCaption.Tone.WARN, durationMs = 10_000)
        card.show(
            key = "finance",
            title = getString(R.string.alert_finance_title),
            message = getString(R.string.alert_finance_command),
            actions = listOf(
                OverlayCard.Action(getString(R.string.alert_continue)) {
                    // 계속하기: 은행 앱을 열고 화면 안내도 시작 (비밀번호·인증은 직접, 끝나면 이어서)
                    val appName = target?.let(::appLabel)
                    GuideSession.startAi(spoken, GuideTask.GOAL_BANK, appName?.let { mapOf("app" to it) }.orEmpty())
                    val launch = bankPackage?.let { packageManager.getLaunchIntentForPackage(it) }
                    when {
                        launch != null -> startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        target == null -> GuideCaption.post(getString(R.string.alert_open_bank), GuideCaption.Tone.INFO)
                    }
                    GuideCaption.post(getString(R.string.caption_finance_guide), GuideCaption.Tone.INFO)
                },
                OverlayCard.Action(getString(R.string.alert_stop), ElderUi.Kind.QUIET) {},
            ),
            accent = ElderUi.RED,
        )
    }

    /**
     * 도움 요청 문자. 보호자 번호가 하나면 지금 화면에서 바로 문자 앱을 엶 (보내기는 어르신이 누름).
     * 여러 명이라 고르거나 번호를 넣어야 하면 그때만 말하기 창의 SOS 화면을 엶.
     */
    private fun openSos(foreground: String?) {
        GuideSession.stop()
        val contacts = settings.sosContacts(Family.gateway?.links().orEmpty())
        val single = contacts.singleOrNull()
        if (single == null) {
            openInput(sos = true, foreground = foreground)
            return
        }
        GuideCaption.post(getString(R.string.caption_sos), GuideCaption.Tone.INFO)
        val sms = Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", ElderSettings.normalizePhone(single.phone), null))
            .putExtra("sms_body", getString(R.string.sos_message))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(sms)
        } catch (_: android.content.ActivityNotFoundException) {
            GuideCaption.post(getString(R.string.sos_sms_unavailable), GuideCaption.Tone.WARN)
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

    private fun speak(text: String, done: (() -> Unit)? = null) {
        if (voice == null) voice = GuideVoice(this)
        voice?.speak(text, done)
    }

    private fun scheduleLater(delayMs: Long) {
        handler.postDelayed({ scheduleRefresh() }, delayMs)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point =
        Point().also { getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(it) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun disconnect() {
        card.hide()
        stopListening(announce = false)
        if (connected) DevScreenHook.sink?.onDisconnected()
        connected = false
        if (GuideSession.onChange === sessionChanged) GuideSession.onChange = null
        if (GuideCaption.listener === captionChanged) GuideCaption.listener = null
        if (SpokenCommandHook.handler === spokenHook) SpokenCommandHook.handler = null
        handler.removeCallbacksAndMessages(null)
        refreshScheduled = false
        floatingControls.hide()
        clearHighlight()
        clearArrow()
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
        /**
         * AI 요청마다 함께 보내는 원칙 (slots 값 100자 이내). 서버 계약상 slots 는 말에서 뽑은 값이지만,
         * 모델이 함께 읽으므로 "직접 입력한 뒤에는 이어서 안내" 원칙을 알려 줌.
         */
        const val SAFETY_SLOT = "rule"
        const val SAFETY_NOTE = "비밀번호·인증·결제 확정은 어르신이 직접 함. 입력이 끝나 민감한 입력이 없는 화면이면 이어서 다음 단계를 안내"
        /** 신청서처럼 칸이 여럿인 화면에서의 원칙 (100자 이내) */
        const val STATE_SLOT = "state"
        const val FORM_SLOT = "form"
        const val FORM_NOTE = "이미 입력·선택된 항목은 넘어가고 빈 항목만 안내. 미리 채워진 값이 맞는지 확인이 꼭 필요할 때만 한 번 물어봄"
        /** 화면이 이만큼 그대로일 때 AI 에 물음 */
        const val AI_SETTLE_MS = 700L
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
