package com.bolimot.mindtheclub.views

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.viewpager2.widget.ViewPager2
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.ImagesFragmentAdapter
import com.bolimot.mindtheclub.fragments.ImageSelectionManager
import com.bolimot.mindtheclub.fragments.ImagesCameraFragment
import com.bolimot.mindtheclub.fragments.ImagesGalleryFragment
import com.bolimot.mindtheclub.start.BaseActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class ImagesTab : BaseActivity(),   ImagesGalleryFragment.OnImageSelectedListener,
    ImagesCameraFragment.OnImageSelectedListener {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ImageSelectionManager.selectedImages.clear()

        val multipleSelection = intent.getBooleanExtra("multipleSelection", false)

        setContentView(R.layout.media_tab)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.title = getString(R.string.images)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewPager.offscreenPageLimit = 2
        viewPager.adapter = ImagesFragmentAdapter(supportFragmentManager, lifecycle, 2, multipleSelection)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) getString(R.string.gallery) else getString(R.string.camera)
        }.attach()

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

    override fun onImageSelected(uri: Uri) {
        val resultIntent = Intent()
        resultIntent.data = uri
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onMultipleImageSelected(uris: List<Uri>) {
        val resultIntent = Intent()
        val arrayUris = ArrayList<Uri>(uris)
        resultIntent.putParcelableArrayListExtra("uriList", arrayUris)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}