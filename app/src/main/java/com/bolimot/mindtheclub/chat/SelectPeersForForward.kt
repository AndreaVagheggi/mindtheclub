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
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Contact
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SelectPeersForForward : BaseActivity(), PeersAdapter.OnItemClickListener {
    private lateinit var peersAdapter: PeersAdapter
    private lateinit var fabContainer: FrameLayout
    private var excludedUserId: String? = null
    // Contact shares set this: sending a contact card to the AI assistant or to
    // note-to-myself is meaningless, so those pseudo-peers are hidden regardless
    // of the "Show Clubby"/"Note to myself" toggles.
    private var excludePseudoPeers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.forward_to_peer)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = getString(R.string.forward_to_peer)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        excludedUserId = intent.getStringExtra("excludedUserId")
        excludePseudoPeers = intent.getBooleanExtra("excludePseudoPeers", false)

        peersAdapter = PeersAdapter(this@SelectPeersForForward, this, true, this)
        peersAdapter.maxSelectableCount = 3

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

            resultIntent.putStringArrayListExtra("selectedPeers", arrayListOfStrings)
            setResult(RESULT_OK, resultIntent)

            finish()
        }

        val peerViewModel = getPeerViewModel()
        lifecycleScope.launch {
            peerViewModel.peers
                .map { pagingData: PagingData<Peer> ->
                    pagingData.filter { peer: Peer ->
                        peer.userId != excludedUserId && peer.status == Contact.ACTIVE
                                && (!AiAssistant.isAssistant(peer.userId) || (!excludePseudoPeers && AiAssistant.isVisible(this@SelectPeersForForward)))
                                && (!NoteToSelf.isNoteToSelf(peer.userId) || (!excludePseudoPeers && NoteToSelf.isVisible(this@SelectPeersForForward)))
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
