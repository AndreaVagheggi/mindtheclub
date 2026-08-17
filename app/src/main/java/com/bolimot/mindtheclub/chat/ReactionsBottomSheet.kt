package com.bolimot.mindtheclub.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.ReactionItem
import com.bolimot.mindtheclub.adapters.ReactionsAdapter
import com.bolimot.mindtheclub.database.reaction.Reaction
import com.bolimot.mindtheclub.database.reaction.ReactionManager
import com.bolimot.mindtheclub.database.reaction.groupByEmoji
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.sending.sendReaction
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/**
 * Who reacted to a message and with what.
 *
 * Opened by tapping the pill under a bubble. The tabs filter by emoji, mirroring the counts the
 * pill collapses, and your own row withdraws your reaction.
 */
class ReactionsBottomSheet : BottomSheetDialogFragment() {

    private val messageId: String
        get() = arguments?.getString(ARG_MESSAGE_ID).orEmpty()

    private lateinit var adapter: ReactionsAdapter
    private lateinit var tabs: TabLayout

    private var allItems: List<ReactionItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_reactions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ReactionsAdapter { removeMyReaction() }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewReactions)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        tabs = view.findViewById(R.id.reactionTabs)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = applyFilter(tab.tag as? String)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val reactions = ReactionManager.reactionsOf(messageId)
            if (reactions.isEmpty()) {
                dismiss()
                return@launch
            }

            allItems = toItems(reactions)
            buildTabs(reactions)
            applyFilter(null)
        }
    }

    private suspend fun toItems(reactions: List<Reaction>): List<ReactionItem> {
        val peerViewModel = getPeerViewModel()
        val myUserId = MySelf.userId()

        return reactions.map { reaction ->
            val isMe = reaction.reactorUserId == myUserId
            val peer = if (isMe) null else peerViewModel.getPeer(reaction.reactorUserId)

            ReactionItem(
                userId = reaction.reactorUserId,
                name = if (isMe) {
                    getString(R.string.you)
                } else {
                    peer?.name?.takeIf { it.isNotEmpty() } ?: getString(R.string.member)
                },
                // Own row: the picture cannot come from the peer table, we are not
                // one of our own contacts, so getPeer would always return null.
                // MySelf holds it, the same source note-to-self and the backup use.
                picture = if (isMe) MySelf.pictureUri() else peer?.picture,
                emoji = reaction.emoji,
                isMe = isMe
            )
        }
    }

    private fun buildTabs(reactions: List<Reaction>) {
        tabs.removeAllTabs()

        tabs.addTab(
            tabs.newTab()
                .setText(getString(R.string.reactions_all, reactions.size))
                .setTag(null)
        )

        reactions.groupByEmoji(MySelf.userId()).forEach { group ->
            tabs.addTab(
                tabs.newTab()
                    .setText("${group.emoji} ${group.count}")
                    .setTag(group.emoji)
            )
        }
    }

    private fun applyFilter(emoji: String?) {
        adapter.submitList(
            if (emoji == null) allItems else allItems.filter { it.emoji == emoji }
        )
    }

    private fun removeMyReaction() {
        val myUserId = MySelf.userId() ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val message = getMessageRepository(App.context()).getMessage(messageId) ?: return@launch

            ReactionManager.apply(messageId, myUserId, "", System.currentTimeMillis())
            sendReaction(message, "")
            dismiss()
        }
    }

    companion object {
        private const val ARG_MESSAGE_ID = "messageId"
        private const val TAG = "ReactionsBottomSheet"

        fun show(fragmentManager: FragmentManager, messageId: String) {
            ReactionsBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_MESSAGE_ID, messageId) }
            }.show(fragmentManager, TAG)
        }
    }
}
