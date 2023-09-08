package com.blaze.imagecompressionwm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.Timestamp
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

val TAG = "PhotoCompressionWorker"

class PhotoCompressionWorker(
    private val appContext: Context, private val params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {


            val stringuri = params.inputData.getString(KEY_CONTENT_URI)
            val compressionTresholdInBytes = params.inputData.getLong(
                KEY_COMPRESSION_THRESHOLD, 0L
            )
            val uri = Uri.parse(stringuri)
            val bytes = appContext.contentResolver.openInputStream(uri)?.use {
                it.readBytes()
            } ?: return@withContext Result.failure()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            var outputBytes: ByteArray
            var quality = 100
            do {
                val outputStream = ByteArrayOutputStream()
                outputStream.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                    outputBytes = outputStream.toByteArray()
                    quality -= (quality * 0.1).roundToInt()
                }
            } while (outputBytes.size > compressionTresholdInBytes && quality > 5)

            val file = File(appContext.cacheDir, "${params.id}.jpg")
            file.writeBytes(outputBytes)

            var imageUrl = ""

            val firebase = Firebase
            val db = firebase.firestore
            db.collection("randomcollection").document("Jef8PG0U2RNRZQ0GqlJw").get()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val result = task.result
                        val allowedToUpload = result.getBoolean("uploadFile") ?: false
                        if (allowedToUpload) {
                            val storage = firebase.storage
                            val sotrageRef = storage.reference.child("baka/${Timestamp.now()}.jpg")

                            val uploadTask = sotrageRef.putBytes(outputBytes)

                            uploadTask.continueWithTask { task ->
                                if (!task.isSuccessful) {
                                    task.exception?.let { throw it }
                                }
                                sotrageRef.downloadUrl
                            }.addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    imageUrl = task.result.toString()
                                    val data = hashMapOf(
                                        "imageUrl" to imageUrl,
                                        "lastUpdated" to Timestamp.now(),
                                    )
                                    db.collection("randomcollection")
                                        .document("Jef8PG0U2RNRZQ0GqlJw")
                                        .update(data as Map<String, Any>)
                                        .addOnCompleteListener { it ->
                                            if (it.isSuccessful) {
                                                Log.d(TAG, "doWork: Uploaded image and fields too")
                                            } else {
                                                Log.d(
                                                    TAG,
                                                    "doWork: failed to update field after upload image"
                                                )
                                            }

                                        }


                                } else {
                                    Log.d(TAG, "doWork: unable to upload url and get its url")
                                }
                            }
                        } else {
                            Log.d(TAG, "doWork: not allowed to upload")
                        }

                    } else {
                        Log.d(TAG, "doWork: failed to connect DB")
                    }

                }

            Result.success(
                workDataOf(
                    KEY_RESULT_PATH to file.absolutePath, KEY_RESULT_URL to imageUrl
                )
            )
        }

    }

    companion object {
        const val KEY_CONTENT_URI = "KEY_CONTENT_URI"
        const val KEY_COMPRESSION_THRESHOLD = "KEY_COMPRESSION_THRESHOLD"
        const val KEY_RESULT_PATH = "KEY_RESULT_PATH"
        const val KEY_RESULT_URL = "KEY_RESULT_URL"
    }
}