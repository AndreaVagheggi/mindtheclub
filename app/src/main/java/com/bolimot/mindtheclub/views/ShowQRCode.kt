package com.bolimot.mindtheclub.views


import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.loadBitmap
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class ShowQRCode : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.show_qrcode)

        val qrCode: ImageView = findViewById(R.id.qrCode)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)

        setSupportActionBar(toolbar)

        supportActionBar?.title = "${MySelf.name()} QR Code"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        intent?.getStringExtra("qrCode")?.let {
            lifecycleScope.launch {
                val bitmap = loadBitmap(it.toUri(), this@ShowQRCode)
                qrCode.setImageBitmap(bitmap)
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
}