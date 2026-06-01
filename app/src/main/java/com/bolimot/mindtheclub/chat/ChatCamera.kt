package com.bolimot.mindtheclub.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.start.BaseActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatCamera : BaseActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnCaptureVideo: ImageButton
    private lateinit var btnCaptureStop: ImageButton
    private lateinit var btnSwitch: ImageButton
    private lateinit var btnSwitchPhoto: ImageButton
    private lateinit var btnSwitchVideo: ImageButton
    private lateinit var userId: String
    private lateinit var name: String

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var peerPicturePath: String? = null
    private var camera: Camera? = null

    private var isVideoMode = false

    private val sendLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_camera)

        userId = intent?.getStringExtra("userId") ?: return
        name = intent?.getStringExtra("name") ?: return
        peerPicturePath = intent?.getStringExtra("peerPicturePath")

        btnCapture = findViewById(R.id.btnCapture)
        btnCaptureVideo = findViewById(R.id.btnCaptureVideo)
        btnCaptureStop = findViewById(R.id.btnCaptureStop)
        btnSwitch = findViewById(R.id.btnSwitch)
        previewView = findViewById(R.id.previewView)
        btnSwitchPhoto = findViewById(R.id.btnSwitchPhoto)
        btnSwitchVideo = findViewById(R.id.btnSwitchVideo)

        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zoomState = cam.cameraInfo.zoomState.value ?: return false
                val currentZoomRatio = zoomState.zoomRatio
                val scale = detector.scaleFactor
                cam.cameraControl.setZoomRatio(currentZoomRatio * scale)
                return true
            }
        })

        previewView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                view.performClick()
            }
            true
        }

        btnCaptureVideo.setOnClickListener { captureOrRecord() }
        btnCapture.setOnClickListener { captureOrRecord() }
        btnCaptureStop.setOnClickListener { captureOrRecord() }
        btnSwitch.setOnClickListener { switchCamera() }
        btnSwitchPhoto.setOnClickListener { selectPhoto() }
        btnSwitchVideo.setOnClickListener { selectVideo() }

        startCamera()
    }

    private fun selectPhoto() {
        isVideoMode = false

        btnSwitchPhoto.setColorFilter(ContextCompat.getColor(this, R.color.red), PorterDuff.Mode.SRC_IN)
        btnSwitchVideo.setColorFilter(ContextCompat.getColor(this, R.color.white), PorterDuff.Mode.SRC_IN)

        startCamera()
    }

    private fun selectVideo() {
        isVideoMode = true

        btnSwitchVideo.setColorFilter(ContextCompat.getColor(this, R.color.red), PorterDuff.Mode.SRC_IN)
        btnSwitchPhoto.setColorFilter(ContextCompat.getColor(this, R.color.white), PorterDuff.Mode.SRC_IN)

        startCamera()
    }

    private fun captureOrRecord() {
        if (!isVideoMode) {
            takePhoto()
        } else {
            if (recording == null) {
                startVideoRecording()
            } else {
                stopVideoRecording()
            }
        }
    }

    private fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            cameraProvider.unbindAll()

            val preview = Preview.Builder()
                .build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            if (!isVideoMode) {
                btnCaptureVideo.visibility = View.GONE
                btnCapture.visibility = View.VISIBLE

                imageCapture = ImageCapture.Builder().build()

                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                videoCapture = null
                recording = null
            } else {
                btnCaptureVideo.visibility = View.VISIBLE
                btnCapture.visibility = View.GONE

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
                imageCapture = null
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = createMediaFile("IMG_", ".jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val intent = Intent(this@ChatCamera, SendImage::class.java).apply {
                        putExtra("userId", listOf(userId).joinToString(","))
                        putExtra("fromName", name)
                        putExtra("peerPicturePath", peerPicturePath)
                        putExtra("imagePath", savedUri.toString())
                    }
                    sendLauncher.launch(intent)
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                }
            }
        )
    }

    private fun startVideoRecording() {
        val videoCapture = videoCapture ?: return

        val videoFile = createMediaFile("VID_", ".mp4")
        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        btnCaptureVideo.visibility = View.GONE
        btnCaptureStop.visibility = View.VISIBLE

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        recording = videoCapture.output
        .prepareRecording(this, outputOptions)
        .withAudioEnabled()
        .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                }

                is VideoRecordEvent.Finalize -> {
                    if (!recordEvent.hasError()) {
                        val savedUri = Uri.fromFile(videoFile)
                        val intent = Intent(this@ChatCamera, SendVideo::class.java).apply {
                            putExtra("userId", listOf(userId).joinToString(","))
                            putExtra("fromName", name)
                            putExtra("peerPicturePath", peerPicturePath)
                            putExtra("imagePath", savedUri.toString())
                        }
                        sendLauncher.launch(intent)
                    }
                    recording = null
                }
            }
        }
    }

    private fun stopVideoRecording() {
        recording?.stop()
        btnCaptureVideo.visibility = View.VISIBLE
        btnCaptureStop.visibility = View.GONE
    }

    private fun createMediaFile(prefix: String, extension: String): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "$prefix$timeStamp$extension"
        val dir = filesDir
        return File(dir, fileName)
    }
}

