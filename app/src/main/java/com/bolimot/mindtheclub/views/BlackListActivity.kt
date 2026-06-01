package com.bolimot.mindtheclub.views

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.BlockedUsersAdapter
import com.bolimot.mindtheclub.dialogs.UnblockPeerDialog
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.start.BaseActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class BlackListActivity : BaseActivity(), UnblockPeerDialog.UnblockPeerListener {

    private lateinit var adapter: BlockedUsersAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_black_list)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val recyclerView: RecyclerView = findViewById(R.id.blockedList)
        emptyView = findViewById(R.id.emptyView)

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.black_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = BlockedUsersAdapter { blockedUser ->
            val dialog = UnblockPeerDialog.newInstance(blockedUser.userId, blockedUser.name)
            dialog.show(supportFragmentManager, "unblockPeer")
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        loadList()
    }

    private fun loadList() {
        lifecycleScope.launch {
            val list = getBlockedUserRepository(this@BlackListActivity).getAllBlocked()
            adapter.submitList(list)
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onPeerUnblocked() {
        loadList()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
