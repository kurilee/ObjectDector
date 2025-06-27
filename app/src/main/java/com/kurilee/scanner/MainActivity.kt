package com.kurilee.scanner

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import ai.onnxruntime.providers.NNAPIFlags
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private lateinit var ortSession: OrtSession

//    private lateinit var viewFinder: PreviewView
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
        try {
            val sessionOption: OrtSession.SessionOptions = OrtSession.SessionOptions()
            val op = OrtxPackage.getLibraryPath()
            sessionOption.registerCustomOpLibrary(op)
            sessionOption.addQnn(mapOf("htp_performance_mode" to "high_performance",
                "htp_graph_finalization_optimization_mode" to "2"))
            ortSession = ortEnv.createSession(readModel(), sessionOption)
        } catch (exc: Exception) {
            val sessionOption: OrtSession.SessionOptions = OrtSession.SessionOptions()
            val op = OrtxPackage.getLibraryPath()
            sessionOption.registerCustomOpLibrary(op)
            sessionOption.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
            ortSession = ortEnv.createSession(readModel(), sessionOption)
        }

//        viewFinder = findViewById(R.id.preview)
        resultImageView = findViewById(R.id.imageView)
//        resultImageView.setImageBitmap(BitmapFactory.decodeStream(readInputImage()))

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

//            val preview = Preview.Builder()
//                .build()
//                .also {
//                    it.surfaceProvider = viewFinder.surfaceProvider
//                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, MyImageAnalyzer { bitmap ->
                        try {
                            val byteArrayOutputStream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
                            val result = objectDetect.detect(
                                byteArrayOutputStream.toByteArray(),
                                ortEnv,
                                ortSession
                            )
                            updateUI(bitmap, result)
                        } catch (exc: Exception) {
                            resultImageView.setImageBitmap(bitmap)
//                            Log.w(TAG, exc.message!!)
                        }
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
            } catch (exc: Exception) {

            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun readModel(): ByteArray  {
        return resources.openRawResource(R.raw.yolov11n).readBytes()
    }

    private fun readClasses(): List<String> {
        return resources.openRawResource(R.raw.classes).bufferedReader().readLines()
    }


    private fun readInputImage(): InputStream {
        return assets.open("test_object_detection_0.jpg")
    }

    private fun updateUI(bitmap: Bitmap, result: Array<FloatArray>) {
        val mutableBitmap: Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(mutableBitmap)
        val paint = Paint()
        paint.color = Color.WHITE
        paint.textSize = 28f
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)

        val rectPaint = Paint()
        rectPaint.color = Color.RED
        rectPaint.style = Paint.Style.STROKE
        rectPaint.strokeWidth = 4.0f

        canvas.drawBitmap(mutableBitmap, 0.0f, 0.0f, paint)
        val boxit = result.iterator()
        while (boxit.hasNext()) {
            val box_info = boxit.next()
            canvas.drawText("%s:%.2f".format(classes[box_info[5].toInt()], box_info[4]), box_info[0] - box_info[2] / 2, box_info[1] - box_info[3] / 2 - 10, paint)
            canvas.drawRect(box_info[0] - box_info[2] / 2, box_info[1] - box_info[3] / 2, box_info[0] + box_info[2] / 2, box_info[1] + box_info[3] / 2, rectPaint)
            Log.d(TAG, "%s:%.2f".format(box_info[5].toInt(), box_info[4]))
        }

        resultImageView.setImageBitmap(mutableBitmap)
    }

//    private fun bitmapToByteArray(bitmap: Bitmap) {
//        val byteBuffer = ByteBuffer.allocate(bitmap.byteCount)
//        bitmap.copyPixelsToBuffer()
//    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}


class MyImageAnalyzer(listener: (bitmap: Bitmap) -> Unit): ImageAnalysis.Analyzer {

    private val listeners = ArrayList<(bitmap: Bitmap) -> Unit>().apply { listener?.let { add(it) } }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        if (image.image == null) {
            return
        }

        listeners.forEach { it(image.toBitmap()) }

        image.close()
    }

    companion object {
        private const val TAG = "MyImageAnalyzer";
    }
}
