package com.kurilee.scanner

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Collections

data class Result(
    var outputBitmap: Bitmap,
    var outputBox: Array<FloatArray>
)

class ObjectDetect {
    fun detect(byteArray: ByteArray, ortEnv: OrtEnvironment, ortSession: OrtSession): Result {
        lateinit var result: Result
//        val rawImageBytes = byteArray;
        Log.d("ObjectDetect", "shape: ${byteArray.size}")
        val shape = longArrayOf(byteArray.size.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, ByteBuffer.wrap(byteArray), shape, OnnxJavaType.UINT8)
        inputTensor.use {
            try {
                val output = ortSession.run(
                    Collections.singletonMap("image", inputTensor),
                    setOf("image_out", "scaled_box_out_next")
                )
                output.use {
                    val rawOutput = (output?.get(0)?.value) as ByteArray
                    val boxOutput = (output?.get(1)?.value) as Array<FloatArray>
                    val outputImageBitmap = byteArrayToBitmap(rawOutput)

                    result = Result(outputImageBitmap, boxOutput)
                }
            }
            catch (e: Exception) {
                Log.e("Detect", e.message, e)
            }
        }
        return result
    }

    private fun byteArrayToBitmap(data: ByteArray): Bitmap {
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }
}