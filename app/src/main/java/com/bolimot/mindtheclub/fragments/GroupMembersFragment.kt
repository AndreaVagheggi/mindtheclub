package com.bolimot.mindtheclub.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MembersAdapter
import com.bolimot.mindtheclub.dataModels.ReceivedRequest
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.peer.PeerRepository
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.resizeBitmap
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.viewModel.PeerViewModel
import com.bolimot.mindtheclub.viewModel.PeerViewModelFactory
import com.bolimot.mindtheclub.views.GroupMembersActivity
import com.bolimot.mindtheclub.views.ImagesTab
import com.bolimot.mindtheclub.views.PeerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import com.bolimot.mindtheclub.views.ProfilePictureViewActivity

class GroupMembersFragment(private val groupId: String) : Fragment(), MembersAdapter.OnItemClickListener {

    private lateinit var viewModel: PeerViewModel
    private lateinit var membersAdapter: MembersAdapter
    private val db = FirebaseFirestore.getInstance()
    private val localUserId = MySelf.userId()

    private var currentMemberUserIds: List<String> = emptyList()
    private var groupName: String = ""
    private var isAdmin = false
    private var currentPictureUrl: String? = null

    private lateinit var groupPic: ShapeableImageView
    private lateinit var groupPicContainer: View
    private lateinit var groupPicEditIcon: View
    private lateinit var groupNameEditText: TextInputEditText
    private lateinit var groupNameInputLayout: TextInputLayout
    private lateinit var membersLabel: View

