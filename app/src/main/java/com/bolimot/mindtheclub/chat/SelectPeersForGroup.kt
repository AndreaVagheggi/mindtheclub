package com.bolimot.mindtheclub.chat

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.PeersAdapter
import com.bolimot.mindtheclub.assistant.AiAssistant
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.NoteToSelf
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Contact
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SelectPeersForGroup : BaseActivity(), PeersAdapter.OnItemClickListener {

    companion object {
        const val EXTRA_INCLUDED = "includedUserIds"
        const val EXTRA_MAX_SELECTION = "maxSelection"
        const val EXTRA_TITLE = "screenTitle"
        const val RESULT_SELECTED = "selectedPeers"
    }

    private lateinit var peersAdapter: PeersAdapter
    private lateinit var fabContainer: FrameLayout
    private var excludedUserIds: List<String> = emptyList()

    /**
     * When set, only these peers are offered. Adding members to a group works by
     * subtraction (everyone except who is already in), but calling a few people
     * inside a large group works by inclusion, and a 150 member group makes the
     * difference between the two very visible.
     */
    private var includedUserIds: List<String> = emptyList()

    /** 0 means no limit. A group call caps the room, so the picker caps too. */
    private var maxSelection: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.forward_to_peer)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title =
            intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.add_members)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        excludedUserIds = intent.getStringArrayListExtra("excludedUserIds") ?: emptyList()
        includedUserIds = intent.getStringArrayListExtra(EXTRA_INCLUDED) ?: emptyList()
        maxSelection = intent.getIntExtra(EXTRA_MAX_SELECTION, 0)

        peersAdapter = PeersAdapter(this@SelectPeersForGroup, this, true, this)

        findViewById<RecyclerView>(R.id.peer_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = peersAdapter
        }

        fabContainer = findViewById(R.id.fab_container)
        fabContainer.scaleX = 0f
        fabContainer.scaleY = 0f

        findViewById<ImageButton>(R.id.send).setOnClickListener {
            val resultIntent = Intent()
            val arrayListOfStrings = ArrayList<String>(peersAdapter.getSelectedPeersUserId())
            resultIntent.putStringArrayListExtra(RESULT_SELECTED, arrayListOfStrings)
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        val peerViewModel = getPeerViewModel()
        lifecycleScope.launch {
            peerViewModel.peers
                .map { pagingData: PagingData<Peer> ->
                    pagingData.filter { peer: Peer ->
                        val allowed = includedUserIds.isEmpty() || peer.userId in includedUserIds
                        allowed && peer.userId !in excludedUserIds && !peer.userId.startsWith("group")
                                && !AiAssistant.isAssistant(peer.userId)
                                && !NoteToSelf.isNoteToSelf(peer.userId) && peer.status == Contact.ACTIVE
                    }
                }
                .collectLatest { filteredPagingData: PagingData<Peer> ->
                    peersAdapter.submitData(filteredPagingData)
                }
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun isAnyPeerSelected(): Boolean {
        return peersAdapter.isAnyPeerSelected()
    }

    override fun onItemClick(peer: Peer) {
        val selected = peersAdapter.getSelectedPeersUserId()
        if (maxSelection > 0 && peer.userId !in selected && selected.size >= maxSelection) {
            showToast(getString(R.string.select_at_most, maxSelection), this)
            return
        }
        peersAdapter.toggleSelection(peer.userId)
        updateFabVisibility()
    }

    private fun updateFabVisibility() {
        val shouldBeVisible = peersAdapter.isAnyPeerSelected()
        val targetScale = if (shouldBeVisible) 1f else 0f
        if (fabContainer.scaleX == targetScale) return
        fabContainer.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(200)
            .start()
    }

    override fun onBlockClick(peer: Peer) {}
    override fun onRejectClick(peer: Peer) {}
    override fun onAcceptClick(peer: Peer) {}
    override fun onItemLongClick(peer: Peer): Boolean { return true }
}
