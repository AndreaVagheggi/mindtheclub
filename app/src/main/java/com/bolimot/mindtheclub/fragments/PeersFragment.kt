package com.bolimot.mindtheclub.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.PeersAdapter
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.peer.PeerRepository
import com.bolimot.mindtheclub.dialogs.BlockPeerDialog
import com.bolimot.mindtheclub.functions.applyNavigationBarPadding
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.saveNewGroupAsPeer
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.viewModel.PeerViewModel
import com.bolimot.mindtheclub.viewModel.PeerViewModelFactory
import com.bolimot.mindtheclub.views.GroupMembersActivity
import com.bolimot.mindtheclub.views.PeerView
import com.google.firebase.firestore.ktx.firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class PeersFragment : Fragment(), PeersAdapter.OnItemClickListener, BlockPeerDialog.BlockPeerListener {

    private lateinit var viewModel: PeerViewModel
    private lateinit var peersAdapter: PeersAdapter
    private lateinit var addContactButtonContainer: FrameLayout

    private var isTransparent = false

    override fun onItemClick(peer: Peer) {
        debugLine("onItemClick", "Peer clicked")

        if (peersAdapter.isAnyPeerSelected()) {
            if (peer.userId.startsWith("group")) return
            peersAdapter.toggleSelection(peer.userId)
            return
        }

        if (peer.status == Contact.NEW) {
            val intent = Intent(requireContext(), PeerView::class.java).apply {
                putExtra("userId", peer.userId)
                putExtra("name", peer.name)
                putExtra("bio", getString(R.string.pending))
                putExtra("picture", peer.picture ?: "")
            }
            startActivity(intent)
            return
        }

        val intent = Intent(requireContext(), ChatScreen::class.java).apply {
            putExtra("userId", peer.userId)
            putExtra("name", peer.name)
            putExtra("bio", peer.bio)
            putExtra("picture", peer.picture)
        }
        startActivity(intent)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onResume() {
        super.onResume()
        revertNavigationBarTransparency()

        isTransparent = false

        peersAdapter.notifyDataSetChanged()

        syncGroupPictures()
    }

    private fun syncGroupPictures() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val groups = viewModel.getGroupPeersWithoutPicture()
                if (groups.isEmpty()) return@launch

                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                for (group in groups) {
                    try {
                        val snapshot = db.collection("groups").document(group.userId).get().await()
                        val pictureUrl = snapshot.getString("picture")
                        if (!pictureUrl.isNullOrEmpty()) {
                            viewModel.updatePeerPicture(group.userId, pictureUrl)
                        }
                    } catch (e: Exception) {
                        debugLine("PeersFragment", "Error syncing group picture for ${group.userId}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                debugLine("PeersFragment", "Error syncing group pictures: ${e.message}")
            }
        }
    }

    override fun onItemLongClick(peer: Peer): Boolean {
        debugLine("onItemLongClick", "Peer long clicked")
        if (peer.userId.startsWith("group")) return false

        peersAdapter.toggleSelection(peer.userId)
        if (peersAdapter.isAnyPeerSelected()) {
            debugLine("onItemLongClick", "Peers selected")
        } else {
            debugLine("onItemLongClick", "No peer selected")
        }
        return true
    }

    override fun onBlockClick(peer: Peer) {
        val dialog = BlockPeerDialog.newInstance(
            peer.userId,
            peer.name,
            peer.picture
        )
        dialog.show(childFragmentManager, "blockPeer")
    }

    override fun onRejectClick(peer: Peer) {
        viewModel.deletePeer(peer)
    }

    override fun onPeerBlocked() { }

    override fun onAcceptClick(peer: Peer) {
        lifecycleScope.launch {
            if (viewModel.setStatusToActive(peer.userId)) {
                viewModel.sendMyProfileToRemotePeer(peer.userId)
                fetchAndStorePeerPublicKey(peer.userId)
            } else {
                debugLine("onAcceptClick", "Failed to set status to active")
            }
        }
    }

    private suspend fun fetchAndStorePeerPublicKey(userId: String) {
        try {
            val doc = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(userId).get().await()
            val publicKey = doc.getString("publicKey")
            if (!publicKey.isNullOrEmpty()) {
                com.bolimot.mindtheclub.functions.getPeerDao(requireContext())
                    .updatePeerPublicKey(userId, publicKey)
                com.bolimot.mindtheclub.transport.PeerIdentityResolver.markStale()
                debugLine("onAcceptClick", "publicKey stored for $userId")
            } else {
                debugLine("onAcceptClick", "No publicKey available yet for $userId")
            }
        } catch (e: Exception) {
            debugLine("onAcceptClick", "fetchAndStorePeerPublicKey failed: ${e.message}")
        }
    }

    override fun isAnyPeerSelected(): Boolean {
        return peersAdapter.isAnyPeerSelected()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.peers_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        peersAdapter = PeersAdapter(requireContext(), this, false, viewLifecycleOwner)

        val recyclerView = view.findViewById<RecyclerView>(R.id.contactList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = peersAdapter
            clipToPadding = false
        }

        addContactButtonContainer = view.findViewById(R.id.fab_container)
        val addGroupButton = view.findViewById<ImageButton>(R.id.fab_add_group)

        recyclerView.applyNavigationBarPadding()

        val extraPadding = (40 * resources.displayMetrics.density).toInt()
        recyclerView.setPadding(
            recyclerView.paddingLeft,
            recyclerView.paddingTop,
            recyclerView.paddingRight,
            extraPadding
        )
        recyclerView.applyNavigationBarPadding()

        addGroupButton.setOnClickListener {
            val selected = peersAdapter.getSelectedPeersUserId()
            if (selected.size < 2) {
                showToast(getString(R.string.select_group), requireContext())
            } else {
                showGroupCreationDialog(selected)
            }
        }

        val peerRepository = PeerRepository(DatabaseProvider.provideDatabase(requireContext()).peerDao())
        val factory = PeerViewModelFactory(requireActivity().application, peerRepository)
        viewModel = ViewModelProvider(this, factory)[PeerViewModel::class.java]

        lifecycleScope.launch {
            viewModel.peers
                .collectLatest { pagingData: PagingData<Peer> ->
                    peersAdapter.submitData(pagingData)
                }
        }

        addContactButtonContainer.scaleX = 1f
        addContactButtonContainer.scaleY = 1f

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (dy > 0 && !isTransparent) {
                    makeNavigationBarTransparent()
                    isTransparent = true
                }
                else if (dy < 0 && isTransparent) {
                    revertNavigationBarTransparency()
                    isTransparent = false
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    revertNavigationBarTransparency()
                    isTransparent = false
                }
            }
        })
    }

    private fun createNewGroup(selectedIds: List<String>, groupName: String) {
        val totalMembers = selectedIds.size + 1 // including self (admin)
        if (totalMembers > 63) {
            showToast(getString(R.string.group_member_limit_exceeded, 63), requireContext())
            peersAdapter.removeSelection()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val gId = "group${guid()}"
            val db = com.google.firebase.ktx.Firebase.firestore
            val currentUserId = MySelf.userId() ?: return@launch
            val membersMap = selectedIds.associateWith { "member" }.toMutableMap()

            membersMap[currentUserId] = "admin"

            val groupData = hashMapOf(
                "groupId" to gId,
                "createdBy" to currentUserId,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "members" to membersMap,
                "name" to groupName
            )

            try {
                db.collection("groups")
                    .document(gId)
                    .set(groupData)
                    .await()

                debugLine("createNewGroup", "Success: Structured Group $gId created.")

                if (saveNewGroupAsPeer(gId, groupName)) {
                    sendNewGroupToMembers(gId, groupName, selectedIds)
                }

            } catch (e: Exception) {
                debugLine("createNewGroup", "Error creating group: ${e.message}")
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                peersAdapter.removeSelection()

                val intent = Intent(requireContext(), GroupMembersActivity::class.java).apply {
                    putExtra("userId", gId)
                    putExtra("name", groupName)
                }
                startActivity(intent)
            }
        }
    }

    private fun sendNewGroupToMembers(gId: String, groupName: String, selectedIds: List<String>) {
        selectedIds.forEach { userId ->
            notifyRemotePeer(userId, gId, Notify.GROUP, groupName)
        }
    }

    private fun revertNavigationBarTransparency() {
        (activity as? BaseActivity)?.setNavigationBarColor(R.color.mtc_opaque)
    }

    private fun makeNavigationBarTransparent() {
        (activity as? BaseActivity)?.setNavigationBarColor(R.color.white_transparent)
    }

    private fun showGroupCreationDialog(selectedIds: List<String>) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_create_group, null)

        val editText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.groupNameEditText)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("New Group")
            .setView(dialogView)
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel") { dialog, _ ->
                peersAdapter.removeSelection()
                dialog.dismiss()
            }
            .setOnCancelListener {
                peersAdapter.removeSelection()
            }
            .create()

        dialog.show()

        val createButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        createButton.setTextColor(android.graphics.Color.RED)

        createButton.setOnClickListener {
            val groupName = editText.text.toString().trim()
            if (groupName.isNotEmpty()) {
                createNewGroup(selectedIds, groupName)
                dialog.dismiss()
            } else {
                dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.groupNameInputLayout)
                    .error = "Name cannot be empty"
            }
        }
    }
}
