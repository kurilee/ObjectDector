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

class ObjectDetect {
    fun detect(byteArray: ByteArray, ortEnv: OrtEnvironment, ortSession: OrtSession): Array<FloatArray> {
        val shape = longArrayOf(byteArray.size.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, ByteBuffer.wrap(byteArray), shape, OnnxJavaType.UINT8)
        inputTensor.use {
            try {
                val output = ortSession.run(
                    Collections.singletonMap("image", inputTensor),
                    setOf("nms_output_with_scaled_boxes_and_keypoints")
                )
                output.use {
                    val boxOutput = (output.get(0)?.value) as Array<FloatArray>
                    return boxOutput
                }
            }
            catch (e: Exception) {
                Log.e("Detect", e.message, e)
            }
        }
        return emptyArray()
    }
}