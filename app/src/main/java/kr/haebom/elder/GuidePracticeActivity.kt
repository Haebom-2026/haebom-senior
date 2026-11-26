package kr.haebom.elder

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

class GuidePracticeActivity : Activity() {
    private var isLastStep = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isLastStep = savedInstanceState?.getBoolean(LAST_STEP) ?: false
        setContentView(R.layout.activity_guide_practice)
        findViewById<Button>(R.id.practice_next).setOnClickListener {
            isLastStep = true
            showStep()
        }
        findViewById<Button>(R.id.practice_finish).setOnClickListener { finish() }
        showStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(LAST_STEP, isLastStep)
        super.onSaveInstanceState(outState)
    }

    private fun showStep() {
        findViewById<TextView>(R.id.practice_title).setText(
            if (isLastStep) R.string.practice_step_two else R.string.practice_step_one,
        )

        findViewById<Button>(R.id.practice_next).visibility =
            if (isLastStep) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.practice_finish).visibility =
            if (isLastStep) View.VISIBLE else View.GONE
    }

    private companion object {
        const val LAST_STEP = "last_step"
    }
}
