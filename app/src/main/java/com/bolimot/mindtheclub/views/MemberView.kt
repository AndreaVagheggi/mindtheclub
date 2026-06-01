package com.bolimot.mindtheclub.views

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dialogs.DeletePeerDialog
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Share
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class MemberView : BaseActivity() , DeletePeerDialog.DeletePeerListener{
    private lateinit var profilePic: ImageView
    private lateinit var profilePicContainer: FrameLayout
    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.member_view)

        val pictureString = intent?.getStringExtra("picture")?.toUri()
        val userId= intent?.getStringExtra("userId") ?: return
        val name= intent?.getStringExtra("name")
        val bio= intent?.getStringExtra("bio")

        profilePic = findViewById(R.id.profilePic)
        profilePicContainer = findViewById(R.id.profilePicContainer)
        rootView = findViewById(R.id.nestedContainer)

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val nameView: TextView = findViewById(R.id.name)
        val bioView: TextView = findViewById(R.id.bio)

        nameView.text = name
        bioView.text = bio

        Glide.with(this)
            .load(pictureString)
            .error(R.drawable.peer)
            .into(profilePic)

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.accept -> {
                    val startAppIntent = Intent(this, AppTab::class.java).apply {
                        putExtra("sharing", Share.PROFILE)
                        putExtra("name", name)
                        putExtra("userId", userId)
                        putExtra("bio", bio)
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(startAppIntent)
                    finish()
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