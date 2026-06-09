package com.bolimot.mindtheclub.views

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.exportLogToVisibleStorage
import com.bolimot.mindtheclub.functions.generateQRCode
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.keypadIsOpen
import com.bolimot.mindtheclub.functions.saveBitmap
import com.bolimot.mindtheclub.functions.saveBitmapFromUri
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.transport.BluetoothToggle
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class MyProfile : BaseActivity() {

    private lateinit var profilePic: ImageView
    private lateinit var profilePicContainer: FrameLayout
    private lateinit var rootView: View
    private var profileChanged = false

    private lateinit var bluetoothSwitch: SwitchMaterial

    private val enableBtResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                BluetoothToggle.enable(this)
                bluetoothSwitch.isChecked = true
            } else {
                bluetoothSwitch.isChecked = false
            }
        }

    private val getImageResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val imageUri: Uri? = result.data?.data

            imageUri?.let {
                val fileName = "${System.currentTimeMillis()}_pic.jpg"
                val newUri = saveBitmapFromUri(it, fileName, 100)
                val newMiniUri = saveBitmapFromUri(it, "mini_$fileName", 100, 200)

                setPreference(MySelf.PICTURE_KEY, newUri.toString(), this)
                setPreference(MySelf.PICTURE_KEY_MINI, newMiniUri.toString(), this)

                profileChanged = true

                debugLine("MyProfile", "RESULT_OK: $newUri")

                Glide.with(this)
                    .load(it)
                    .into(profilePic)
            }

            profilePicContainer.isClickable = true
        } else {
            debugLine("MyProfile", "RESULT_CANCELED")
        }
    }

    override fun shouldApplyGlobalInsets(): Boolean {
        return false
    }

    override fun onPause() {
        val nameEditText: TextInputEditText = findViewById(R.id.nameEditText)
        val bioEditText: TextInputEditText = findViewById(R.id.bioEditText)

        val newName = nameEditText.text.toString()
        val newBio = bioEditText.text.toString()

        if (newName != getPreference("myName", this) || newBio != getPreference("myBio", this)) {
            profileChanged = true
        }

        setPreference("myName", newName, this)
        setPreference("myBio", newBio, this)

        if (profileChanged) {
            getPeerViewModel().broadcastMyProfileToAllPeers()
            profileChanged = false
        }

        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)

        setContentView(R.layout.my_profile)

        profilePic = findViewById(R.id.profilePic)
        profilePicContainer = findViewById(R.id.profilePicContainer)
        rootView = findViewById(R.id.nestedContainer)

        val fab = findViewById<View>(R.id.shareClub)
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val nameEditText: TextInputEditText = findViewById(R.id.nameEditText)
        val bioEditText: TextInputEditText = findViewById(R.id.bioEditText)

        val appBarLayout = findViewById<View>(R.id.appBarLayout)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            appBarLayout.updatePadding(top = bars.top)

            bottomNavigationView.updatePadding(bottom = bars.bottom)

            insets
        }

        fab.setOnClickListener {
            val name = nameEditText.text.toString().trim()
            val userId = MySelf.userId() ?: ""
            val bio = bioEditText.text.toString().trim()

            if (listOf(name, userId).any { it.isEmpty() }) {
                showToast("No profile to share", this@MyProfile)
                return@setOnClickListener
            }

            val fingerprint = com.bolimot.mindtheclub.crypto.KeyManager.getMyPublicKeyFingerprint() ?: ""
            val payload = "mtc;$name;$userId;$bio;$fingerprint"
            startActivity(Intent(this@MyProfile, InviteActivity::class.java).putExtra("payload", payload))
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.show_qr_code -> {
                    val name = nameEditText.text.toString().trim()
                    val userId = MySelf.userId() ?: ""
                    val bio = bioEditText.text.toString().trim()

                    if (listOf(name, userId).any { it.isEmpty() }) {
                        showToast("No QR code to show", this)
                        return@setOnItemSelectedListener false
                    }

                    val fingerprint = com.bolimot.mindtheclub.crypto.KeyManager.getMyPublicKeyFingerprint() ?: ""
                    val qrCode = saveBitmap(generateQRCode("mtc;$name;$userId;$bio;$fingerprint"),"myQRCode.jpg", 100)

                    qrCode?.let {
                        val qrCodePath = it.toString()
                        if(qrCodePath.isEmpty()) {
                            return@setOnItemSelectedListener false
                        }
                        val intent = Intent(this, ShowQRCode::class.java)
                        intent.putExtra("qrCode", qrCodePath)
                        startActivity(intent)
                    }
                    true
                }
                R.id.scan_qr_code -> {
                    val intent = Intent(this, ScanQRCode::class.java)
                    startActivity(intent)
                    true
                }
                R.id.send_debug_log -> {
                    startActivity(Intent(this, BlackListActivity::class.java))
                    true
                }
                else -> false
            }
        }

        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            if (keypadIsOpen(rootView)) {
                bottomNavigationView.visibility = View.INVISIBLE
                fab.visibility = View.INVISIBLE

            } else {
                bottomNavigationView.visibility = View.VISIBLE
                fab.visibility = View.VISIBLE
            }
        }

        nameEditText.imeOptions = EditorInfo.IME_ACTION_DONE
        nameEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        nameEditText.maxLines = 1

        bioEditText.imeOptions = EditorInfo.IME_ACTION_DONE
        bioEditText.setRawInputType(InputType.TYPE_CLASS_TEXT)
        nameEditText.maxLines = 3

        setSupportActionBar(toolbar)

        supportActionBar?.title = getString(R.string.my_profile)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        nameEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {

                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)

                setPreference("myName", v.text.toString(), this)

                profileChanged = true

                findViewById<ConstraintLayout>(R.id.container).requestFocus()

                true
            } else {
                false
            }
        }
        nameEditText.setOnFocusChangeListener { _, hasFocus ->
            nameEditText.isCursorVisible = hasFocus
        }

        bioEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {

                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)

                setPreference("myBio", v.text.toString(), this)

                profileChanged = true

                findViewById<ConstraintLayout>(R.id.container).requestFocus()

                true
            } else {
                false
            }
        }
        bioEditText.setOnFocusChangeListener { _, hasFocus ->
            nameEditText.isCursorVisible = hasFocus
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)

        bluetoothSwitch = findViewById(R.id.bluetoothTransportSwitch)
        bluetoothSwitch.isChecked = BluetoothToggle.isEnabled(this) && BluetoothToggle.isAdapterOn(this)
        bluetoothSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                when (BluetoothToggle.enable(this)) {
                    BluetoothToggle.EnableStep.NEEDS_ADAPTER ->
                        enableBtResult.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    BluetoothToggle.EnableStep.AWAITING_PERMISSIONS -> { /* finished in onRequestPermissionsResult */ }
                    BluetoothToggle.EnableStep.STARTED -> { /* fully on */ }
                }
            } else {
                BluetoothToggle.disable(this)
            }
        }

        val profilePicContainer: FrameLayout = findViewById(R.id.profilePicContainer)
        profilePicContainer.setOnClickListener {
            it.isClickable = false
            val intent = Intent(this, ImagesTab::class.java)
            getImageResult.launch(intent)
        }
    }

    override fun onResume() {
        super.onResume()

        val nameEditText: TextInputEditText = findViewById(R.id.nameEditText)
        val name: String? = getPreference("myName", this)
        nameEditText.setText(name)
        if (name.isNullOrEmpty()) {
            nameEditText.requestFocus()
            nameEditText.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(nameEditText, InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }

        val bioEditText: TextInputEditText = findViewById(R.id.bioEditText)
        val bio: String? = getPreference("myBio", this)
        bioEditText.setText(bio)

        val uriString = getPreference(MySelf.PICTURE_KEY, this)
        uriString?.let {
            Glide.with(this)
                .load(it.toUri())
                .signature(ObjectKey(System.currentTimeMillis()))
                .into(profilePic)
        }

        profilePicContainer.isClickable = true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BluetoothToggle.PERMISSIONS_REQUEST_CODE) {
            val granted = BluetoothToggle.onPermissionsResult(requestCode, grantResults)
            if (granted) {
                // Permissions granted — now check the adapter and prompt/start as needed.
                when (BluetoothToggle.enable(this)) {
                    BluetoothToggle.EnableStep.NEEDS_ADAPTER ->
                        enableBtResult.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    else -> bluetoothSwitch.isChecked = BluetoothToggle.isEnabled(this)
                }
            } else {
                bluetoothSwitch.isChecked = false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.my_profile_toolbar, menu)
        if (!BuildConfig.ENABLE_DEBUG_TOOLS) {
            menu.findItem(R.id.bug)?.isVisible = false
            menu.findItem(R.id.delete_log)?.isVisible = false
            menu.findItem(R.id.about)?.isVisible = false
        }
        return true
    }

    @SuppressLint("SetTextI18n")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.bug -> {
                if (BuildConfig.ENABLE_DEBUG_TOOLS) exportLogToVisibleStorage()
                return true
            }
            R.id.backup_restore -> {
                startActivity(Intent(this, BackupRestoreActivity::class.java))
                return true
            }
            R.id.delete_log -> {
                if (BuildConfig.ENABLE_DEBUG_TOOLS) {
                    val context = applicationContext
                    val deviceContext = context.createDeviceProtectedStorageContext()
                    val safeName = MySelf.name()?.trim() ?: return true
                    val logFile = java.io.File(deviceContext.filesDir, "$safeName.txt")
                    if (logFile.exists()) {
                        logFile.delete()
                        android.widget.Toast.makeText(
                            this,
                            "Log deleted",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            this,
                            "No log file found",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return true
            }
            R.id.about -> {
                if (BuildConfig.ENABLE_DEBUG_TOOLS) {
                    val layout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, 64, 0, 0)
                    }

                    val appName = TextView(this).apply {
                        text = getString(R.string.app_name)
                        textSize = 20f
                        gravity = android.view.Gravity.CENTER
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    val versionLabel = TextView(this).apply {
                        text = getString(R.string.version)
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, 16, 0, 0)
                    }

                    val versionValue = TextView(this).apply {
                        text = BuildConfig.VERSION_NAME
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, 8, 0, 0)
                    }

                    val userIdLabel = TextView(this).apply {
                        text = "User ID"
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, 32, 0, 0)
                    }

                    val userIdValue = TextView(this).apply {
                        text = MySelf.userId()
                        textSize = 14f
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, 8, 0, 0)
                        setTextIsSelectable(true)
                    }

                    layout.addView(appName)
                    layout.addView(versionLabel)
                    layout.addView(versionValue)
                    layout.addView(userIdLabel)
                    layout.addView(userIdValue)

                    MaterialAlertDialogBuilder(this)
                        .setView(layout)
                        .setPositiveButton(R.string.close, null)
                        .setCancelable(true)
                        .show()
                }

                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}