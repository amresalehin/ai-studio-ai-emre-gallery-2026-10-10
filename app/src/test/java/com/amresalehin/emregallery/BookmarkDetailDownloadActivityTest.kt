package com.amresalehin.emregallery

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.DownloadActivityManager
import com.amresalehin.emregallery.data.DownloadActivityStatus
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.PocketRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkDetailDownloadActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DownloadActivityManager.initialize(context)
        DownloadActivityManager.clearFinished()
        context.getSharedPreferences("pocket_bookmarks_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("download_activity_tracker_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testBookmarkMediaDownloadRegistersAndFinishesInDownloadActivityManager() {
        val stackId = "stack_bookmark_101"
        val itemId = "item_media_202"
        val mediaUrl = "https://example.com/art.jpg"
        val title = "Digital Artwork"

        // 1. Initial queuing from bookmark detail screen
        DownloadActivityManager.recordQueued(
            id = itemId,
            title = title,
            mediaUrl = mediaUrl,
            thumbnailUrl = mediaUrl,
            isVideo = false,
            isAudio = false,
            isStream = false,
            format = "JPG",
            stackId = stackId,
            stackTitle = "Art Showcase",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/art"
        )

        val queuedList = DownloadActivityManager.activities.value
        val queuedItem = queuedList.firstOrNull { it.id == itemId }
        assertNotNull("Item must be recorded in DownloadActivityManager when download starts", queuedItem)
        assertEquals(DownloadActivityStatus.DOWNLOADING, queuedItem?.status)
        assertEquals(title, queuedItem?.title)
        assertEquals(stackId, queuedItem?.stackId)

        // 2. Mark download finished (e.g. file saved to device)
        val dummySavedFile = File(context.cacheDir, "saved_art.jpg").apply {
            writeText("dummy-image-bytes")
        }
        val fileUri = Uri.fromFile(dummySavedFile).toString()

        DownloadActivityManager.markFinished(
            itemId = itemId,
            localUri = fileUri,
            sizeBytes = dummySavedFile.length()
        )

        val finishedList = DownloadActivityManager.activities.value
        val finishedItem = finishedList.firstOrNull { it.id == itemId }
        assertNotNull("Finished download must remain in activities", finishedItem)
        assertEquals(DownloadActivityStatus.FINISHED, finishedItem?.status)
        assertEquals(fileUri, finishedItem?.localUri)
        assertEquals(1.0f, finishedItem?.progress ?: 0f, 0.01f)
    }

    @Test
    fun testSyncWithInboxRepositoryIncludesPocketBookmarksWithLocalMedia() {
        val pocketRepo = PocketRepository.getInstance(context)

        // Create a local dummy file representing a previously downloaded media item from bookmark detail
        val localMediaFile = File(context.filesDir, "downloaded_photo.jpg").apply {
            writeText("sample-photo-data")
        }
        val localMediaUri = Uri.fromFile(localMediaFile).toString()

        val createdBookmark = pocketRepo.addBookmark(
            title = "Tech Blog",
            url = "https://tech.blog/post/1",
            domain = "tech.blog",
            description = "",
            autoFetchMetadata = false
        )

        pocketRepo.updateBookmark(
            createdBookmark.copy(
                items = listOf(
                    MediaInboxItem(
                        id = "tech_photo_1",
                        title = "Diagram",
                        localUri = localMediaUri,
                        sourceUrl = "https://tech.blog/diagram.jpg",
                        isVideo = false,
                        format = "JPG",
                        sizeBytes = localMediaFile.length()
                    )
                )
            )
        )

        // Trigger syncWithInboxRepository on DownloadActivityManager
        DownloadActivityManager.syncWithInboxRepository()

        val activities = DownloadActivityManager.activities.value
        val syncedItem = activities.firstOrNull { it.localUri == localMediaUri }
        assertNotNull("Activity page must show media downloaded from bookmark detail via syncWithInboxRepository", syncedItem)
        assertEquals(DownloadActivityStatus.FINISHED, syncedItem?.status)
        assertEquals("Diagram", syncedItem?.title)
        assertEquals(createdBookmark.id, syncedItem?.stackId)
    }

    @Test
    fun pocketCacheUpdatePromotesMatchingQueuedActivityToFinished() {
        val pocketRepo = PocketRepository.getInstance(context)
        val stack = pocketRepo.addBookmark(
            title = "Cached media bookmark",
            url = "https://cache.example.com/post",
            domain = "cache.example.com",
            autoFetchMetadata = false
        )
        val itemId = "cached_bookmark_media"
        val sourceUrl = "https://cache.example.com/media.jpg"
        DownloadActivityManager.recordQueued(
            id = itemId,
            title = "Cached image",
            mediaUrl = sourceUrl,
            stackId = stack.id,
            stackTitle = stack.title,
            sourceUrl = stack.sourceUrl
        )
        val cachedFile = File(context.filesDir, "pocket_cached_media.jpg").apply { writeText("cached-media") }
        val cachedUri = Uri.fromFile(cachedFile).toString()
        pocketRepo.updateBookmark(
            stack.copy(items = listOf(
                MediaInboxItem(
                    id = itemId, title = "Cached image", localUri = cachedUri, sourceUrl = sourceUrl,
                    isVideo = false, format = "JPG", sizeBytes = cachedFile.length()
                )
            ))
        )
        DownloadActivityManager.syncWithInboxRepository()
        val activity = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
        assertNotNull("Cached bookmark media must be reconciled into Activity", activity)
        assertEquals(DownloadActivityStatus.FINISHED, activity?.status)
        assertEquals(cachedUri, activity?.localUri)
        assertEquals(cachedFile.length(), activity?.bytesRead)
        cachedFile.delete()
    }

    @Test
    fun syncWithStorageDirDoesNotExposeFfmpegIntermediateFiles() {
        val baseStorageDir = OkHttpDownloader.getTargetStorageDir(context)
        val suffix = System.currentTimeMillis()
        val completeFile = File(baseStorageDir, "verified_stream_$suffix.mp4").apply { writeText("verified-final-video") }
        val rootPartial = File(baseStorageDir, "unmerged_$suffix.part.mp4").apply { writeText("partial-video") }
        val stagingDir = File(baseStorageDir, ".ffmpeg-staging").apply { mkdirs() }
        val stagedPartial = File(stagingDir, "unmerged.part.mp4").apply { writeText("partial-video") }
        DownloadActivityManager.syncWithStorageDir(context)
        val activities = DownloadActivityManager.activities.value
        assertTrue("A verified final video should be visible", activities.any { it.localUri == Uri.fromFile(completeFile).toString() })
        assertFalse("A root .part.mp4 file must not be exposed", activities.any { it.localUri == Uri.fromFile(rootPartial).toString() })
        assertFalse("A hidden FFmpeg staging file must not be exposed", activities.any { it.localUri == Uri.fromFile(stagedPartial).toString() })
        completeFile.delete()
        rootPartial.delete()
        stagedPartial.delete()
        stagingDir.delete()
    }

    @Test
    fun testSyncWithStorageDirRecursivelyFindsDomainSubdirectories() {
        val baseStorageDir = OkHttpDownloader.getTargetStorageDir(context)
        val domainSubDir = File(baseStorageDir, "twitter_com").apply { mkdirs() }
        val nestedFile = File(domainSubDir, "tweet_video.mp4").apply {
            writeText("video_mp4_bytes")
        }

        DownloadActivityManager.syncWithStorageDir(context)

        val activities = DownloadActivityManager.activities.value
        val found = activities.firstOrNull { it.title == "tweet_video.mp4" }
        assertNotNull("Nested domain subdirectory file must be discovered by syncWithStorageDir", found)
        assertEquals("MP4", found?.format)
        assertTrue(found?.isVideo == true)
        assertEquals(DownloadActivityStatus.FINISHED, found?.status)
    }

    @Test
    fun testSaveToPocketWithAlreadyFetchedMediaSavesMediaItemsToBookmark() {
        val pocketRepo = PocketRepository.getInstance(context)

        val fetchedItem1 = MediaInboxItem(
            id = "scraped_item_1",
            title = "Article Hero Image",
            localUri = "",
            sourceUrl = "https://news.example.com/images/hero.jpg",
            isVideo = false,
            format = "JPG"
        )
        val fetchedItem2 = MediaInboxItem(
            id = "scraped_item_2",
            title = "Article Video Explainer",
            localUri = "",
            sourceUrl = "https://news.example.com/videos/explainer.mp4",
            isVideo = true,
            format = "MP4"
        )

        // Simulate browser user clicking 'Save to Pocket' with already fetched media
        val bookmark = pocketRepo.addBookmark(
            title = "Breaking Science News",
            url = "https://news.example.com/breaking",
            domain = "news.example.com",
            description = "Fascinating new discovery",
            previewImageUrl = "",
            autoFetchMetadata = false,
            initialItems = listOf(fetchedItem1, fetchedItem2)
        )

        val savedBookmark = pocketRepo.bookmarks.value.firstOrNull { it.id == bookmark.id }
        assertNotNull("Bookmark must be saved in PocketRepository", savedBookmark)
        assertEquals(2, savedBookmark?.items?.size)
        assertTrue("Must contain the hero image", savedBookmark?.items?.any { it.sourceUrl == "https://news.example.com/images/hero.jpg" } == true)
        assertTrue("Must contain the explainer video", savedBookmark?.items?.any { it.isVideo && it.sourceUrl.contains("explainer.mp4") } == true)
        assertEquals("Preview image should automatically resolve from fetched media", "https://news.example.com/images/hero.jpg", savedBookmark?.previewImageUrl)
    }

    @Test
    fun testBookmarkDoesNotSynthesizeFakePreviewItemWhenItemsEmpty() {
        val pocketRepo = PocketRepository.getInstance(context)

        val bookmark = pocketRepo.addBookmark(
            title = "Article Without Media",
            url = "https://news.example.com/no-media",
            domain = "news.example.com",
            description = "Text article",
            previewImageUrl = "",
            autoFetchMetadata = false,
            initialItems = emptyList()
        )

        val savedBookmark = pocketRepo.bookmarks.value.firstOrNull { it.id == bookmark.id }
        assertNotNull(savedBookmark)
        assertTrue("Bookmark items must remain empty without fake preview items", savedBookmark?.items?.isEmpty() == true)

        val sanitized = pocketRepo.sanitizeStack(savedBookmark!!)
        assertTrue("Sanitized items must be empty when no real media is present", sanitized.items.isEmpty())
        assertTrue("No synthetic preview item ID should exist", sanitized.items.none { it.id.startsWith("preview_") || it.id.startsWith("media_preview_") })
    }

    @Test
    fun testDeepScanRunsAndStopsManually() {
        val pocketRepo = PocketRepository.getInstance(context)
        val bookmark = pocketRepo.addBookmark(
            title = "Continuous Gallery",
            url = "https://gallery.example.com/photos",
            domain = "gallery.example.com",
            autoFetchMetadata = false
        )

        // Start deep scan
        pocketRepo.startDeepScan(bookmark.id, context)
        assertEquals("activeDeepScanId must track currently scanning bookmark", bookmark.id, pocketRepo.activeDeepScanId.value)
        assertTrue("isDeepScanning must be true for active bookmark", pocketRepo.isDeepScanning(bookmark.id))

        // User stops manually
        pocketRepo.stopDeepScan(bookmark.id)
        org.junit.Assert.assertNull("activeDeepScanId must be cleared after manual stop", pocketRepo.activeDeepScanId.value)
        org.junit.Assert.assertFalse("isDeepScanning must be false after manual stop", pocketRepo.isDeepScanning(bookmark.id))
    }

    @Test
    fun testEnsureBookmarkMediaPermanentlyStoredEnqueuesUnstoredMedia() {
        val pocketRepo = PocketRepository.getInstance(context)

        // 1. Create a local temporary file that represents already permanently stored media
        val testDir = File(context.cacheDir, "test_perm_storage")
        testDir.mkdirs()
        val storedFile = File(testDir, "already_stored.mp4")
        storedFile.writeBytes(ByteArray(1024)) // 1 KB

        val storedItem = MediaInboxItem(
            id = "stored_vid_1",
            title = "Stored Video",
            localUri = "file://${storedFile.absolutePath}",
            sourceUrl = "https://example.com/stored.mp4",
            isVideo = true,
            sizeBytes = 1024L
        )

        val unStoredItem = MediaInboxItem(
            id = "unstored_img_2",
            title = "Unstored Image",
            localUri = "",
            sourceUrl = "https://example.com/photo_large.jpg",
            isVideo = false,
            sizeBytes = 0L
        )

        val bookmark = pocketRepo.addBookmark(
            title = "Permanent Storage Test",
            url = "https://example.com/gallery",
            domain = "example.com",
            autoFetchMetadata = false,
            initialItems = listOf(storedItem, unStoredItem)
        )

        // 2. Clear queued activities to isolate this test
        DownloadActivityManager.clearFinished()

        // 3. Trigger ensureBookmarkMediaPermanentlyStored
        pocketRepo.ensureBookmarkMediaPermanentlyStored(bookmark.id)

        // 4. Verify unstored item was queued for permanent download, while already stored item was not re-queued
        val activities = DownloadActivityManager.activities.value
        val unstoredQueued = activities.any { it.mediaUrl == "https://example.com/photo_large.jpg" }
        val storedQueued = activities.any { it.mediaUrl == "https://example.com/stored.mp4" }

        assertTrue("Unstored media must be queued for permanent device download", unstoredQueued)
        assertFalse("Already permanently stored media must NOT be re-downloaded", storedQueued)

        // 5. Verify localUri points to a verified permanent file on disk
        val verifiedFile = File(storedItem.localUri.removePrefix("file://"))
        assertTrue("Stored item must exist on disk", verifiedFile.exists())
        assertEquals(1024L, verifiedFile.length())
    }

    @Test
    fun testOpeningBookmarkWithAlreadyDownloadedMediaDoesNotRerunDownloads() {
        val pocketRepo = PocketRepository.getInstance(context)

        // 1. Pre-create downloaded file in domain storage directory with deterministic id
        val domain = "nature.org"
        val mediaUrl = "https://nature.org/wildlife_safari_4k.mp4"
        val fileName = "wildlife_safari_4k.mp4"
        val domainDir = OkHttpDownloader.getTargetStorageDir(context, domain)
        val deterministicName = OkHttpDownloader.generateDeterministicId(mediaUrl, fileName)
        val savedFile = File(domainDir, deterministicName).apply {
            writeBytes(ByteArray(2048))
        }
        assertTrue("Pre-downloaded file must exist", savedFile.exists())

        // 2. Create bookmark with item referencing this URL but with empty localUri
        val bookmark = pocketRepo.addBookmark(
            title = "Nature Safari",
            url = "https://nature.org/article",
            domain = domain,
            autoFetchMetadata = false,
            initialItems = listOf(
                MediaInboxItem(
                    id = "safari_item_1",
                    title = fileName,
                    localUri = "",
                    sourceUrl = mediaUrl,
                    isVideo = true,
                    sizeBytes = 2048L
                )
            )
        )

        // 3. Clear activities to verify zero downloads are triggered on bookmark open
        DownloadActivityManager.clearFinished()
        val initialDownloadingCount = DownloadActivityManager.activities.value.count {
            it.status == DownloadActivityStatus.DOWNLOADING
        }
        assertEquals(0, initialDownloadingCount)

        // 4. Simulate opening bookmark
        pocketRepo.ensureBookmarkMediaPermanentlyStored(bookmark.id)

        // 5. Verify zero downloads were triggered or re-queued
        val afterFirstOpenDownloading = DownloadActivityManager.activities.value.count {
            it.status == DownloadActivityStatus.DOWNLOADING
        }
        assertEquals("Opening already-downloaded bookmark must NOT trigger downloading", 0, afterFirstOpenDownloading)

        // 6. Verify bookmark item reconciled its localUri to the existing file
        val updatedBookmark = pocketRepo.bookmarks.value.find { it.id == bookmark.id }
        assertNotNull(updatedBookmark)
        val reconciledItem = updatedBookmark!!.items.first()
        assertTrue("Reconciled localUri must point to saved file", reconciledItem.localUri.isNotBlank())
        val resolvedDiskFile = OkHttpDownloader.resolveLocalFile(reconciledItem.localUri)
        assertNotNull("Reconciled file must exist on disk: ${reconciledItem.localUri}", resolvedDiskFile)
        assertTrue(resolvedDiskFile!!.exists())

        // 7. Simulate subsequent bookmark opens: must remain 0 downloading
        pocketRepo.ensureBookmarkMediaPermanentlyStored(bookmark.id)
        val afterSecondOpenDownloading = DownloadActivityManager.activities.value.count {
            it.status == DownloadActivityStatus.DOWNLOADING
        }
        assertEquals("Subsequent bookmark open must NEVER rerun downloads", 0, afterSecondOpenDownloading)
    }
}
