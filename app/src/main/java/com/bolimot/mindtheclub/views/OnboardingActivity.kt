package com.bolimot.mindtheclub.views

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.constraintlayout.widget.ConstraintLayout
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText

class OnboardingActivity : BaseActivity() {

    companion object {
        /**
         * TESTING TOGGLE.
         *  - true  -> onboarding always runs (handy while developing: no need to
         *             reinstall the app between tests).
         *  - false -> production behaviour: onboarding runs only on the first launch
         *             after a fresh install. This is detected through [COMPLETED_KEY],
         *             which lives in shared preferences and is wiped on uninstall.
         */
        const val FORCE_ONBOARDING = true

        private const val COMPLETED_KEY = "onboardingCompleted"

        /** Decides whether the onboarding flow should be shown on this launch. */
        fun shouldRun(context: Context): Boolean {
            if (FORCE_ONBOARDING) return true
            return getPreference(COMPLETED_KEY, context) != "true"
        }

        /** Marks onboarding as finished so it won't run again (production path). */
        fun markCompleted(context: Context) {
            setPreference(COMPLETED_KEY, "true", context)
        }
    }

    private lateinit var container: View
    private lateinit var nameEditText: TextInputEditText
    private lateinit var nextButton: ExtendedFloatingActionButton

    private var keyboardOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        container = findViewById(R.id.container)
        nameEditText = findViewById(R.id.nameEditText)
        nextButton = findViewById(R.id.nextButton)

        // Pre-fill in case the user comes back to this screen.
        nameEditText.setText(getPreference(MySelf.NAME_KEY, this))
        refreshNextVisibility()

        // Hide "Next" while the soft keyboard is open. Measuring the visible
        // frame works across API 26-35, regardless of edge-to-edge insets.
        container.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            container.getWindowVisibleDisplayFrame(visible)
            val rootHeight = container.rootView.height
            val open = (rootHeight - visible.bottom) > rootHeight * 0.15
            if (open != keyboardOpen) {
                keyboardOpen = open
                refreshNextVisibility()
            }
        }

        nameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val name = s?.toString().orEmpty().trim()
                // Save the name as soon as one is defined.
                setPreference(MySelf.NAME_KEY, name, this@OnboardingActivity)
                refreshNextVisibility()
            }
        })

        nameEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                findViewById<ConstraintLayout>(R.id.container).requestFocus()
                true
            } else {
                false
            }
        }

        nextButton.setOnClickListener {
            setPreference(MySelf.NAME_KEY, nameEditText.text?.toString().orEmpty().trim(), this)
            // Onboarding step 2: the permission-priming screen.
            startActivity(Intent(this, OnboardingPermissionsActivity::class.java))
        }
    }

    /** "Next" shows only when a name is set and the keyboard is closed. */
    private fun refreshNextVisibility() {
        val hasName = nameEditText.text?.toString().orEmpty().isNotBlank()
        nextButton.visibility = if (hasName && !keyboardOpen) View.VISIBLE else View.GONE
    }
}
