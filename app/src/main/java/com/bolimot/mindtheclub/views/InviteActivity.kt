package com.bolimot.mindtheclub.views

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.shareMyProfile
import com.bolimot.mindtheclub.start.BaseActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.bolimot.mindtheclub.functions.buildInviteLink
import com.bolimot.mindtheclub.functions.parseQRCode
import android.view.Menu

class InviteActivity : BaseActivity() {

    private lateinit var contactsRecyclerView: RecyclerView
    private lateinit var contactsAdapter: ContactsAdapter
    private var payload: String = ""

    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadContacts()
            else Toast.makeText(this, getString(R.string.contacts_permission_needed), Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.invite_screen)

        payload = intent.getStringExtra("payload") ?: ""

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.invite_friends)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        contactsAdapter = ContactsAdapter { contact -> onContactSelected(contact) }
        contactsRecyclerView = findViewById(R.id.contactsRecyclerView)
        contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        contactsRecyclerView.adapter = contactsAdapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        ensureContactsAndLoad()
    }

    private fun ensureContactsAndLoad() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED) {
            loadContacts()
        } else {
            requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun loadContacts() {
        lifecycleScope.launch {
            val contacts = loadPhoneContacts(this@InviteActivity)
            contactsAdapter.submitList(contacts)
        }
    }

    private fun onContactSelected(contact: PhoneContact) {
        lifecycleScope.launch {
            val numbers = loadPhoneNumbers(this@InviteActivity, contact.id)
            when {
                numbers.isEmpty() ->
                    Toast.makeText(this@InviteActivity, getString(R.string.no_sms_app), Toast.LENGTH_SHORT).show()
                numbers.size == 1 -> openSmsComposer(numbers.first().number)
                else -> {
                    val items = numbers.map { "${it.label} · ${it.number}" }.toTypedArray()
                    AlertDialog.Builder(this@InviteActivity)
                        .setTitle(contact.name)
                        .setItems(items) { _, which -> openSmsComposer(numbers[which].number) }
                        .show()
                }
            }
        }
    }

    private fun openSmsComposer(number: String) {
        val link = parseQRCode(payload)?.let { qr ->
            buildInviteLink(qr.name, qr.userId, qr.bio, qr.fingerprint)
        } ?: ""
        val body = getString(R.string.share_message) + "\n\n" + link

        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).apply {
            putExtra("sms_body", body)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_sms_app), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.invite_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.action_share_other -> {
                lifecycleScope.launch {
                    shareMyProfile(payload, this@InviteActivity, contactsRecyclerView)
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}