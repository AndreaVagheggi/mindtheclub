package com.bolimot.mindtheclub.views

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.saveBitmapFromUri
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText

/**
 * Onboarding step 2: optional profile picture and bio.
 *
 * The picture picker and bio field behave exactly like the ones in [MyProfile] —
 * same image flow ([ImagesTab] -> save -> preferences) and the same bio preference.
 * Both are optional, so "Next" is always enabled (only hidden while the keyboard is
 * open). Flow: name screen -> this screen -> permission screen.
 */
class OnboardingProfileActivity : BaseActivity() {

    private lateinit var container: View
    private lateinit var profilePic: ShapeableImageView
    private lateinit var imageEditIcon: ImageView
    private lateinit var bioEditText: TextInputEditText
    private lateinit var nextButton: ExtendedFloatingActionButton

    private var keyboardOpen = false

    private val getImageResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val imageUri: Uri? = result.data?.data

                imageUri?.let {
                    val fileName = "${System.currentTimeMillis()}_pic.jpg"
                    val newUri = saveBitmapFromUri(it, fileName, 100)
                    val newMiniUri = saveBitmapFromUri(it, "mini_$fileName", 100, 200)

                    setPreference(MySelf.PICTURE_KEY, newUri.toString(), this)
                    setPreference(MySelf.PICTURE_KEY_MINI, newMiniUri.toString(), this)

                    Glide.with(this)
                        .load(it)
                        .into(profilePic)
                }

                imageEditIcon.isClickable = true
            } else {
                debugLine("OnboardingProfile", "RESULT_CANCELED")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding_profile)

        container = findViewById(R.id.container)
        profilePic = findViewById(R.id.profilePic)
        imageEditIcon = findViewById(R.id.imageEditIcon)
        bioEditText = findViewById(R.id.bioEditText)
        nextButton = findViewById(R.id.nextButton)

        // Hide "Next" while the soft keyboard is open (same approach as screen 1).
        container.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            container.getWindowVisibleDisplayFrame(visible)
            val rootHeight = container.rootView.height
            val open = (rootHeight - visible.bottom) > rootHeight * 0.15
            if (open != keyboardOpen) {
                keyboardOpen = open
                nextButton.visibility = if (keyboardOpen) View.GONE else View.VISIBLE
            }
        }

        // Enter on the soft keyboard dismisses the bio edit (same as MyProfile),
        // instead of inserting a new line.
        bioEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                saveBio()
                container.requestFocus()
                true
            } else {
                false
            }
        }

        imageEditIcon.setOnClickListener {
            it.isClickable = false
            getImageResult.launch(Intent(this, ImagesTab::class.java))
        }

        profilePic.setOnClickListener {
            val uriString = getPreference(MySelf.PICTURE_KEY, this)
            if (!uriString.isNullOrEmpty()) {
                val intent = Intent(this, ProfilePictureViewActivity::class.java).apply {
                    putExtra("pictureUri", uriString)
                    putExtra("title", getPreference(MySelf.NAME_KEY, this@OnboardingProfileActivity) ?: "")
                }
                startActivity(intent)
            }
        }

        nextButton.setOnClickListener {
            saveBio()
            // Onboarding step 3: the permission-priming screen.
            startActivity(Intent(this, OnboardingPermissionsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()

        bioEditText.setText(getPreference("myBio", this))

        getPreference(MySelf.PICTURE_KEY, this)?.let {
            Glide.with(this)
                .load(it.toUri())
                .signature(ObjectKey(System.currentTimeMillis()))
                .into(profilePic)
        }

        imageEditIcon.isClickable = true
    }

    override fun onPause() {
        // Persist the bio on any exit path (optional field).
        saveBio()
        super.onPause()
    }

    private fun saveBio() {
        setPreference("myBio", bioEditText.text.toString().trim(), this)
    }
}
