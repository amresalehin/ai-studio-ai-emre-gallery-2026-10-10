package com.amresalehin.emregallery.data

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/**
 * Manages persistent media downloads through Android Jetpack WorkManager.
 * Schedules, tracks, and cancels background downloads with network constraints.
 * Keys unique work by deduped URL and synchronizes active work IDs with running jobs.
 */
object DownloadQueueManager {

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private val _inMemoryDownloading = kotlinx.coroutines.flow.MutableStateFlow(false)
    val inMemoryDownloading: kotlinx.coroutines.flow.StateFlow<Boolean> = _inMemoryDownloading

    private val activeDownloadCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeItemIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** True only when this process currently has the item registered as in flight (no stale persisted state). */
    fun isInFlight(itemId: String): Boolean = activeItemIds.contains(itemId)

    fun isDownloading(itemId: String): Boolean {
        if (activeItemIds.contains(itemId)) return true
        val activity = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
        return activity?.status == DownloadActivityStatus.DOWNLOADING || activity?.status == DownloadActivityStatus.RETRYING
    }

    /**
     * Registers a download as in flight. Idempotent per [itemId]: the in-flight counter is only
     * incremented the first time an item is seen, so start/complete always stay balanced.
     */
    fun markDownloadStarted(count: Int = 1, itemId: String? = null) {
        if (itemId != null) {
            if (!activeItemIds.add(itemId)) return
            activeDownloadCount.incrementAndGet()
        } else {
            activeDownloadCount.addAndGet(count)
        }
        _inMemoryDownloading.value = true
    }

    /**
     * Idempotent per [itemId]; an item that was never registered (or was already completed by a
     * cancel/pause) does not decrement the counter a second time.
     */
    fun markDownloadCompleted(itemId: String? = null) {
        if (itemId != null && !activeItemIds.remove(itemId)) {
            if (activeItemIds.isEmpty() && activeDownloadCount.get() <= 0) {
                activeDownloadCount.set(0)
                _inMemoryDownloading.value = false
            }
            return
        }
        val remaining = activeDownloadCount.decrementAndGet()
        if (remaining <= 0) {
            activeDownloadCount.set(0)
            _inMemoryDownloading.value = false
        }
    }

