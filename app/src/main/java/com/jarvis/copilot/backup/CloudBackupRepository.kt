package com.jarvis.copilot.backup

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jarvis.copilot.JarvisApplication
import com.jarvis.copilot.network.RelayApiClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/**
 * Photos are physically different files per phone, so backups stay fully
 * separated by device — namespaced by the local, non-auth deviceTag (see
 * JarvisApplication). All uploads go through the relay server, which is the
 * only thing holding a Backblaze B2 key; this repository never talks to B2
 * directly. Architecture §12/§15.
 */
object CloudBackupRepository {

    private const val WIFI_ONLY_PREF_KEY = "backup_wifi_only"
    private const val AUTO_BACKUP_PREF_KEY = "auto_backup_enabled"
    private const val PREFS_NAME = "cloud_backup_prefs"
    private const val BACKFILL_WORK_NAME = "backfill_all_photos"
    private const val BACKFILL_INDEX_KEY = "backfill_saved_index"

    private var photoObserver: ContentObserver? = null

    fun isAutoBackupEnabled(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(AUTO_BACKUP_PREF_KEY, false)

    /**
     * Stores the switch's display state only. Does NOT register/unregister the
     * photo observer anymore — backup itself is always-on regardless of what
     * this shows, enforced separately via enforceAlwaysOnBackup().
     */
    fun setAutoBackup(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTO_BACKUP_PREF_KEY, enabled).apply()
    }

    /**
     * Always-on backup entry point — call once from JarvisApplication.onCreate().
     * Registers the photo observer unconditionally, independent of the switch's
     * stored preference. registerPhotoObserver() already no-ops if a photoObserver
     * is already registered, so calling this repeatedly is harmless.
     */
    fun enforceAlwaysOnBackup(context: Context) {
        registerPhotoObserver(context)
    }

    /** Event-driven — fires only when MediaStore actually changes, not a polling loop. */
    private fun registerPhotoObserver(context: Context) {
        if (photoObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri?.let { enqueueSingleUpload(context, it) }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        photoObserver = observer
    }

    private fun unregisterPhotoObserver(context: Context) {
        photoObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        photoObserver = null
    }

    private fun enqueueSingleUpload(context: Context, uri: Uri) {
        val work = OneTimeWorkRequestBuilder<PhotoBackupWorker>()
            .setInputData(workDataOf("photoUri" to uri.toString()))
            .setConstraints(buildConstraints(context))
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }

    /** One-time backfill of everything already on the device — explicit button, visible progress. */
    fun startBackfill(context: Context, photos: List<Uri>) {
        val savedIndex = getSavedBackfillIndex(context)
        val remaining = photos.drop(savedIndex)
        val requests = remaining.map { uri ->
            OneTimeWorkRequestBuilder<PhotoBackupWorker>()
                .setInputData(workDataOf("photoUri" to uri.toString()))
                .setConstraints(buildConstraints(context))
                .build()
        }
        WorkManager.getInstance(context)
            .beginUniqueWork(BACKFILL_WORK_NAME, ExistingWorkPolicy.KEEP, requests)
            .enqueue()
    }

    fun pauseBackfill(context: Context, currentIndex: Int) {
        WorkManager.getInstance(context).cancelUniqueWork(BACKFILL_WORK_NAME)
        saveBackfillIndex(context, currentIndex)
    }

    fun resumeBackfill(context: Context, photos: List<Uri>) = startBackfill(context, photos)

    fun stopBackfill(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(BACKFILL_WORK_NAME)
        saveBackfillIndex(context, 0)
    }

    private fun getSavedBackfillIndex(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(BACKFILL_INDEX_KEY, 0)

    private fun saveBackfillIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(BACKFILL_INDEX_KEY, index).apply()
    }

    /**
     * Any network — Wi-Fi or mobile data. WIFI_ONLY_PREF_KEY is intentionally
     * unused now; kept as a constant only so old stored prefs don't dangle.
     */
    private fun buildConstraints(context: Context): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
    }

    fun compressImage(original: Bitmap, targetSizeKB: Int = 500): ByteArray {
        val stream = ByteArrayOutputStream()
        var quality = 90
        do {
            stream.reset()
            original.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            quality -= 10
        } while (stream.size() > targetSizeKB * 1024 && quality > 10)
        return stream.toByteArray()
    }

    /** Lists this device's backed-up photo filenames — B2 (via the relay) is the source of truth. */
    suspend fun listBackedUpPhotos(context: Context): List<String> {
        val deviceTag = (context.applicationContext as JarvisApplication).deviceTag()
        return RelayApiClient.api.listPhotos(deviceTag).filenames
    }

    suspend fun deleteFromBackup(context: Context, fileName: String) {
        val deviceTag = (context.applicationContext as JarvisApplication).deviceTag()
        RelayApiClient.api.deletePhoto(filename = fileName, deviceTag = deviceTag)
    }

    /** Uploads one already-compressed photo to B2 via the relay server. */
    suspend fun uploadPhoto(context: Context, fileName: String, compressed: ByteArray) {
        val deviceTag = (context.applicationContext as JarvisApplication).deviceTag()
        val photoPart = MultipartBody.Part.createFormData(
            "photo", fileName, compressed.toRequestBody("image/jpeg".toMediaTypeOrNull())
        )
        RelayApiClient.api.uploadPhoto(
            photo = photoPart,
            deviceTag = deviceTag.toRequestBody("text/plain".toMediaTypeOrNull()),
            filename = fileName.toRequestBody("text/plain".toMediaTypeOrNull())
        )
    }
}

class PhotoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uriString = inputData.getString("photoUri") ?: return Result.failure()
        val uri = Uri.parse(uriString)
        return try {
            val bitmap = applicationContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return Result.failure()

            val compressed = CloudBackupRepository.compressImage(bitmap)
            // uri.lastPathSegment is a bare MediaStore row ID with no extension
            // (e.g. "482"), so it's safe to append ".jpg" without risking a
            // double extension like "IMG_20260101.jpg.jpg".
            val fileName = "${uri.lastPathSegment ?: System.currentTimeMillis().toString()}.jpg"

            CloudBackupRepository.uploadPhoto(applicationContext, fileName, compressed)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
