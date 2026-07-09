package com.bolimot.mindtheclub.views

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.assistant.AiAssistant
import com.bolimot.mindtheclub.contactAcquisition.PREF_AUTO_INVITE_MODE
import com.bolimot.mindtheclub.contactAcquisition.isAutoInviteEnabled
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.functions.NoteToSelf
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.transport.BluetoothToggle
import com.bolimot.mindtheclub.webrtc.RTCClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class OptionsActivity : BaseActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_options)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.options)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val stealthModeSwitch: SwitchMaterial = findViewById(R.id.stealthModeSwitch)
        stealthModeSwitch.isChecked = getPreference(RTCClient.PREF_STEALTH_MODE, this) == "true"
        stealthModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            setPreference(RTCClient.PREF_STEALTH_MODE, if (isChecked) "true" else "false", this)
        }

        val autoInviteModeSwitch: SwitchMaterial = findViewById(R.id.autoInviteModeSwitch)
        autoInviteModeSwitch.isChecked = isAutoInviteEnabled(this)
        autoInviteModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            setPreference(PREF_AUTO_INVITE_MODE, if (isChecked) "true" else "false", this)
        }

        val showClubbySwitch: SwitchMaterial = findViewById(R.id.showClubbySwitch)
        showClubbySwitch.isChecked = AiAssistant.isVisible(this)
        showClubbySwitch.setOnCheckedChangeListener { _, isChecked ->
            setPreference(AiAssistant.SHOW_CLUBBY_KEY, if (isChecked) "true" else "false", this)
        }

        val showNoteToSelfSwitch: SwitchMaterial = findViewById(R.id.showNoteToSelfSwitch)
        showNoteToSelfSwitch.isChecked = NoteToSelf.isVisible(this)
        showNoteToSelfSwitch.setOnCheckedChangeListener { _, isChecked ->
            setPreference(NoteToSelf.SHOW_NOTE_TO_SELF_KEY, if (isChecked) "true" else "false", this)
        }

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

        findViewById<ImageView>(R.id.stealthHelpIcon).setOnClickListener {
            showHelpDialog(
                getString(R.string.stealth_mode),
                getString(R.string.stealth_mode_help)
            )
        }

        findViewById<ImageView>(R.id.autoInviteHelpIcon).setOnClickListener {
            showHelpDialog(
                getString(R.string.auto_invite_mode),
                getString(R.string.auto_invite_mode_help)
            )
        }

        findViewById<ImageView>(R.id.showClubbyHelpIcon).setOnClickListener {
            showHelpDialog(
                getString(R.string.show_clubby),
                getString(R.string.show_clubby_help)
            )
        }

        findViewById<ImageView>(R.id.showNoteToSelfHelpIcon).setOnClickListener {
            showHelpDialog(
                getString(R.string.note_to_self),
                getString(R.string.show_note_to_self_help)
            )
        }

        findViewById<ImageView>(R.id.bluetoothHelpIcon).setOnClickListener {
            showHelpDialog(
                getString(R.string.bluetooth_transport),
                getString(R.string.bluetooth_transport_help)
            )
        }

        findViewById<MaterialButton>(R.id.inviteFriendButton).setOnClickListener {
            val name = MySelf.name()?.trim() ?: ""
            val userId = MySelf.userId() ?: ""
            val bio = MySelf.bio()?.trim() ?: ""

            if (listOf(name, userId).any { it.isEmpty() }) {
                showToast("No profile to share", this)
                return@setOnClickListener
            }

            val fingerprint = KeyManager.getMyPublicKeyFingerprint() ?: ""
            val payload = "mtc;$name;$userId;$bio;$fingerprint"
            startActivity(Intent(this, InviteActivity::class.java).putExtra("payload", payload))
        }

        findViewById<MaterialButton>(R.id.myProfileButton).setOnClickListener {
            startActivity(Intent(this, MyProfile::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    private fun showHelpDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
