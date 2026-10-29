package com.bridge.security

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Pair confirmation screen (BR-5).
 *
 * The six digits are the control: they are derived from both certificate fingerprints,
 * so they agree only when each device is really talking to the other. Nothing is pinned
 * until the user taps Accept here *and* the Mac's user does the same.
 *
 * Plain programmatic AppCompat views to match the rest of the app's no-Compose UI.
 */
class PairConfirmationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Pair Request"

        val pending = PairConfirmation.pending
        if (pending == null) {
            // Already answered elsewhere, or the link dropped while this was opening.
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        root.addView(TextView(this).apply {
            text = "${pending.deviceName} wants to pair"
            textSize = 20f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = spaced(pending.code)
            textSize = 44f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
            contentDescription = "Verification code ${pending.code.toCharArray().joinToString(" ")}"
        })

        root.addView(TextView(this).apply {
            text = "Accept only if the Mac shows these same six digits."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(Button(this).apply {
            text = "Decline"
            contentDescription = "Decline pair request from ${pending.deviceName}"
            setOnClickListener { answer(accepted = false) }
        })
        buttons.addView(Button(this).apply {
            text = "Accept"
            contentDescription = "Accept pair request from ${pending.deviceName}"
            setOnClickListener { answer(accepted = true) }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                leftMargin = dp(16)
            }
        })
        root.addView(buttons)

        setContentView(root)
    }

    private fun answer(accepted: Boolean) {
        PairConfirmation.answer(accepted)
        finish()
    }

    /** "418207" reads as six digits; "418 207" reads as two groups a person can hold. */
    private fun spaced(code: String): String =
        if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
