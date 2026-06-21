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
         * Onboarding runs only when no user name has been set yet. The name is the
         * one piece of information the app can't work without, so its absence means
         * this is effectively a first run. Once a name exists, the app starts
         * normally. (The name is saved on the first screen, which can't be passed
         * without entering one.)
         */
        fun shouldRun(context: Context): Boolean {
            return getPreference(MySelf.NAME_KEY, context).isNullOrEmpty()
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
            // Onboarding step 2: optional profile picture and bio.
            startActivity(Intent(this, OnboardingProfileActivity::class.java))
        }
    }

    /** "Next" shows only when a name is set and the keyboard is closed. */
    private fun refreshNextVisibility() {
        val hasName = nameEditText.text?.toString().orEmpty().isNotBlank()
        nextButton.visibility = if (hasName && !keyboardOpen) View.VISIBLE else View.GONE
    }
}
