package com.amresalehin.emregallery.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import android.media.MediaScannerConnection
import android.net.Uri
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Persistent background downloader worker powered by Android Jetpack WorkManager.
 * Runs as a foreground service with a unique notification ID to ensure uninterrupted downloading.
 * Incorporates byte-level progress reporting, HTTP Range resumable downloading, headers/cookies propagation,
 * and social video remuxing with yt-dlp.
 */
class MediaDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private fun getWorkerNotificationId(key: String): Int {
        return 4001 + (abs(key.hashCode()) % 50000)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: id.toString()
        val title = inputData.getString(KEY_TITLE) ?: inputData.getString(KEY_FILE_NAME) ?: "Media"
        return createSilentForegroundInfo(title, getWorkerNotificationId(itemId))
    }

    private fun createSilentForegroundInfo(title: String, notificationId: Int): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(SILENT_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    SILENT_CHANNEL_ID,
                    "Media Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Silent background media downloads"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        val notification = NotificationCompat.Builder(applicationContext, SILENT_CHANNEL_ID)
            .setContentTitle("Downloading media")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mediaUrl = inputData.getString(KEY_MEDIA_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "media_${System.currentTimeMillis()}"
        val referer = inputData.getString(KEY_REFERER)
        val itemId = inputData.getString(KEY_ITEM_ID) ?: "item_${System.currentTimeMillis()}"
        val title = inputData.getString(KEY_TITLE) ?: fileName
        val isVideo = inputData.getBoolean(KEY_IS_VIDEO, false)
        val isAudio = inputData.getBoolean(KEY_IS_AUDIO, false)
        val isStream = inputData.getBoolean(KEY_IS_STREAM, false)
        val format = inputData.getString(KEY_FORMAT) ?: ""
        val stackId = inputData.getString(KEY_STACK_ID) ?: "stack_${System.currentTimeMillis()}"
        val stackTitle = inputData.getString(KEY_STACK_TITLE) ?: "Discovered Media"
        val sourceDomain = inputData.getString(KEY_SOURCE_DOMAIN) ?: ""
        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: mediaUrl
        val sourceEngine = inputData.getString(KEY_SOURCE_ENGINE) ?: ""
        val headersJson = inputData.getString(KEY_HEADERS_JSON)

        val downloadHeaders: Map<String, String> = runCatching {
            if (!headersJson.isNullOrBlank()) {
                val obj = JSONObject(headersJson)
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.getString(k)
                }
                map
            } else emptyMap()
        }.getOrDefault(emptyMap())

        val cookieString = OkHttpDownloader.sharedCookieJar.getCookieString(mediaUrl).takeIf { it.isNotBlank() }
            ?: downloadHeaders["Cookie"]

        // Workers can be started in a fresh process with no UI: make sure the activity store is
        // loaded BEFORE reporting progress/finish, otherwise those updates are silently dropped.
        DownloadActivityManager.initialize(applicationContext)

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not set silent foreground service notification: ${e.message}")
        }

        // Fast-path: Check if this media file is already saved in the Download storage folder
        val alreadySaved = OkHttpDownloader.findExistingSavedFile(
            context = applicationContext,
            mediaUrl = mediaUrl,
            fileName = fileName,
            downloadId = itemId,
            domain = sourceDomain
        )
        if (alreadySaved != null && alreadySaved.exists() && alreadySaved.length() > 0L) {
            Log.d(TAG, "Media $title already saved at ${alreadySaved.absolutePath}, completing without download")
            val fileUri = Uri.fromFile(alreadySaved).toString()
            DownloadActivityManager.markFinished(
                itemId = itemId,
                localUri = fileUri,
                sizeBytes = alreadySaved.length()
            )
            runCatching {
                val pocketRepo = PocketRepository.getInstance(applicationContext)
                val stack = pocketRepo.bookmarks.value.firstOrNull { it.id == stackId }
                if (stack != null) {
                    val updatedItems = stack.items.map { itm ->
                        if (itm.id == itemId || itm.sourceUrl == mediaUrl) {
                            itm.copy(localUri = fileUri, sizeBytes = alreadySaved.length())
                        } else itm
                    }
                    pocketRepo.updateBookmark(stack.copy(items = updatedItems))
                }
            }
            return@withContext Result.success(
                workDataOf(
                    KEY_LOCAL_URI to alreadySaved.absolutePath,
                    KEY_SIZE_BYTES to alreadySaved.length(),
                    KEY_ITEM_ID to itemId
                )
            )
        }

        setProgress(workDataOf(KEY_PROGRESS to 0.05f, KEY_STATUS to "Initializing download for $title..."))

        try {
            var downloadedFile: File? = null
            var errorDiagnostic: String? = null
            var lastStatusCode = -1
            var lastRetryAfter: Long? = null
            val lastProgressAt = AtomicLong(System.currentTimeMillis())
            fun noteProgress() = lastProgressAt.set(System.currentTimeMillis())

            fun recordHttpFailure(result: OkHttpDownloader.DownloadResult) {
                if (errorDiagnostic == null && !result.errorMessage.isNullOrBlank()) {
                    errorDiagnostic = result.errorMessage
                }
                if (result.statusCode > 0 && lastStatusCode <= 0) lastStatusCode = result.statusCode
                if (result.retryAfterSeconds != null) lastRetryAfter = result.retryAfterSeconds
            }

            setProgress(workDataOf(KEY_PROGRESS to 0.15f, KEY_STATUS to "Downloading $title..."))
            DownloadActivityManager.updateProgress(itemId, 0.15f, 0L, 0L, 0L)

            val isDirectHttpAsset = isVideo.not() && isStream.not() && (
                    mediaUrl.startsWith("http://", ignoreCase = true) ||
                    mediaUrl.startsWith("https://", ignoreCase = true) ||
                    mediaUrl.startsWith("data:image/", ignoreCase = true)
            ) && (
                sourceEngine.contains("HTML", ignoreCase = true) ||
                sourceEngine.contains("DOM", ignoreCase = true) ||
                sourceEngine.contains("Network", ignoreCase = true) ||
                sourceEngine.contains("Srcset", ignoreCase = true) ||
                sourceEngine.contains("Direct", ignoreCase = true) ||
                sourceEngine.contains("Pocket", ignoreCase = true) ||
                mediaUrl.contains(".jpg", ignoreCase = true) ||
                mediaUrl.contains(".jpeg", ignoreCase = true) ||
                mediaUrl.contains(".png", ignoreCase = true) ||
                mediaUrl.contains(".webp", ignoreCase = true) ||
                mediaUrl.contains(".gif", ignoreCase = true) ||
                mediaUrl.contains(".svg", ignoreCase = true) ||
                mediaUrl.contains(".mp3", ignoreCase = true) ||
                mediaUrl.contains(".m4a", ignoreCase = true) ||
                mediaUrl.contains(".wav", ignoreCase = true) ||
                mediaUrl.contains(".flac", ignoreCase = true) ||
                (mediaUrl.contains(".mp4", ignoreCase = true) && !sourceUrl.contains("youtube.com", ignoreCase = true) && !sourceUrl.contains("youtu.be", ignoreCase = true))
            )

            val targetUrl = if (sourceUrl.contains("youtube.com", ignoreCase = true) || sourceUrl.contains("youtu.be", ignoreCase = true)) {
                sourceUrl
            } else {
                mediaUrl
            }

            val effectiveDomain = sourceDomain.takeIf { it.isNotBlank() }
                ?: OkHttpDownloader.extractDomain(sourceUrl.ifBlank { mediaUrl })

            // Strategy 1: Direct HTTP media via OkHttp (fast path, byte-level ranges, resumable)
            if (isDirectHttpAsset) {
                setProgress(workDataOf(KEY_PROGRESS to 0.25f, KEY_STATUS to "Downloading via OkHttp: $title..."))
                val okResult = OkHttpDownloader.downloadMediaFileDetailed(
                    context = applicationContext,
                    mediaUrl = mediaUrl,
                    fileName = fileName,
                    referer = referer ?: sourceUrl,
                    downloadId = itemId,
                    headers = downloadHeaders,
                    domain = effectiveDomain,
                    onProgressWithSpeed = { bytesRead, totalBytes, speedBps ->
                        noteProgress()
                        val ratio = if (totalBytes > 0L) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0.1f, 0.95f) else 0.5f
                        DownloadActivityManager.updateProgress(itemId, ratio, bytesRead, totalBytes, speedBps)
                    }
                )
                if (okResult.isSuccessful && okResult.file != null && okResult.file.exists() && okResult.file.length() > 0L) {
                    downloadedFile = okResult.file
                } else {
                    recordHttpFailure(okResult)
                }
            }

            // Strategy 2: Originating extractor (yt-dlp or gallery-dl) when required or as fallback.
            // The embedded Python engine cannot be interrupted, so it runs behind a stall watchdog:
            // if it makes no progress for STALL_TIMEOUT_MS we stop waiting and move on instead of
            // leaving the download "running" forever.
            if (!isStopped && (downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) && GalleryDlEngine.isAvailable()) {
                setProgress(workDataOf(KEY_PROGRESS to 0.35f, KEY_STATUS to "Downloading via extractor engine: $title..."))
                noteProgress()
                val extractorProgress: (Float, Long, Long, Long) -> Unit = { ratio, bytesRead, totalBytes, speedBps ->
                    noteProgress()
                    DownloadActivityManager.updateProgress(itemId, ratio, bytesRead, totalBytes, speedBps)
                }
                downloadedFile = withStallGuard(lastProgressAt, STALL_TIMEOUT_MS, MAX_EXTRACTOR_MS) {
                    GalleryDlEngine.downloadMedia(
                        context = applicationContext,
                        mediaUrl = targetUrl,
                        fileName = fileName,
                        referer = referer ?: sourceUrl,
                        headers = downloadHeaders,
                        cookies = cookieString,
                        domain = effectiveDomain,
                        onProgress = extractorProgress
                    )
                }
                // If targetUrl != mediaUrl and extractor returned null, retry with mediaUrl directly
                if (!isStopped && (downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) && targetUrl != mediaUrl) {
                    noteProgress()
                    downloadedFile = withStallGuard(lastProgressAt, STALL_TIMEOUT_MS, MAX_EXTRACTOR_MS) {
                        GalleryDlEngine.downloadMedia(
                            context = applicationContext,
                            mediaUrl = mediaUrl,
                            fileName = fileName,
                            referer = referer ?: sourceUrl,
                            headers = downloadHeaders,
                            cookies = cookieString,
                            domain = effectiveDomain,
                            onProgress = extractorProgress
                        )
                    }
                }
            }

            // Strategy 3: Final fallback to OkHttp for any URL that specialized extractors couldn't capture
            if (!isStopped && (downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L) && !isDirectHttpAsset) {
                setProgress(workDataOf(KEY_PROGRESS to 0.5f, KEY_STATUS to "Trying direct download fallback: $title..."))
                val fallbackResult = OkHttpDownloader.downloadMediaFileDetailed(
                    context = applicationContext,
                    mediaUrl = mediaUrl,
                    fileName = fileName,
                    referer = referer ?: sourceUrl,
                    downloadId = itemId,
                    headers = downloadHeaders,
                    domain = effectiveDomain,
                    onProgressWithSpeed = { bytesRead, totalBytes, speedBps ->
                        noteProgress()
                        val ratio = if (totalBytes > 0L) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0.1f, 0.95f) else 0.5f
                        DownloadActivityManager.updateProgress(itemId, ratio, bytesRead, totalBytes, speedBps)
                    }
                )
                if (fallbackResult.isSuccessful && fallbackResult.file != null && fallbackResult.file.exists() && fallbackResult.file.length() > 0L) {
                    downloadedFile = fallbackResult.file
                } else {
                    recordHttpFailure(fallbackResult)
                }
            }

            if (isStopped) {
                // Stopped by the user (pause/cancel: state already set by DownloadActivityManager) or by
                // the system (constraints lost, process limits). In the system case WorkManager
                // reschedules this work itself, so the activity must stay as-is and must NOT be failed.
                Log.d(TAG, "MediaDownloadWorker was stopped for $mediaUrl")
                return@withContext Result.failure()
            }

            if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0L) {
                setProgress(workDataOf(KEY_PROGRESS to 1.0f, KEY_STATUS to "Saved successfully"))

                val fileUri = Uri.fromFile(downloadedFile).toString()
                runCatching {
                    MediaScannerConnection.scanFile(
                        applicationContext,
                        arrayOf(downloadedFile.absolutePath),
                        arrayOf(if (isVideo) "video/*" else if (isAudio) "audio/*" else "image/*"),
                        null
                    )
                }

                DownloadActivityManager.markFinished(
                    itemId = itemId,
                    localUri = fileUri,
                    sizeBytes = downloadedFile.length()
                )

                // Update EVERY bookmark that references this media, not just the one that enqueued it.
                // Unique work is keyed by URL, so when two bookmarks share a file only one worker runs;
                // the other bookmark would otherwise never learn its file exists and stay "saving" forever.
                runCatching {
                    val pocketRepo = PocketRepository.getInstance(applicationContext)
                    val downloadedKey = MediaClassifier.deduplicationKey(mediaUrl)
                    for (stack in pocketRepo.bookmarks.value) {
                        var changed = false
                        val updatedItems = stack.items.map { itm ->
                            val itemMatches = itm.id == itemId || (itm.sourceUrl.isNotBlank() && (
                                itm.sourceUrl == mediaUrl ||
                                    MediaClassifier.deduplicationKey(itm.sourceUrl) == downloadedKey
                                ))
                            if (itemMatches && OkHttpDownloader.resolveLocalFile(itm.localUri) == null) {
                                changed = true
                                itm.copy(localUri = fileUri, sizeBytes = downloadedFile.length())
                            } else itm
                        }
                        if (!changed) continue
                        val updatedPreview = if (stack.previewImageUrl.isBlank() || (!isVideo && !isStream && stack.previewImageUrl == mediaUrl)) {
                            fileUri
                        } else stack.previewImageUrl
                        pocketRepo.updateBookmark(stack.copy(items = updatedItems, previewImageUrl = updatedPreview))
                    }
                }

                Result.success(
                    workDataOf(
                        KEY_LOCAL_URI to downloadedFile.absolutePath,
                        KEY_SIZE_BYTES to downloadedFile.length(),
                        KEY_ITEM_ID to itemId
                    )
                )
            } else {
                val finalError = errorDiagnostic
                    ?: "Could not download $mediaUrl (the site returned no usable media)"
                val currentItem = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
                val isPausedOrCancelled = currentItem?.status == DownloadActivityStatus.PAUSED ||
                    currentItem?.errorMessage == "Cancelled by user"

                if (!isPausedOrCancelled) {
                    DownloadActivityManager.handleDownloadFailure(
                        context = applicationContext,
                        itemId = itemId,
                        statusCode = lastStatusCode,
                        errorMessage = finalError,
                        retryAfterSeconds = lastRetryAfter
                    )
                }
                Result.failure(workDataOf(KEY_ERROR to finalError))
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation: let WorkManager see it so a system-stopped worker is
            // rescheduled and a user-cancelled one is marked cancelled.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in MediaDownloadWorker for $mediaUrl", e)
            val err = e.message ?: e.javaClass.simpleName
            val currentItem = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
            val isPausedOrCancelled = isStopped ||
                currentItem?.status == DownloadActivityStatus.PAUSED ||
                currentItem?.errorMessage == "Cancelled by user"

            if (!isPausedOrCancelled) {
                DownloadActivityManager.handleDownloadFailure(
                    context = applicationContext,
                    itemId = itemId,
                    statusCode = -1,
                    errorMessage = err,
                    retryAfterSeconds = null
                )
            }
            Result.failure(workDataOf(KEY_ERROR to err))
        } finally {
            DownloadQueueManager.markDownloadCompleted(itemId)
        }
    }

    /**
     * Runs a blocking, non-interruptible extractor call (embedded Python / FFmpeg) on a detached
     * scope and waits for it with a stall watchdog. Returns null if the call made no progress for
     * [stallMs], ran longer than [maxMs], or this worker was stopped. The orphaned call finishes on
     * its own thread (yt-dlp has socket timeouts) but can no longer wedge this worker.
     */
    private suspend fun <T> withStallGuard(
        lastProgressAt: AtomicLong,
        stallMs: Long,
        maxMs: Long,
        block: suspend () -> T?
    ): T? {
        val deferred = detachedScope.async { block() }
        val startedAt = System.currentTimeMillis()
        try {
            while (!deferred.isCompleted) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                if (isStopped) {
                    return null
                }
                if (now - lastProgressAt.get() > stallMs) {
                    Log.w(TAG, "Extractor stalled for ${stallMs / 1000}s with no progress; giving up on it")
                    return null
                }
                if (now - startedAt > maxMs) {
                    Log.w(TAG, "Extractor exceeded ${maxMs / 1000}s; giving up on it")
                    return null
                }
            }
            return runCatching { deferred.await() }.getOrNull()
        } finally {
            if (!deferred.isCompleted) deferred.cancel()
        }
    }

    companion object {
        private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val STALL_TIMEOUT_MS = 180_000L
        private const val MAX_EXTRACTOR_MS = 25 * 60_000L

        const val TAG = "MediaDownloadWorker"
        const val WORK_TAG_DOWNLOAD = "media_download"

        const val KEY_MEDIA_URL = "media_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_REFERER = "referer"
        const val KEY_ITEM_ID = "item_id"
        const val KEY_TITLE = "title"
        const val KEY_IS_VIDEO = "is_video"
        const val KEY_IS_AUDIO = "is_audio"
        const val KEY_IS_STREAM = "is_stream"
        const val KEY_FORMAT = "format"
        const val KEY_STACK_ID = "stack_id"
        const val KEY_STACK_TITLE = "stack_title"
        const val KEY_SOURCE_DOMAIN = "source_domain"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_SOURCE_ENGINE = "source_engine"
        const val KEY_HEADERS_JSON = "headers_json"

        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_READ = "bytes_read"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_STATUS = "status"
        const val KEY_LOCAL_URI = "local_uri"
        const val KEY_SIZE_BYTES = "size_bytes"
        const val KEY_ERROR = "error"

        const val SILENT_CHANNEL_ID = "media_download_silent_channel"
        const val NOTIFICATION_ID = 4001
    }
}
