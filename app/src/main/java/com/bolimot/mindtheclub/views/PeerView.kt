package com.bolimot.mindtheclub.views

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.peer.PeerRepository
import com.bolimot.mindtheclub.dialogs.BlockPeerDialog
import com.bolimot.mindtheclub.dialogs.DeletePeerDialog
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.loadBitmap
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class PeerView : BaseActivity() , DeletePeerDialog.DeletePeerListener, BlockPeerDialog.BlockPeerListener{
    private lateinit var profilePic: ImageView
    private lateinit var profilePicContainer: FrameLayout
    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {

        val picture: Bitmap?

        super.onCreate(savedInstanceState)
        setContentView(R.layout.peer_view)

        val pictureString = intent?.getStringExtra("picture")?.toUri()
        val userId= intent?.getStringExtra("userId") ?: return

        if (userId.startsWith("group")) {
            val intent = Intent(this, GroupMembersActivity::class.java).apply {
                putExtra("userId", userId)
                putExtra("name", intent?.getStringExtra("name"))
            }
            startActivity(intent)
            finish()
            return
        }

        val name= intent?.getStringExtra("name")
        val bio= intent?.getStringExtra("bio")
        val fromChat = intent?.getBooleanExtra("fromChat", false) ?: false


        picture = if(pictureString != null) {
            if (!userId.startsWith("group")) {
                loadBitmap(pictureString, this)
            } else {
                null
            }
        } else {
            null
        }

        profilePic = findViewById(R.id.profilePic)
        profilePicContainer = findViewById(R.id.profilePicContainer)
        rootView = findViewById(R.id.nestedContainer)

        val fab = findViewById<View>(R.id.remove_pending)
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val nameView: TextView = findViewById(R.id.name)
        val bioView: TextView = findViewById(R.id.bio)

        picture?.let{
            profilePic.setImageBitmap(picture)
        }

        profilePicContainer.setOnClickListener {
            if (pictureString != null) {
                val viewIntent = Intent(this, ProfilePictureViewActivity::class.java).apply {
                    putExtra("pictureUri", pictureString.toString())
                    putExtra("title", name ?: "")
                }
                startActivity(viewIntent)
            }
        }

        if(bio == getString(R.string.pending)) {
            fab.visibility = View.VISIBLE
            bottomNavigationView.visibility = View.GONE
        } else {
            fab.visibility = View.GONE
            bottomNavigationView.visibility = View.VISIBLE
        }

        fab.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val localUserId = MySelf.userId() ?: return@launch

                    withContext(Dispatchers.IO) {
                        FirebaseFirestore.getInstance()
                            .collection("users").document(userId)
                            .collection("requests").document(localUserId)
                            .delete()
                            .await()

                        try {
                            FirebaseStorage.getInstance().reference
                                .child("profile_pictures/${localUserId}_${userId}.jpg")
                                .delete()
                                .await()
                        } catch (e: Exception) {
                            debugLine("PeerView", "Picture cleanup failed (may not exist): ${e.message}")
                        }

                        val peerDao = DatabaseProvider.provideDatabase(this@PeerView).peerDao()
                        PeerRepository(peerDao).deletePeerByUserId(userId)
                    }

                    setResult(RESULT_OK)
                    finish()
                } catch (e: Exception) {
                    debugLine("PeerView", "Error removing pending peer: ${e.message}")
                }
            }
        }

        nameView.text = name
        bioView.text = bio

        if(!fromChat) {
            bottomNavigationView.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.delete -> {

                        val dialog = DeletePeerDialog.newInstance(
                            userId,
                            name,
                            bio,
                            pictureString.toString()
                        )
                        dialog.show(supportFragmentManager, "deletePeer")
                        true
                    }

                    R.id.block -> {
                        val dialog = BlockPeerDialog.newInstance(
                            userId,
                            name,
                            pictureString?.toString()
                        )
                        dialog.show(supportFragmentManager, "blockPeer")
                        true
                    }

                    else -> false
                }
            }
        } else {
            bottomNavigationView.visibility = View.GONE
        }

        setSupportActionBar(toolbar)

        supportActionBar?.title = name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)
    }

    override fun onPeerBlocked() {
        setResult(RESULT_OK)
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPeerDeleted() {
        setResult(RESULT_OK)
        finish()
    }
}