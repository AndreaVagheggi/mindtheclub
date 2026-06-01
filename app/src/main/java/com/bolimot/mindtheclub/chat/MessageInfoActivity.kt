package com.bolimot.mindtheclub.chat

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MessageInfoAdapter
import com.bolimot.mindtheclub.adapters.MessageInfoItem
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.start.BaseActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class MessageInfoActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_info)

        val messageId = intent.getStringExtra("messageId") ?: run {
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.info)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        toolbar.setTitleTextColor(android.graphics.Color.WHITE)
        toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val adapter = MessageInfoAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        observeStatuses(messageId, adapter)
    }

    private fun observeStatuses(messageId: String, adapter: MessageInfoAdapter) {
        val statusDao = DatabaseProvider.provideDatabase(applicationContext)
            .groupMessageStatusDao()
        val peerViewModel = getPeerViewModel()

        lifecycleScope.launch {
            statusDao.observeStatusesForMessage(messageId)
                .flowOn(Dispatchers.IO)
                .collectLatest { statuses ->
                    val items = statuses.map { status ->
                        val peer = peerViewModel.getPeer(status.memberUserId)
                        MessageInfoItem(
                            userId = status.memberUserId,
                            name = peer?.name ?: getString(R.string.member),
                            picture = peer?.picture,
                            deliveryStatus = status.status
                        )
                    }
                    debugLine("MessageInfo", "Loaded ${items.size} member statuses for $messageId")
                    adapter.submitList(items)
                }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
