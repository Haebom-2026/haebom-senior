package kr.haebom.elder.family

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import kr.haebom.elder.GuideInputActivity
import kr.haebom.elder.R
import kr.haebom.elder.guide.GuideCaption
import kr.haebom.elder.ui.ElderUi

/**
 * 가족의 도전 화면 (S4 제안). 한 화면에 도전 하나, 큰 글씨, 단추는 두 개까지.
 * 1. 받기: "{호칭}이 도전을 보냈어요" + 도전 이름 + 도움말 → [해 볼게요] / "다음에 할게요"
 * 2. 하는 중: 도전 이름·도움말 → [해봄에게 도움 받기] / [해냈어요!] (해냈는지는 어르신이 누르는 것으로만)
 * 3. 해냈어요: 축하 한 화면 (점수·순위·연속 기록 없음)
 * 4. 칭찬 받기: "{호칭}이 칭찬을 보냈어요" + 칭찬 문구 → [확인]
 * 홈 카드에서 들어오면 받은 순서대로 하나씩. [EXTRA_PREVIEW] 는 서버 없이 화면만 보는 시험용 (debug)
 */
class ChallengeActivity : Activity() {
    private val settings by lazy { ElderSettings(this) }
    private lateinit var content: LinearLayout
    private var list: List<Challenge> = emptyList()
    private var preview = false
    private var cheerMode = false
    private var focusId: String? = null
    private var subscription: AutoCloseable? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        cheerMode = intent.getBooleanExtra(EXTRA_CHEER, false)
        focusId = intent.getStringExtra(EXTRA_ID)
        content = ElderUi.page(this)
        if (preview) list = listOf(previewChallenge())
    }

    override fun onStart() {
        super.onStart()
        if (preview) {
            render()
            return
        }
        val gateway = Challenges.gateway
        if (gateway == null) {
            showMessage(getString(R.string.challenge_failed))
            return
        }
        subscription = gateway.observe {
            list = gateway.cached()
            if (!busy) render()
        }
        gateway.refresh { if (it == null && list.isEmpty()) showMessage(getString(R.string.challenge_failed)) }
    }

    override fun onStop() {
        subscription?.close()
        subscription = null
        super.onStop()
    }

    private fun render() {
        if (cheerMode) {
            val cheers = Challenges.unseenCheers(list, settings.cheersSeen)
            val next = cheers.firstOrNull { it.first.id == focusId } ?: cheers.firstOrNull()
            if (next != null) {
                showCheer(next.first, next.second, next.third)
                return
            }
            cheerMode = false
        }
        val open = Challenges.openInOrder(list)
        val current = open.firstOrNull { it.id == focusId } ?: open.firstOrNull()
        when {
            current == null -> showMessage(getString(R.string.challenge_none))
            current.status == ChallengeStatus.ACCEPTED -> showDoing(current)
            else -> showReceived(current)
        }
    }

    // ───────────────────────── 화면 넷 ─────────────────────────

    private fun showReceived(c: Challenge) {
        content.removeAllViews()
        content.addView(ElderUi.text(this, getString(R.string.challenge_sent_by, Challenges.subject(label(c.suggestedBy))), 22f, ElderUi.NAVY_DARK, bold = true))
        addTitleAndHint(c)
        content.addView(big(getString(R.string.challenge_try)) {
            press(c, { g, done -> g.accept(c.id, done) }) {
                settings.challengesInProgress = settings.challengesInProgress + c.id
                list = list.map { if (it.id == c.id) it.copy(status = ChallengeStatus.ACCEPTED) else it }
                focusId = c.id
                render()
            }
        })
        // 작은 글씨: 거절이 아님 (보호자에게 알리지 않음). 나중에 홈 카드에서 다시 [해 볼게요]
        content.addView(ElderUi.text(this, getString(R.string.challenge_later), 21f, ElderUi.MUTED).apply {
            gravity = Gravity.CENTER
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            minHeight = ElderUi.dp(context, 64)
            setPadding(0, ElderUi.dp(context, 18), 0, 0)
            setOnClickListener {
                press(c, { g, done -> g.later(c.id, done) }) {
                    GuideCaption.post(getString(R.string.challenge_later_done), GuideCaption.Tone.INFO)
                    finish()
                }
            }
        })
    }

    private fun showDoing(c: Challenge) {
        content.removeAllViews()
        content.addView(ElderUi.text(this, getString(R.string.challenge_doing), 22f, ElderUi.NAVY_DARK, bold = true))
        addTitleAndHint(c)
        // 해봄 도우미를 그대로 씀. 도우미 안내가 시작되면 이 도전은 "도움 받음" (GuideSession.onStarted)
        content.addView(ElderUi.button(this, getString(R.string.challenge_get_help), ElderUi.Kind.SECONDARY) {
            startActivity(GuideInputActivity.intent(this))
        }.apply { minHeight = ElderUi.dp(context, 76) })
        content.addView(big(getString(R.string.challenge_done_button)) {
            val helped = c.id in settings.challengesHelped
            press(c, { g, done -> g.done(c.id, helped, done) }) {
                settings.challengesInProgress = settings.challengesInProgress - c.id
                settings.challengesHelped = settings.challengesHelped - c.id
                list = list.map {
                    if (it.id == c.id) it.copy(status = ChallengeStatus.DONE, withHelp = helped, completedAt = System.currentTimeMillis()) else it
                }
                showDone()
            }
        })
    }

    private fun showDone() {
        content.removeAllViews()
        content.addView(ElderUi.text(this, "🎉", 64f, ElderUi.INK).apply { gravity = Gravity.CENTER })
        content.addView(ElderUi.text(this, getString(R.string.challenge_done_title), 36f, ElderUi.GREEN, bold = true).apply { gravity = Gravity.CENTER })
        content.addView(ElderUi.text(this, getString(R.string.challenge_done_body), 24f, ElderUi.INK).apply {
            gravity = Gravity.CENTER
            setPadding(0, ElderUi.dp(context, 12), 0, ElderUi.dp(context, 12))
        })
        content.addView(big(getString(R.string.challenge_ok)) {
            if (preview) {
                // 시험용: 가족이 칭찬을 보낸 것처럼
                list = list.map { it.copy(cheers = mapOf(PREVIEW_GUARDIAN to "최고예요! 👍")) }
                cheerMode = true
                render()
            } else {
                finish()
            }
        })
    }

    private fun showCheer(c: Challenge, guardianId: String, message: String) {
        content.removeAllViews()
        content.addView(ElderUi.text(this, getString(R.string.challenge_cheer_from, Challenges.subject(label(guardianId))), 22f, ElderUi.NAVY_DARK, bold = true))
        content.addView(ElderUi.text(this, message, 34f, ElderUi.INK, bold = true).apply {
            setPadding(0, ElderUi.dp(context, 24), 0, ElderUi.dp(context, 8))
        })
        content.addView(ElderUi.body(this, c.title, 20f))
        content.addView(big(getString(R.string.challenge_ok)) {
            if (preview) return@big finish()
            settings.cheersSeen = settings.cheersSeen + Challenges.cheerKey(c.id, guardianId, message)
            focusId = null
            if (Challenges.unseenCheers(list, settings.cheersSeen).isEmpty()) finish() else render()
        })
    }

    private fun showMessage(text: String) {
        content.removeAllViews()
        content.addView(ElderUi.text(this, text, 24f, ElderUi.INK).apply { setPadding(0, ElderUi.dp(context, 24), 0, ElderUi.dp(context, 12)) })
        content.addView(big(getString(R.string.challenge_ok)) { finish() })
    }

    // ───────────────────────── 도우미 ─────────────────────────

    /** 도전 이름(가장 크게)과 도움말(회색 상자, 있을 때만) */
    private fun addTitleAndHint(c: Challenge) {
        content.addView(ElderUi.text(this, c.title, 34f, ElderUi.INK, bold = true).apply {
            setPadding(0, ElderUi.dp(context, 24), 0, ElderUi.dp(context, 8))
        })
        if (c.hint.isNotBlank()) {
            content.addView(ElderUi.card(this, fill = HINT_BOX, stroke = HINT_BOX).apply {
                addView(ElderUi.text(context, c.hint, 21f, ElderUi.INK))
            })
        }
    }

    private fun big(label: String, onClick: () -> Unit) = ElderUi.button(this, label, onClick = onClick).apply {
        minHeight = ElderUi.dp(context, 96)
        textSize = 27f
        (layoutParams as LinearLayout.LayoutParams).topMargin = ElderUi.dp(context, 28)
    }

    /** 단추를 서버에 전함. 시험용 미리 보기는 보내지 않고 바로 다음 화면 */
    private fun press(c: Challenge, call: (ChallengeGateway, (ChallengeResult) -> Unit) -> Unit, onOk: () -> Unit) {
        if (busy) return
        if (preview) {
            onOk()
            return
        }
        val gateway = Challenges.gateway ?: return showMessage(getString(R.string.challenge_send_failed))
        busy = true
        call(gateway) { result ->
            busy = false
            if (isFinishing || isDestroyed) return@call
            when (result) {
                ChallengeResult.OK -> onOk()
                // 이미 바뀐 상태 (다른 폰에서 눌렀거나 늦게 옴): 새 목록으로 다시 그림
                ChallengeResult.NOT_ALLOWED -> { list = gateway.cached(); render() }
                ChallengeResult.FAILED -> GuideCaption.post(getString(R.string.challenge_send_failed), GuideCaption.Tone.WARN)
            }
        }
        android.util.Log.d("HaebomChallenge", "단추: ${c.id} (${c.status.wire})")
    }

    /** 보호자 호칭: 연결 목록의 guardianLabel → 이름 → "가족" */
    private fun label(guardianId: String): String {
        if (guardianId == PREVIEW_GUARDIAN) return "딸"
        return Family.gateway?.links()?.firstOrNull { it.guardianId == guardianId }?.labelForElder ?: getString(R.string.challenge_family)
    }

    private fun previewChallenge() = Challenge(
        id = "preview-1",
        title = "카카오톡으로 사진 보내 보기",
        hint = "사진 모양 단추를 눌러 보세요",
        suggestedBy = PREVIEW_GUARDIAN,
        status = ChallengeStatus.SENT,
        createdAt = System.currentTimeMillis(),
    )

    companion object {
        private const val EXTRA_ID = "challenge_id"
        private const val EXTRA_CHEER = "cheer"
        private const val EXTRA_PREVIEW = "preview"
        private const val PREVIEW_GUARDIAN = "preview-guardian"
        private val HINT_BOX = Color.rgb(238, 240, 244)

        /** [id] 가 있으면 그 도전부터, [cheer] 면 새 칭찬부터 */
        fun intent(context: Context, id: String? = null, cheer: Boolean = false, preview: Boolean = false): Intent =
            Intent(context, ChallengeActivity::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_CHEER, cheer).putExtra(EXTRA_PREVIEW, preview)
    }
}
