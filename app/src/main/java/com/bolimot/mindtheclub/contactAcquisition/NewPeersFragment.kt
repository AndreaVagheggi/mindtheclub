package com.bolimot.mindtheclub.contactAcquisition

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.ReceivedRequest
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.peer.PeerRepository
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.viewModel.PeerViewModel
import com.bolimot.mindtheclub.viewModel.PeerViewModelFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class NewPeersFragment : Fragment(), NewPeersAdapter.OnItemClickListener {
    private lateinit var viewModel: PeerViewModel
    private lateinit var peersAdapter: NewPeersAdapter
    private val db = FirebaseFirestore.getInstance()
    private val localUserId = MySelf.userId()

    override fun onItemClick(request: ReceivedRequest) {
        debugLine("onItemClick", "New Peer clicked")

        if (peersAdapter.isAnyPeerSelected()) {
            peersAdapter.toggleSelection(request.userId)
            return
        }

        val intent = Intent(requireContext(), NewPeerView::class.java).apply {
            putExtra("userId", request.userId)
            putExtra("name", request.name)
            putExtra("bio", request.bio)
            putExtra("picture", request.picture)
            putExtra("fingerprint", request.fingerprint)
        }

        startActivity(intent)
    }

    override fun onItemLongClick(request: ReceivedRequest): Boolean {
        debugLine("onItemLongClick", "Peer long clicked")
        peersAdapter.toggleSelection(request.userId)


        if (peersAdapter.isAnyPeerSelected()) {
            debugLine("onItemLongClick", "Peers selected")
        } else {
            debugLine("onItemLongClick", "No peer selected")
        }
        return true
    }

    override fun isAnyPeerSelected(): Boolean {
        return peersAdapter.isAnyPeerSelected()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.new_peers_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isScrollingDownNotified = false
        val isScrollingUpNotified = false

        peersAdapter = NewPeersAdapter(this, false)

        val recyclerView = view.findViewById<RecyclerView>(R.id.contactList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = peersAdapter
        }

        val peerRepository =
            PeerRepository(DatabaseProvider.provideDatabase(requireContext()).peerDao())
        val factory = PeerViewModelFactory(requireActivity().application, peerRepository)
        viewModel = ViewModelProvider(this, factory)[PeerViewModel::class.java]

        setupFirestoreListener()

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && !isScrollingDownNotified) {
                    makeNavigationBarTransparent()
                } else if (dy < 0 && !isScrollingUpNotified) {
                    revertNavigationBarTransparency()
                }
            }
        })
    }

    private fun setupFirestoreListener() {
        if (localUserId == null) {
            debugLine("setupFirestoreListener", "Local user ID is null, cannot load requests.")
            return
        }

        val requestsCollectionRef = db.collection("users").document(localUserId)
            .collection("requests")

        requestsCollectionRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                debugLine("setupFirestoreListener", "Listen failed: $error")
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val requestsList = snapshot.documents.mapNotNull { doc ->
                    if (doc.id == "received") return@mapNotNull null

                    try {
                        val isEnc = doc.getBoolean("enc") == true
                        fun unseal(v: String?): String? =
                            if (isEnc && v != null && v != com.bolimot.mindtheclub.tools.NO_PICTURE)
                                com.bolimot.mindtheclub.crypto.KeyManager.decrypt(v)
                            else v

                        ReceivedRequest(
                            userId = doc.id,
                            name = unseal(doc.getString("name")),
                            bio = unseal(doc.getString("bio")),
                            picture = unseal(doc.getString("picture")),
                            fingerprint = if (isEnc) unseal(doc.getString("fingerprint")) else null
                        )
                    } catch (e: Exception) {
                        debugLine(
                            "setupFirestoreListener",
                            "Failed to parse request for ${doc.id}: $e"
                        )
                        null
                    }
                }

                val context = context ?: return@addSnapshotListener
                lifecycleScope.launch {
                    val blockedRepo = getBlockedUserRepository(context)
                    val filteredList = requestsList.filter { !blockedRepo.isBlocked(it.userId) }
                    peersAdapter.submitList(filteredList)
                }

            } else {
                debugLine("setupFirestoreListener", "Snapshot is null.")
                peersAdapter.submitList(emptyList())
            }
        }
    }

    private fun revertNavigationBarTransparency() {
        val color = context?.let { ContextCompat.getColor(it, R.color.mtc_opaque) } ?: return
        @Suppress("DEPRECATION")
        activity?.window?.navigationBarColor = color
    }

    private fun makeNavigationBarTransparent() {
        val color = context?.let { ContextCompat.getColor(it, R.color.white_transparent) } ?: return
        @Suppress("DEPRECATION")
        activity?.window?.navigationBarColor = color
    }
}