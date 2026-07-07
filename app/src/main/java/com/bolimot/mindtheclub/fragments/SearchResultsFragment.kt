package com.bolimot.mindtheclub.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.SearchItem
import com.bolimot.mindtheclub.assistant.AiAssistant
import com.bolimot.mindtheclub.adapters.SearchResultsAdapter
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.tools.MySelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchResultsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: SearchResultsAdapter
    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.search_results_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.searchResultsList)
        emptyView = view.findViewById(R.id.emptySearchView)

        adapter = SearchResultsAdapter(
            onContactClick = { peer -> openChat(peer) },
            onMessageClick = { message, remoteUserId, peerName, peerPicture ->
                openChatAtMessage(message, remoteUserId, peerName, peerPicture)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    fun updateQuery(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            adapter.submitList(emptyList())
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            return
        }

        searchJob = lifecycleScope.launch {
            delay(250)

            val results = withContext(Dispatchers.IO) {
                performSearch(query.trim())
            }

            if (results.isEmpty()) {
                recyclerView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
            } else {
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.submitList(results)
            }
        }
    }

    private suspend fun performSearch(query: String): List<SearchItem> {
        val context = requireContext()
        val db = DatabaseProvider.provideDatabase(context)
        val peerDao = db.peerDao()
        val messageDao = db.messageDao()
        val myUserId = MySelf.userId() ?: return emptyList()

        val items = mutableListOf<SearchItem>()

        val clubbyHidden = !AiAssistant.isVisible(context)

        // --- Contacts section ---
        val matchingPeers = peerDao.searchPeers(query)
            .filterNot { clubbyHidden && AiAssistant.isAssistant(it.userId) }
        if (matchingPeers.isNotEmpty()) {
            items.add(SearchItem.SectionHeader(getString(R.string.contacts_header)))
            matchingPeers.forEach { peer ->
                items.add(SearchItem.ContactResult(peer))
            }
        }

        // --- Messages section ---
        val matchingMessages = messageDao.searchMessages(query)
            .filterNot { clubbyHidden &&
                    (AiAssistant.isAssistant(it.fromUserId) || AiAssistant.isAssistant(it.toUserId)) }
        if (matchingMessages.isNotEmpty()) {
            items.add(SearchItem.SectionHeader(getString(R.string.messages_header)))

            // Cache peer lookups to avoid repeated DB hits
            val peerCache = mutableMapOf<String, Peer?>()

            for (message in matchingMessages) {
                val remoteUserId = if (message.fromUserId == myUserId) {
                    message.toUserId
                } else {
                    message.fromUserId
                }

                val peer = peerCache.getOrPut(remoteUserId) {
                    peerDao.getPeer(remoteUserId)
                }

                val peerName = peer?.name ?: remoteUserId
                val peerPicture = peer?.picture

                items.add(
                    SearchItem.MessageResult(
                        message = message,
                        peerName = peerName,
                        peerPicture = peerPicture,
                        remoteUserId = remoteUserId
                    )
                )
            }
        }

        return items
    }

    private fun openChat(peer: Peer) {
        val intent = Intent(requireContext(), ChatScreen::class.java).apply {
            putExtra("userId", peer.userId)
            putExtra("name", peer.name)
            putExtra("bio", peer.bio)
            putExtra("picture", peer.picture)
        }
        startActivity(intent)
    }

    private fun openChatAtMessage(
        message: Message,
        remoteUserId: String,
        peerName: String,
        peerPicture: String?
    ) {
        val intent = Intent(requireContext(), ChatScreen::class.java).apply {
            putExtra("userId", remoteUserId)
            putExtra("name", peerName)
            putExtra("picture", peerPicture ?: "")
            putExtra("targetMessageId", message.messageId)
        }
        startActivity(intent)
    }
}

