package com.bolimot.mindtheclub.contactAcquisition

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dialogs.BlockPeerDialog
import com.bolimot.mindtheclub.dialogs.DeletePeerDialog
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.BaseActivity
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class NewPeerView : BaseActivity() , DeletePeerDialog.DeletePeerListener, BlockPeerDialog.BlockPeerListener{
    private lateinit var profilePic: ImageView
    private lateinit var profilePicContainer: FrameLayout
    private lateinit var rootView: View

    private var peerUserId: String = ""

    override fun shouldApplyGlobalInsets(): Boolean {
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.new_peer_view)

        val pictureString = intent?.getStringExtra("picture")?.toUri()
        val userId= intent?.getStringExtra("userId") ?: return
        peerUserId = userId
        val name= intent?.getStringExtra("name")
        val bio= intent?.getStringExtra("bio")

        profilePic = findViewById(R.id.profilePic)
        profilePicContainer = findViewById(R.id.profilePicContainer)
        rootView = findViewById(R.id.nestedContainer)

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val nameView: TextView = findViewById(R.id.name)
        val bioView: TextView = findViewById(R.id.bio)

        val appBarLayout = findViewById<View>(R.id.appBarLayout)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            appBarLayout.updatePadding(top = bars.top)

            bottomNavigationView.updatePadding(bottom = bars.bottom)

            insets
        }

        nameView.text = name
        bioView.text = bio

        Glide.with(this)
            .load(pictureString)
            .error(R.drawable.peer)
            .into(profilePic)

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {

                R.id.accept -> {
                    lifecycleScope.launch {
                        debugLine("NewPeerView", "Accepting new contact")
                        acceptNewContact(this@NewPeerView, userId, name, bio, pictureString, this@NewPeerView)
                    }
                    true
                }
                R.id.reject -> {
                    lifecycleScope.launch {
                        removeRequestFromFirestore(userId)
                    }
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
        lifecycleScope.launch {
            removeRequestFromFirestore(peerUserId)
        }
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