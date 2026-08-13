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
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
            val admin = withContext(Dispatchers.IO) {
                try {
                    val currentUserId = MySelf.userId() ?: return@withContext false
                    val db = FirebaseFirestore.getInstance()
                    val snapshot = db.collection("groups").document(groupId).get().await()
                    if (!snapshot.exists()) return@withContext false

                    @Suppress("UNCHECKED_CAST")
                    val members = snapshot.get("members") as? Map<String, String>
                        ?: return@withContext false
                    members[currentUserId] == "admin"
                } catch (e: Exception) {
                    debugLine("GroupMembersActivity", "Admin check error: ${e.message}")
                    false
                }
            }

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
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.leave_group))
            .setMessage(getString(R.string.leave_group_alert))
            .setPositiveButton(getString(R.string.leave)) { _, _ -> leaveGroup() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun leaveGroup() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val myUserId = MySelf.userId() ?: return@launch
                val db = FirebaseFirestore.getInstance()
                val groupRef = db.collection("groups").document(groupId)

                groupRef.update("members.$myUserId", com.google.firebase.firestore.FieldValue.delete()).await()
                debugLine("GroupMembersActivity", "Removed myself from group $groupId in Firestore")

                val messageRepository = getMessageRepository(applicationContext)
                messageRepository.deleteRemotePeerMessages(groupId)

                val peerViewModel = getPeerViewModel()
                val peer = peerViewModel.getPeer(groupId)
                if (peer != null) {
                    peerViewModel.deletePeer(peer)
                }

                debugLine("GroupMembersActivity", "Local group data cleaned up after leaving")

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
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_group))
            .setMessage(getString(R.string.delete_group_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteGroup() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteGroup() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val groupRef = FirebaseFirestore.getInstance()
                    .collection("groups").document(groupId)
                val snapshot = groupRef.get().await()

                if (!snapshot.exists()) {
                    debugLine("GroupMembersActivity", "Group already deleted")
                    withContext(Dispatchers.Main) { finish() }
                    return@launch
                }

                @Suppress("UNCHECKED_CAST")
                val membersMap = snapshot.get("members") as? Map<String, String> ?: emptyMap()
                val myUserId = MySelf.userId() ?: return@launch
                val groupName = snapshot.getString("name") ?: ""

                membersMap.keys.filter { it != myUserId }.forEach { userId ->
                    notifyRemotePeer(userId, groupId, Notify.GROUP_REMOVED, groupName)
                }

                groupRef.delete().await()
                debugLine("GroupMembersActivity", "Group $groupId deleted from Firestore")

                try {
                    FirebaseStorage.getInstance().reference
                        .child("group_pictures/${groupId}.jpg")
                        .delete()
                        .await()
                    debugLine("GroupMembersActivity", "Group picture deleted from Storage")
                } catch (e: Exception) {
                    debugLine("GroupMembersActivity", "Group picture cleanup (may not exist): ${e.message}")
                }

                val messageRepository = getMessageRepository(applicationContext)
                messageRepository.deleteRemotePeerMessages(groupId)

                val peerViewModel = getPeerViewModel()
                val peer = peerViewModel.getPeer(groupId)
                if (peer != null) {
                    peerViewModel.deletePeer(peer)
                }

                debugLine("GroupMembersActivity", "Local group data cleaned up")

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