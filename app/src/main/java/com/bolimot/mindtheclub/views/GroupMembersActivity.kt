package com.bolimot.mindtheclub.views

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.chat.SelectPeersForGroup
import com.bolimot.mindtheclub.dialogs.DeletePeerDialog
import com.bolimot.mindtheclub.fragments.GroupMembersFragment
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.deleteGroupNow
import com.bolimot.mindtheclub.functions.isGroupAdmin
import com.bolimot.mindtheclub.functions.leaveGroupNow
import com.bolimot.mindtheclub.functions.showDeleteGroupDialog
import com.bolimot.mindtheclub.functions.showLeaveGroupDialog
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupMembersActivity : BaseActivity(), DeletePeerDialog.DeletePeerListener {

    private lateinit var groupId: String
    private lateinit var bottomNavigationView: BottomNavigationView
    private var groupMembersFragment: GroupMembersFragment? = null
    private var isAdmin = false

    private val addMembersResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val selectedPeers: List<String> =
                    result.data?.getStringArrayListExtra("selectedPeers")
                        ?: return@registerForActivityResult

                debugLine("GroupMembersActivity", "Adding ${selectedPeers.size} members")
                groupMembersFragment?.addMembers(selectedPeers)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        groupId = intent.getStringExtra("userId") ?: return
        val groupName = intent.getStringExtra("name") ?: "Group Members"

        setContentView(R.layout.activity_group_members)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar?>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.title = groupName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bottomNavigationView = findViewById(R.id.bottom_navigation)

        if (savedInstanceState == null) {
            groupMembersFragment = GroupMembersFragment(groupId)
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, groupMembersFragment!!)
                .commit()
        }

        checkAdminStatus()
    }

    private fun checkAdminStatus() {
        lifecycleScope.launch {
            val admin = isGroupAdmin(groupId)

            isAdmin = admin
            if (isAdmin) {
                bottomNavigationView.visibility = View.VISIBLE
                setupBottomNavigation()
            } else {
                bottomNavigationView.menu.clear()
                bottomNavigationView.inflateMenu(R.menu.group_leave_bottom_menu)
                bottomNavigationView.visibility = View.VISIBLE
                setupLeaveGroupNavigation()
            }
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.add_member -> {
                    launchAddMembers()
                    true
                }

                R.id.remove_member -> {
                    removeSelectedMembers()
                    true
                }

                R.id.make_admin -> {
                    promoteSelectedMembers()
                    true
                }

                R.id.delete_group -> {
                    confirmDeleteGroup()
                    true
                }

                else -> false
            }
        }
    }

    private fun setupLeaveGroupNavigation() {
        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.leave_group -> {
                    confirmLeaveGroup()
                    true
                }
                else -> false
            }
        }
    }

    private fun confirmLeaveGroup() {
        showLeaveGroupDialog(this) { leaveGroup() }
    }

    private fun leaveGroup() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                leaveGroupNow(applicationContext, groupId)

                withContext(Dispatchers.Main) {
                    ChatScreen.shouldFinish = true
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                debugLine("GroupMembersActivity", "Error leaving group: ${e.message}")
            }
        }
    }

    private fun launchAddMembers() {
        val currentMemberIds = groupMembersFragment?.getCurrentMemberUserIds() ?: emptyList()
        val allExcluded = ArrayList(currentMemberIds)
        MySelf.userId()?.let { allExcluded.add(it) }

        val intent = Intent(this, SelectPeersForGroup::class.java).apply {
            putStringArrayListExtra("excludedUserIds", allExcluded)
        }
        addMembersResult.launch(intent)
    }

    private fun removeSelectedMembers() {
        val selectedIds = groupMembersFragment?.getSelectedMemberUserIds() ?: emptyList()
        if (selectedIds.isEmpty()) {
            debugLine("GroupMembersActivity", "No members selected for removal")
            return
        }
        groupMembersFragment?.removeMembers(selectedIds)
    }

    private fun promoteSelectedMembers() {
        val selectedIds = groupMembersFragment?.getSelectedMemberUserIds() ?: emptyList()
        if (selectedIds.isEmpty()) {
            debugLine("GroupMembersActivity", "No members selected for promotion")
            return
        }
        groupMembersFragment?.promoteMembers(selectedIds)
    }

    private fun confirmDeleteGroup() {
        showDeleteGroupDialog(this) { deleteGroup() }
    }

    private fun deleteGroup() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!deleteGroupNow(applicationContext, groupId)) {
                    // Already gone from Firestore: as before, just close.
                    withContext(Dispatchers.Main) { finish() }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    ChatScreen.shouldFinish = true
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                debugLine("GroupMembersActivity", "Error deleting group: ${e.message}")
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun updateToolbarTitle(newTitle: String) {
        supportActionBar?.title = newTitle
    }

    override fun onPeerDeleted() {
        setResult(RESULT_OK)
        finish()
    }
}