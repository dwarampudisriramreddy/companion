package com.dark.tool_neuron.global

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Base64

object ImageUtils {

    fun downloadImage(context: Context, base64Image: String, fileName: String = "Neuron_Image_${System.currentTimeMillis()}.jpg") {
        try {
            val bytes = Base64.getDecoder().decode(base64Image)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Companion")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri != null) {
                resolver.openOutputStream(imageUri).use { outputStream ->
                    if (outputStream != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }
                Toast.makeText(context, "Image saved to Gallery", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareImage(context: Context, base64Image: String) {
        try {
            val bytes = Base64.getDecoder().decode(base64Image)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return

            val cachePath = File(context.cacheDir, "shared_images")
            cachePath.mkdirs()
            val file = File(cachePath, "shared_image_${System.currentTimeMillis()}.jpg")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            stream.close()

            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(contentUri, context.contentResolver.getType(contentUri))
                putExtra(Intent.EXTRA_STREAM, contentUri)
                type = "image/jpeg"
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to share image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
