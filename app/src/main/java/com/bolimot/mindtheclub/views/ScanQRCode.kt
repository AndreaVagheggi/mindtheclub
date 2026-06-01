package com.bolimot.mindtheclub.views


import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.parseQRCode
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Share
import com.google.android.material.appbar.MaterialToolbar
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanQRCode : BaseActivity(), ImageAnalysis.Analyzer {
    private lateinit var cameraPreview: PreviewView
    private lateinit var cameraProvider: ProcessCameraProvider
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var cameraSelector: CameraSelector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.scan_qrcode)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        cameraPreview = findViewById(R.id.cameraPreview)

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.add_new_contact)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        startCamera()

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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = cameraPreview.surfaceProvider
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, this)
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                debugLine("ScanQRCode", "Cannot bind camera provider: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(mediaImage, rotation)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options)
            scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val qrCode = barcodes[0].rawValue

                    if(qrCode != null) {
                        val qrCodeData = parseQRCode(qrCode)

                        qrCodeData?.let { qr ->
                            listOf(qr.name,qr.userId).takeIf { it.none { part -> part.isEmpty() } }?.let {
                                val startAppIntent = Intent(this, AppTab::class.java).apply {
                                    putExtra("sharing", Share.PROFILE)
                                    putExtra("name", qr.name)
                                    putExtra("userId", qr.userId)
                                    putExtra("bio", qr.bio)
                                    putExtra("fingerprint", qr.fingerprint)
                                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                
                                debugLine("ScanQRCode", "QR code scanned: $qrCode")

                                imageProxy.close()
                                cameraExecutor.shutdown()

                                startActivity(startAppIntent)
                                finish()

                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                debugLine("ScanQRCode", "Cannot scan QR code: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
        } else {
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}