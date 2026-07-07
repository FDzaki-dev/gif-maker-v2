package com.example.gifmaker

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/** Publishes a rendered GIF file into the device's public Pictures collection. */
object PublicGifExporter {

    private const val RELATIVE_SUBFOLDER = "GifMaker"

    fun publish(context: Context, sourceFile: File, displayName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(context, sourceFile, displayName)
        } else {
            publishViaLegacyStorage(context, sourceFile, displayName)
        }
    }

    private fun publishViaMediaStore(context: Context, sourceFile: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$RELATIVE_SUBFOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val itemUri = resolver.insert(collection, values) ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            } ?: return null

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    private fun publishViaLegacyStorage(context: Context, sourceFile: File, displayName: String): Uri? {
        return try {
            val picturesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                RELATIVE_SUBFOLDER
            )
            if (!picturesDir.exists()) picturesDir.mkdirs()

            val destFile = File(picturesDir, displayName)
            sourceFile.copyTo(destFile, overwrite = true)

            var resultUri: Uri? = null
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf("image/gif")
            ) { _, scannedUri -> resultUri = scannedUri }

            resultUri ?: Uri.fromFile(destFile)
        } catch (e: Exception) {
            null
        }
    }
}
