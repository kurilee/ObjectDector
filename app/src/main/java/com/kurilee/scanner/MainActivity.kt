package com.kurilee.scanner

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.Buffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private lateinit var ortSession: OrtSession

    private lateinit var viewFinder: PreviewView
    private lateinit var resultImageView: ImageView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var classes:List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        classes = readClasses();

        cameraExecutor = Executors.newSingleThreadExecutor();

        // init onnxruntime
        val sessionOption: OrtSession.SessionOptions = OrtSession.SessionOptions()
        val op = OrtxPackage.getLibraryPath()
        sessionOption.registerCustomOpLibrary(op)
        ortSession = ortEnv.createSession(readModel(), sessionOption)

        viewFinder = findViewById(R.id.preview)
        resultImageView = findViewById(R.id.imageView)
        resultImageView.setImageBitmap(BitmapFactory.decodeStream(readInputImage()))

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // release onnxruntime
        ortEnv.close()
        ortSession.close()

        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        val objectDetect = ObjectDetect()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = viewFinder.surfaceProvider
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, MyImageAnalyzer { byteArray ->
                        try {
//                              val result = objectDetect.detect(readInputImage().readAllBytes(), ortEnv, ortSession)
                            val result = objectDetect.detect(byteArray, ortEnv, ortSession)
                            updateUI(result)
                            Log.d(TAG, "ok")
                        } catch (exc: Exception) {
                            Log.w(TAG, exc.message!!)
                        }
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {

            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun readModel(): ByteArray  {
        return resources.openRawResource(R.raw.yolov8n_v8).readBytes()
    }

    private fun readClasses(): List<String> {
        return resources.openRawResource(R.raw.classes).bufferedReader().readLines()
    }


    private fun readInputImage(): InputStream {
        return assets.open("test_object_detection_0.jpg")
    }

    private fun updateUI(result: Result) {
        val mutableBitmap: Bitmap = result.outputBitmap.copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(mutableBitmap)
        val paint = Paint()
        paint.color = Color.WHITE
        paint.textSize = 28f
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)

        canvas.drawBitmap(mutableBitmap, 0.0f, 0.0f, paint)
        val boxit = result.outputBox.iterator()
        while (boxit.hasNext()) {
            val box_info = boxit.next()
            canvas.drawText("%s:%.2f".format(classes[box_info[5].toInt()], box_info[4]), box_info[0] - box_info[2] / 2, box_info[1] - box_info[3] / 2, paint)
            Log.d(TAG, "%s:%.2f".format(box_info[5].toInt(), box_info[4]))
        }

        resultImageView.setImageBitmap(mutableBitmap)
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}


class MyImageAnalyzer(listener: (stream: ByteArray) -> Int): ImageAnalysis.Analyzer {

    private val listeners = ArrayList<(rect: ByteArray) -> Int>().apply { listener?.let { add(it) } }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        if (image.image == null) {
            return
        }

        if (image.format == 1) {
            val bitmap = image.toBitmap()

            val mutableBitmap: Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

            val outputStream = ByteArrayOutputStream()
            mutableBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val jpegBytes = outputStream.toByteArray()

            listeners.forEach { it(jpegBytes) }
        }

        image.close()
    }

    companion object {
        private const val TAG = "MyImageAnalyzer";
    }
}
