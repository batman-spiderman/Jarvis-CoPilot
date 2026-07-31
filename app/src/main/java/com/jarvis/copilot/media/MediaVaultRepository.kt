package com.jarvis.copilot.media

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhotoItem(val uri: Uri, val dateTaken: Long, val displayName: String)

object MediaVaultRepository {

    /** Requires READ_MEDIA_IMAGES (Android 13+) / READ_EXTERNAL_STORAGE (<=12), requested lazily. */
    fun queryAllPhotos(context: Context): List<PhotoItem> {
        val photos = mutableListOf<PhotoItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                photos.add(PhotoItem(uri, cursor.getLong(dateCol), cursor.getString(nameCol)))
            }
        }
        return photos
    }

    fun getPhotosGroupedByDate(photos: List<PhotoItem>, pattern: String = "yyyy-MM-dd"): Map<String, List<PhotoItem>> {
        val formatter = SimpleDateFormat(pattern, Locale.getDefault())
        return photos.groupBy { formatter.format(Date(it.dateTaken)) }
    }

    /** Simple content-hash duplicate detection. Production version should use a
     *  perceptual hash to catch resized/recompressed duplicates, not just exact bytes. */
    fun findDuplicates(context: Context, photos: List<PhotoItem>): Map<String, List<PhotoItem>> {
        val hashToPhotos = mutableMapOf<String, MutableList<PhotoItem>>()
        for (photo in photos) {
            val hash = hashImageBytes(context, photo.uri) ?: continue
            hashToPhotos.getOrPut(hash) { mutableListOf() }.add(photo)
        }
        return hashToPhotos.filter { it.value.size > 1 }
    }

    private fun hashImageBytes(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    /** Single system confirmation dialog covers the whole batch — no write permission needed. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun requestBatchDelete(activity: Activity, urisToDelete: List<Uri>, requestCode: Int) {
        val pendingIntent = MediaStore.createDeleteRequest(activity.contentResolver, urisToDelete)
        activity.startIntentSenderForResult(pendingIntent.intentSender, requestCode, null, 0, 0, 0)
    }

    /** One write confirmation dialog; after approval, files get moved via RELATIVE_PATH update. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun requestReorganizeByDate(activity: Activity, photos: List<PhotoItem>, requestCode: Int) {
        val urisToModify = photos.map { it.uri }
        val pendingIntent = MediaStore.createWriteRequest(activity.contentResolver, urisToModify)
        activity.startIntentSenderForResult(pendingIntent.intentSender, requestCode, null, 0, 0, 0)
        // After the user approves (handle result in onActivityResult), call:
        // applyReorganization(activity, photos)
    }

    fun applyReorganization(context: Context, photos: List<PhotoItem>) {
        val grouped = getPhotosGroupedByDate(photos, pattern = "yyyy-MM")
        grouped.forEach { (monthKey, items) ->
            items.forEach { photo ->
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Organized/$monthKey")
                }
                context.contentResolver.update(photo.uri, values, null, null)
            }
        }
    }
}
