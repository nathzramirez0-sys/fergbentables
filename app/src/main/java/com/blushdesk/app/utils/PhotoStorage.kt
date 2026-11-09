package com.blushdesk.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Turns a picked or captured image into a small file the database can point at, and cleans up after. */
interface PhotoStore {
    /** Copies [source] into private storage, downsized, and returns the copy's `file://` URI. */
    suspend fun importPhoto(source: Uri): String

    /** Deletes an image previously returned by [importPhoto]. Ignores null and anything else. */
    fun delete(imageUri: String?)
}

class PhotoStorage(private val context: Context) : PhotoStore {

    private val photosDir: File get() = File(context.filesDir, PHOTOS_DIR).also { it.mkdirs() }

    /**
     * The picker's URI is only valid for a while and the camera's file lives in the cache, so
     * neither can be stored. Instead the image is decoded at a sampled-down size (an unsampled
     * 12 MP photo is ~48 MB of bitmap), rotated according to its EXIF flag, scaled so its longest
     * side is at most [MAX_EDGE] px and written as JPEG.
     */
    override suspend fun importPhoto(source: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // With inJustDecodeBounds, decodeStream always returns null and reports the size through
        // `bounds`, so "could it be opened" has to be tracked separately from its return value.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
        if (!opened) throw IOException("Could not open the selected image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The selected file is not an image")

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight))
        }
        val decoded = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: throw IOException("Could not read the selected image")

        val exif = resolver.openInputStream(source)?.use { runCatching { ExifInterface(it) }.getOrNull() }
        val prepared = orient(decoded, exif)
        if (prepared !== decoded) decoded.recycle()

        val target = File(photosDir, "${UUID.randomUUID()}.jpg")
        try {
            FileOutputStream(target).use { prepared.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } catch (e: IOException) {
            target.delete()
            throw e
        } finally {
            prepared.recycle()
        }

        // The camera app wrote a full-size original into the cache; we have our copy, so drop it.
        File(context.cacheDir, AppFiles.CAMERA_DIR).listFiles()?.forEach { it.delete() }

        Uri.fromFile(target).toString()
    }

    override fun delete(imageUri: String?) {
        val file = AppFiles.localFile(imageUri) ?: return
        // Only ever delete inside our own photos folder, whatever string we were handed.
        if (file.canonicalFile.parentFile == photosDir.canonicalFile) file.delete()
    }

    private fun orient(source: Bitmap, exif: ExifInterface?): Bitmap {
        val matrix = Matrix()
        val rotation = exif?.rotationDegrees ?: 0
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (exif?.isFlipped == true) matrix.postScale(-1f, 1f)

        val longest = maxOf(source.width, source.height)
        if (longest > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longest
            matrix.postScale(scale, scale)
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun sampleSizeFor(longestEdge: Int): Int {
        var sample = 1
        // Stay within 2x of the target so the final scale-down keeps good quality.
        while (longestEdge / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }

    companion object {
        /** Under the app's files folder; backups copy it whole. */
        const val PHOTOS_DIR = "photos"
        private const val MAX_EDGE = 1024
        private const val JPEG_QUALITY = 85
    }
}