    /** Looks up the WorkManager state for a unique work name without ever blocking indefinitely. */
    private fun activeWorkFor(workManager: WorkManager?, uniqueWorkName: String): WorkInfo? {
        if (workManager == null) return null
        val infos = runCatching {
            workManager.getWorkInfosForUniqueWork(uniqueWorkName).get(3, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(emptyList())
        return infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }

    private fun serializeHeaders(headers: Map<String, String>): String? {
        if (headers.isEmpty()) return null
        return runCatching {
            val json = JSONObject()
            headers.forEach { (k, v) ->
                // Guard against exceeding the 10KB WorkManager Data limit by capping individual header values
                if (v.length <= 2048) {
                    json.put(k, v)
                }
            }
            json.toString().takeIf { it.length <= 4096 }
        }.getOrNull()
    }

    fun enqueueDownload(
        context: Context,
        item: ScrapedMediaItem,
        referer: String? = null,
        stackId: String? = null,
        stackTitle: String? = null,
        sourceDomain: String? = null,
        sourceUrl: String? = null,
        isRetry: Boolean = false
    ): UUID {
        val sId = stackId ?: "stack_${System.currentTimeMillis()}"
        val sTitle = stackTitle ?: sourceDomain ?: "Discovered Media"
        val domain = sourceDomain ?: ""
        val url = sourceUrl ?: item.url

        // Fast-path: If media is already saved in Download storage, mark it finished without enqueueing
        val alreadySaved = OkHttpDownloader.findExistingSavedFile(
            context = context,
            mediaUrl = item.url,
            fileName = item.title,
            downloadId = item.id,
            domain = domain
        )
        if (alreadySaved != null && alreadySaved.exists() && alreadySaved.length() > 0L) {
            val fileUri = Uri.fromFile(alreadySaved).toString()
            DownloadActivityManager.initialize(context)
            DownloadActivityManager.markFinished(
                itemId = item.id,
                localUri = fileUri,
                sizeBytes = alreadySaved.length()
            )
            return UUID.randomUUID()
        }

        val headersJson = serializeHeaders(item.downloadHeaders)

        val inputData = Data.Builder()
            .putString(MediaDownloadWorker.KEY_MEDIA_URL, item.url)
            .putString(MediaDownloadWorker.KEY_FILE_NAME, item.title)
            .putString(MediaDownloadWorker.KEY_REFERER, referer)
            .putString(MediaDownloadWorker.KEY_ITEM_ID, item.id)
            .putString(MediaDownloadWorker.KEY_TITLE, item.title)
            .putBoolean(MediaDownloadWorker.KEY_IS_VIDEO, item.isVideo)
            .putBoolean(MediaDownloadWorker.KEY_IS_AUDIO, item.isAudio)
            .putBoolean(MediaDownloadWorker.KEY_IS_STREAM, item.isStream)
            .putString(MediaDownloadWorker.KEY_FORMAT, item.format)
            .putString(MediaDownloadWorker.KEY_STACK_ID, sId)
            .putString(MediaDownloadWorker.KEY_STACK_TITLE, sTitle)
            .putString(MediaDownloadWorker.KEY_SOURCE_DOMAIN, domain)
            .putString(MediaDownloadWorker.KEY_SOURCE_URL, url)
            .putString(MediaDownloadWorker.KEY_SOURCE_ENGINE, item.provenance.extractorEngine)
            .apply {
                if (!headersJson.isNullOrBlank()) {
                    putString(MediaDownloadWorker.KEY_HEADERS_JSON, headersJson)
                }
            }
            .build()

        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setConstraints(networkConstraints)
            .setInputData(inputData)
            .addTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
            .addTag("item_${item.id}")
            .build()

        val dedupKey = MediaClassifier.deduplicationKey(item.url)
        val uniqueWorkName = "download_${abs(dedupKey.hashCode())}"

        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull()
        val activeWork = activeWorkFor(workManager, uniqueWorkName)

        val finalWorkId: UUID
        if (activeWork != null && !isRetry) {
            // Re-use already active WorkManager request ID so pause/cancel references the running job
            finalWorkId = activeWork.id
            markDownloadStarted(count = 1, itemId = item.id)
        } else {
            finalWorkId = request.id
            workManager?.enqueueUniqueWork(
                uniqueWorkName,
                if (isRetry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
            markDownloadStarted(count = 1, itemId = item.id)
        }

        DownloadActivityManager.initialize(context)
        DownloadActivityManager.recordQueued(
            id = item.id,
            title = item.title,
            mediaUrl = item.url,
            thumbnailUrl = item.thumbnailUrl ?: item.url,
            isVideo = item.isVideo,
            isAudio = item.isAudio,
            isStream = item.isStream,
            format = item.format,
            workId = finalWorkId.toString(),
            isRetry = isRetry,
            stackId = sId,
            stackTitle = sTitle,
            sourceDomain = domain,
            sourceUrl = url
        )

        return finalWorkId
    }

    fun enqueueBatch(
        context: Context,
        items: List<ScrapedMediaItem>,
        referer: String? = null,
        stackId: String? = null,
        stackTitle: String? = null,
        sourceDomain: String? = null,
        sourceUrl: String? = null
    ): List<UUID> {
        val sId = stackId ?: "stack_${System.currentTimeMillis()}"
        val sTitle = stackTitle ?: sourceDomain ?: "Discovered Media"
        val domain = sourceDomain ?: ""
        val url = sourceUrl ?: referer ?: ""

        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull()
        val distinctItems = MediaClassifier.deduplicateScrapedItems(items)

        val (alreadySaved, needDownload) = distinctItems.partition { item ->
            val existing = OkHttpDownloader.findExistingSavedFile(
                context = context,
                mediaUrl = item.url,
                fileName = item.title,
                downloadId = item.id,
                domain = domain
            )
            existing != null && existing.exists() && existing.length() > 0L
        }

        DownloadActivityManager.initialize(context)

        // Immediately record already saved items as finished
        val finishedWorkIds = alreadySaved.map { item ->
            val existing = OkHttpDownloader.findExistingSavedFile(
                context = context,
                mediaUrl = item.url,
                fileName = item.title,
                downloadId = item.id,
                domain = domain
            )
            if (existing != null) {
                DownloadActivityManager.markFinished(
                    itemId = item.id,
                    localUri = Uri.fromFile(existing).toString(),
                    sizeBytes = existing.length()
                )
            }
            UUID.randomUUID()
        }

        if (needDownload.isEmpty()) {
            return finishedWorkIds
        }

        val workIds = needDownload.map { item ->
            val headersJson = serializeHeaders(item.downloadHeaders)
            val inputData = Data.Builder()
                .putString(MediaDownloadWorker.KEY_MEDIA_URL, item.url)
                .putString(MediaDownloadWorker.KEY_FILE_NAME, item.title)
                .putString(MediaDownloadWorker.KEY_REFERER, referer)
                .putString(MediaDownloadWorker.KEY_ITEM_ID, item.id)
                .putString(MediaDownloadWorker.KEY_TITLE, item.title)
                .putBoolean(MediaDownloadWorker.KEY_IS_VIDEO, item.isVideo)
                .putBoolean(MediaDownloadWorker.KEY_IS_AUDIO, item.isAudio)
                .putBoolean(MediaDownloadWorker.KEY_IS_STREAM, item.isStream)
                .putString(MediaDownloadWorker.KEY_FORMAT, item.format)
                .putString(MediaDownloadWorker.KEY_STACK_ID, sId)
                .putString(MediaDownloadWorker.KEY_STACK_TITLE, sTitle)
                .putString(MediaDownloadWorker.KEY_SOURCE_DOMAIN, domain)
                .putString(MediaDownloadWorker.KEY_SOURCE_URL, url)
                .putString(MediaDownloadWorker.KEY_SOURCE_ENGINE, item.provenance.extractorEngine)
                .apply {
                    if (!headersJson.isNullOrBlank()) {
                        putString(MediaDownloadWorker.KEY_HEADERS_JSON, headersJson)
                    }
                }
                .build()

            val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setConstraints(networkConstraints)
                .setInputData(inputData)
                .addTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
                .addTag("item_${item.id}")
                .build()

            val dedupKey = MediaClassifier.deduplicationKey(item.url)
            val uniqueWorkName = "download_${abs(dedupKey.hashCode())}"

            val activeWork = activeWorkFor(workManager, uniqueWorkName)

            if (activeWork != null) {
                markDownloadStarted(count = 1, itemId = item.id)
                activeWork.id
            } else {
                workManager?.enqueueUniqueWork(
                    uniqueWorkName,
                    ExistingWorkPolicy.KEEP,
                    request
                )
                markDownloadStarted(count = 1, itemId = item.id)
                request.id
            }
        }

        DownloadActivityManager.recordBatchQueued(
            items = needDownload,
            workIds = workIds,
            stackId = sId,
            stackTitle = sTitle,
            sourceDomain = domain,
            sourceUrl = url
        )

        return finishedWorkIds + workIds
    }

    fun getActiveDownloadsFlow(context: Context): Flow<List<WorkInfo>> {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull()
        return wm?.getWorkInfosByTagFlow(MediaDownloadWorker.WORK_TAG_DOWNLOAD) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    fun cancelDownload(context: Context, workId: UUID, itemId: String? = null) {
        runCatching {
            WorkManager.getInstance(context).cancelWorkById(workId)
        }
        // Release the in-memory "downloading" flag for this item. Previously this was called with no
        // id, so the item stayed registered as downloading forever after a pause/cancel.
        markDownloadCompleted(itemId)
    }

    fun cancelAll(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
        }
        activeItemIds.clear()
        activeDownloadCount.set(0)
        _inMemoryDownloading.value = false
    }
}
