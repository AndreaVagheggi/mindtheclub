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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.launch

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

        val shareFab: ExtendedFloatingActionButton = findViewById(R.id.shareOtherApps)
        shareFab.setOnClickListener {
            lifecycleScope.launch {
                shareMyProfile(payload, this@InviteActivity, contactsRecyclerView)
            }
        }

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

    // Wired to the SMS composer in Step 7c
    private fun onContactSelected(contact: PhoneContact) {
        Toast.makeText(this, contact.name, Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}