    private val getImageResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val imageUri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch {
                uploadGroupPicture(imageUri)
            }
        }
    }

    override fun onItemClick(request: ReceivedRequest) {
        debugLine("GroupMembers", "Member clicked: ${request.userId}")

        if (membersAdapter.isAnyPeerSelected()) {
            membersAdapter.toggleSelection(request.userId)
            return
        }

        val intent = Intent(requireContext(), PeerView::class.java).apply {
            putExtra("userId", request.userId)
            putExtra("name", request.name)
            putExtra("bio", request.bio)
            putExtra("picture", request.picture)
            putExtra("fromChat", true)
        }
        startActivity(intent)
    }

    override fun onItemLongClick(request: ReceivedRequest): Boolean {
        debugLine("GroupMembers", "Member long clicked")
        membersAdapter.toggleSelection(request.userId)
        return true
    }

    override fun onBlockClick(request: ReceivedRequest) {}
    override fun onRejectClick(request: ReceivedRequest) {}
    override fun onAcceptClick(request: ReceivedRequest) {}

    override fun isAnyPeerSelected(): Boolean {
        return membersAdapter.isAnyPeerSelected()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.members_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        membersAdapter = MembersAdapter(this, false)

        groupPic = view.findViewById(R.id.groupPic)
        groupPicContainer = view.findViewById(R.id.groupPicContainer)
        groupPicEditIcon = view.findViewById(R.id.groupPicEditIcon)
        groupNameEditText = view.findViewById(R.id.groupNameEditText)
        groupNameInputLayout = view.findViewById(R.id.groupNameInputLayout)
        membersLabel = view.findViewById(R.id.membersLabel)

        view.findViewById<RecyclerView>(R.id.contactList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = membersAdapter
        }

        val peerRepository = PeerRepository(DatabaseProvider.provideDatabase(requireContext()).peerDao())
        val factory = PeerViewModelFactory(requireActivity().application, peerRepository)
        viewModel = ViewModelProvider(this, factory)[PeerViewModel::class.java]

        groupPicContainer.setOnClickListener {
            val uri = currentPictureUrl
            if (!uri.isNullOrEmpty()) {
                val viewIntent = Intent(requireContext(), ProfilePictureViewActivity::class.java).apply {
                    putExtra("pictureUri", uri)
                    putExtra("title", groupName)
                }
                startActivity(viewIntent)
            }
        }

        groupPicEditIcon.setOnClickListener {
            val intent = Intent(requireContext(), ImagesTab::class.java)
            getImageResult.launch(intent)
        }

        groupNameEditText.imeOptions = EditorInfo.IME_ACTION_DONE
        groupNameEditText.maxLines = 1

        groupNameEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val newName = v.text.toString().trim()
                if (newName.isNotEmpty() && newName != groupName) {
                    saveGroupName(newName)
                }
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                view.findViewById<View>(R.id.constraintLayout)?.requestFocus()
                true
            } else {
                false
            }
        }

        setupFirestoreListener()
    }

    private fun setupFirestoreListener() {
        if (localUserId == null) {
            debugLine("GroupMembers", "Local user ID is null.")
            return
        }

        val groupRef = db.collection("groups").document(groupId)

        groupRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                debugLine("GroupMembers", "Listen failed: $error")
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                groupName = snapshot.getString("name") ?: ""

                // Load group picture from Firestore URL
                val pictureUrl = snapshot.getString("picture")
                currentPictureUrl = pictureUrl

                if (!pictureUrl.isNullOrEmpty() && isAdded) {
                    Glide.with(this)
                        .load(pictureUrl)
                        .placeholder(R.drawable.group_placeholder)
                        .error(R.drawable.group_placeholder)
                        .into(groupPic)
                }

                @Suppress("UNCHECKED_CAST")
                val membersMap = snapshot.get("members") as? Map<String, String> ?: return@addSnapshotListener

                // Determine admin status and show/hide edit controls
                isAdmin = membersMap[localUserId] == "admin"
                showHeader()

                groupNameEditText.setText(groupName)
                groupNameEditText.isEnabled = isAdmin
                groupPicEditIcon.visibility = if (isAdmin) View.VISIBLE else View.GONE

                val memberUserIds = membersMap.keys.filter { it != localUserId }
                currentMemberUserIds = memberUserIds

                debugLine("GroupMembers", "Found ${memberUserIds.size} members (excluding self)")

                lifecycleScope.launch {
                    val membersList = withContext(Dispatchers.IO) {
                        memberUserIds.map { userId ->
                            val peer = viewModel.getPeer(userId)
                            if (peer != null) {
                                ReceivedRequest(
                                    userId = peer.userId,
                                    name = peer.name,
                                    bio = peer.bio,
                                    picture = peer.picture
                                )
                            } else {
                                debugLine("GroupMembers", "Peer not found locally for $userId")
                                ReceivedRequest(
                                    userId = userId,
                                    name = membersMap[userId] ?: "Unknown",
                                    bio = null,
                                    picture = null
                                )
                            }
                        }
                    }
                    membersAdapter.submitList(membersList)

                    // Update local Peer with latest picture URL from Firestore
                    if (!pictureUrl.isNullOrEmpty()) {
                        withContext(Dispatchers.IO) {
                            viewModel.updatePeerPicture(groupId, pictureUrl)
                        }
                    }
                }
            } else {
                debugLine("GroupMembers", "Group document does not exist.")
                membersAdapter.submitList(emptyList())
            }
        }
    }

    private fun showHeader() {
        groupPicContainer.visibility = View.VISIBLE
        groupNameInputLayout.visibility = View.VISIBLE
        membersLabel.visibility = View.VISIBLE
    }

    private suspend fun uploadGroupPicture(imageUri: android.net.Uri) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) {
                    debugLine("GroupMembers", "Failed to decode image")
                    return@withContext
                }

                val resized = resizeBitmap(bitmap)
                val baos = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val data = baos.toByteArray()

                val storageRef = FirebaseStorage.getInstance().reference
                val pictureRef = storageRef.child("group_pictures/${groupId}.jpg")

                pictureRef.putBytes(data).await()
                val downloadUrl = pictureRef.downloadUrl.await().toString()

                debugLine("GroupMembers", "Group picture uploaded: $downloadUrl")

                // Save URL to Firestore group document
                db.collection("groups").document(groupId)
                    .update("picture", downloadUrl)
                    .await()

                // Update local Peer picture
                viewModel.updatePeerPicture(groupId, downloadUrl)

                debugLine("GroupMembers", "Group picture URL saved to Firestore and local DB")

                // Glide refresh happens automatically via the snapshot listener

            } catch (e: Exception) {
                debugLine("GroupMembers", "Error uploading group picture: ${e.message}")
            }
        }
    }

    private fun saveGroupName(newName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db.collection("groups").document(groupId)
                    .update("name", newName)
                    .await()

                viewModel.updatePeerName(groupId, newName)

                groupName = newName

                withContext(Dispatchers.Main) {
                    (activity as? GroupMembersActivity)?.updateToolbarTitle(newName)
                }

                debugLine("GroupMembers", "Group name updated to: $newName")
            } catch (e: Exception) {
                debugLine("GroupMembers", "Error updating group name: ${e.message}")
            }
        }
    }

    fun getCurrentMemberUserIds(): List<String> {
        return currentMemberUserIds
    }

    fun getSelectedMemberUserIds(): List<String> {
        return membersAdapter.getSelectedPeersUserId()
    }

    fun addMembers(userIds: List<String>) {
        val currentTotal = currentMemberUserIds.size + 1
        val newTotal = currentTotal + userIds.size
        if (newTotal > 63) {
            showToast(getString(R.string.group_member_limit_exceeded, 63), requireContext())
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val groupRef = db.collection("groups").document(groupId)
                val updates = hashMapOf<String, Any>()
                userIds.forEach { userId ->
                    updates["members.$userId"] = "member"
                }
                groupRef.update(updates).await()

                debugLine("GroupMembers", "Added ${userIds.size} members to group $groupId")

                userIds.forEach { userId ->
                    notifyRemotePeer(userId, groupId, Notify.GROUP, groupName)
                }
            } catch (e: Exception) {
                debugLine("GroupMembers", "Error adding members: ${e.message}")
            }
        }
    }

    fun removeMembers(userIds: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val groupRef = db.collection("groups").document(groupId)
                val updates = hashMapOf<String, Any>()
                userIds.forEach { userId ->
                    updates["members.$userId"] = FieldValue.delete()
                }
                groupRef.update(updates).await()

                debugLine("GroupMembers", "Removed ${userIds.size} members from group $groupId")

                userIds.forEach { userId ->
                    notifyRemotePeer(userId, groupId, Notify.GROUP_REMOVED, groupName)
                }
            } catch (e: Exception) {
                debugLine("GroupMembers", "Error removing members: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                membersAdapter.clearSelection()
            }
        }
    }
}