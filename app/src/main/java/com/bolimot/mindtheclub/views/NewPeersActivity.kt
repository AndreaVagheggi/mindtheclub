package com.bolimot.mindtheclub.views

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.contactAcquisition.NewPeersFragment
import com.bolimot.mindtheclub.start.BaseActivity

class NewPeersActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_peers)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.title = getString(R.string.new_contact_requests)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, NewPeersFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}