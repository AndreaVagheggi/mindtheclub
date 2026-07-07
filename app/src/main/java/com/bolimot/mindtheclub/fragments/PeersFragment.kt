package com.bolimot.mindtheclub.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.PeersAdapter
import com.bolimot.mindtheclub.assistant.AiAssistant
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.peer.PeerRepository
import com.bolimot.mindtheclub.dialogs.BlockPeerDialog
import com.bolimot.mindtheclub.functions.applyNavigationBarPadding
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.saveNewGroupAsPeer
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.notifications.MessageReceivedNotification
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.viewModel.PeerViewModel
import com.bolimot.mindtheclub.viewModel.PeerViewModelFactory
import com.bolimot.mindtheclub.views.GroupMembersActivity
import com.bolimot.mindtheclub.views.PeerView
import com.bumptech.glide.Glide
import com.google.firebase.firestore.ktx.firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import androidx.paging.LoadState
import com.bolimot.mindtheclub.views.InviteActivity

class PeersFragment : Fragment(), PeersAdapter.OnItemClickListener, BlockPeerDialog.BlockPeerListener {

    private lateinit var viewModel: PeerViewModel
    private lateinit var peersAdapter: PeersAdapter
    private lateinit var addContactButtonContainer: FrameLayout

    private var isTransparent = false
    private var clubbyRow: View? = null

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

        refreshClubbyRow()

        syncGroupPictures()
    }

    /**
     * Clubby is pinned above the scrollable list, so its row is bound manually
     * (mirroring PeersActiveViewHolder): avatar, blue name, last message, badge.
     */
    private fun refreshClubbyRow() {
        val row = clubbyRow ?: return
        val divider = view?.findViewById<View>(R.id.clubbyDivider)

        viewLifecycleOwner.lifecycleScope.launch {
            val peer = viewModel.getPeer(AiAssistant.USER_ID)

            if (peer == null || peer.privateId.startsWith("blocked")
                || !AiAssistant.isVisible(requireContext())) {
                row.visibility = View.GONE
                divider?.visibility = View.GONE
                return@launch
            }
            row.visibility = View.VISIBLE
            divider?.visibility = View.VISIBLE

            row.findViewById<TextView>(R.id.name).apply {
                text = peer.name
                setTextColor(ContextCompat.getColor(requireContext(), R.color.assistant_name))
            }

            val imageView = row.findViewById<ImageView>(R.id.peerImage)
            peer.picture?.let { pictureUri ->
                val file = java.io.File(pictureUri.toUri().path ?: "")
                val signature = com.bumptech.glide.signature.ObjectKey(file.lastModified())
                Glide.with(this@PeersFragment)
                    .load(pictureUri)
                    .signature(signature)
                    .placeholder(imageView.drawable)
                    .error(R.drawable.peer)
                    .into(imageView)
            } ?: Glide.with(this@PeersFragment).load(R.drawable.peer).into(imageView)

            val lastMessageData = withContext(Dispatchers.IO) {
                getMessageRepository(requireContext()).getLastMessageData(peer.userId)
            }
            row.findViewById<TextView>(R.id.lastMessage).text = lastMessageData?.first
            row.findViewById<TextView>(R.id.lastMessageDate).text = lastMessageData?.second

            val unreadCount = MessageReceivedNotification.getUnreadCount(requireContext(), peer.userId)
            val badge = row.findViewById<TextView>(R.id.unreadBadge)
            if (unreadCount > 0) {
                badge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }

            row.setOnClickListener {
                // Direct open: never part of multi-select / group creation.
                val intent = Intent(requireContext(), ChatScreen::class.java).apply {
                    putExtra("userId", peer.userId)
                    putExtra("name", peer.name)
                    putExtra("bio", peer.bio)
                    putExtra("picture", peer.picture)
                }
                startActivity(intent)
            }
        }
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
            } else {
                debugLine("onAcceptClick", "Failed to set status to active")
            }
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

        clubbyRow = view.findViewById(R.id.clubbyRow)

        lifecycleScope.launch {
            viewModel.peers
                .collectLatest { pagingData: PagingData<Peer> ->
                    // Clubby lives in the pinned row above the list, not in it.
                    peersAdapter.submitData(pagingData.filter { !AiAssistant.isAssistant(it.userId) })
                }
        }

        val inviteFriendButton = view.findViewById<View>(R.id.inviteFriendButton)
        val emptyListText = view.findViewById<View>(R.id.emptyListText)

        inviteFriendButton.setOnClickListener {
            val name = MySelf.name()?.trim() ?: ""
            val userId = MySelf.userId() ?: ""
            val bio = MySelf.bio()?.trim() ?: ""

            if (listOf(name, userId).any { it.isEmpty() }) {
                showToast("No profile to share", requireContext())
                return@setOnClickListener
            }

            val fingerprint = com.bolimot.mindtheclub.crypto.KeyManager.getMyPublicKeyFingerprint() ?: ""
            val payload = "mtc;$name;$userId;$bio;$fingerprint"
            startActivity(Intent(requireContext(), InviteActivity::class.java).putExtra("payload", payload))
        }

        lifecycleScope.launch {
            peersAdapter.loadStateFlow.collectLatest { loadStates ->
                // Clubby is filtered out of the adapter, so an empty adapter means
                // no real contacts — exactly when the empty state should show.
                val isEmpty = loadStates.refresh is LoadState.NotLoading && peersAdapter.itemCount == 0
                val visibility = if (isEmpty) View.VISIBLE else View.GONE
                inviteFriendButton.visibility = visibility
                emptyListText.visibility = visibility

                // Peer-table changes (new AI message bumps lastMessageAt) land here
                // too — keep the pinned row's preview in sync.
                refreshClubbyRow()
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
