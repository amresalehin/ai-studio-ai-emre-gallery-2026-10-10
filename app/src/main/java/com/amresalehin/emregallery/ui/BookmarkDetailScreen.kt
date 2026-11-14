package com.amresalehin.emregallery.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.amresalehin.emregallery.R
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.PocketRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.amresalehin.emregallery.data.DownloadActivityManager
import com.amresalehin.emregallery.data.DownloadActivityStatus
import java.io.File
import java.util.Locale

/**
 * Fullscreen Bookmark Detail view in Emre Gallery.
 * - Shows ONLY permanently saved media from the download folder that passed active filters.
 * - Tapping the card deck directly opens the app's built-in media viewer (ViewerScreen).
 * - Clutter-free design with no junk drawers, redundant rows, or duplicate download buttons.
 */
@Composable
fun BookmarkDetailScreen(
    stack: MediaInboxStack,
    onBack: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onDelete: () -> Unit,
    onRefreshMetadata: (() -> Unit)? = null,
    onFetchSiteMedia: (() -> Unit)? = null,
    onOpenMediaItem: ((MediaItem, List<MediaItem>) -> Unit)? = null,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pocketRepo = remember { PocketRepository.getInstance(context) }
    val allBookmarks by pocketRepo.bookmarks.collectAsState()
    val showSvg by pocketRepo.showSvg.collectAsState()
    val showVideos by pocketRepo.showVideos.collectAsState()
    val showStreams by pocketRepo.showStreams.collectAsState()
    val showPng by pocketRepo.showPng.collectAsState()
    val showGif by pocketRepo.showGif.collectAsState()
    val minWidth by pocketRepo.minWidth.collectAsState()
    val minHeight by pocketRepo.minHeight.collectAsState()
    val minSizeKb by pocketRepo.minSizeKb.collectAsState()

    // Keep live data synced and sanitized according to active filters
    val liveStack = remember(
        allBookmarks, stack.id, showSvg, showVideos, showStreams, showPng, showGif,
        minWidth, minHeight, minSizeKb
    ) {
        val raw = allBookmarks.find { it.id == stack.id } ?: stack
        pocketRepo.sanitizeStack(
            stack = raw,
            showSvg = showSvg,
            minWidth = minWidth,
            minHeight = minHeight,
            showVideos = showVideos,
            showStreams = showStreams,
            showPng = showPng,
            showGif = showGif,
            minSizeKb = minSizeKb
        )
    }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    BackHandler {
        onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketThemeColors.background(isDark))
            .testTag("bookmark_detail_screen")
    ) {
        PocketBookmarkViewerPage(
            stack = liveStack,
            onBack = onBack,
            onDelete = { showDeleteConfirmDialog = true },
            onRefreshMetadata = onRefreshMetadata,
            onOpenMediaItem = onOpenMediaItem,
            isDark = isDark,
            modifier = Modifier.fillMaxSize()
        )

        // Delete confirmation dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                containerColor = if (isDark) Color(0xFF1C1C1C) else Color(0xFFFFFFFF),
                titleContentColor = PocketThemeColors.primaryText(isDark),
                textContentColor = PocketThemeColors.secondaryText(isDark),
                icon = {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = PocketThemeColors.primaryText(isDark)
                    )
                },
                title = {
                    Text(
                        text = "Delete Bookmark",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text("Are you sure you want to remove this bookmark from Pocket?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmDialog = false
                            onDelete()
                        }
                    ) {
                        Text(
                            text = "Delete",
                            color = if (isDark) Color(0xFFFF6B6B) else Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text(
                            text = "Cancel",
                            color = PocketThemeColors.secondaryText(isDark)
                        )
                    }
                }
            )
        }
    }
}

/**
 * Clean card deck page:
 * - Displays ONLY items that have been verified and saved to the download folder.
 * - Tapping on the card directly opens the built-in Media Viewer (ViewerScreen).
 * - All junk elements (drawer, redundant tags, duplicate carousels) removed.
 */
@Composable
fun PocketBookmarkViewerPage(
    stack: MediaInboxStack,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onRefreshMetadata: (() -> Unit)? = null,
    onOpenMediaItem: ((MediaItem, List<MediaItem>) -> Unit)? = null,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val cardSurface = PocketThemeColors.stackFront(isDark)
    val barSurface = PocketThemeColors.barSurface(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)

    var isDeckVideoMuted by remember { mutableStateOf(false) }

    val pocketRepo = remember { PocketRepository.getInstance(context) }
    val showSvg by pocketRepo.showSvg.collectAsState()
    val showVideos by pocketRepo.showVideos.collectAsState()
    val showStreams by pocketRepo.showStreams.collectAsState()
    val showPng by pocketRepo.showPng.collectAsState()
    val showGif by pocketRepo.showGif.collectAsState()
    val minWidth by pocketRepo.minWidth.collectAsState()
    val minHeight by pocketRepo.minHeight.collectAsState()
    val minSizeKb by pocketRepo.minSizeKb.collectAsState()

    val sanitizedStack = remember(
        stack, showSvg, showVideos, showStreams, showPng, showGif, minWidth, minHeight, minSizeKb
    ) {
        pocketRepo.sanitizeStack(
            stack = stack,
            showSvg = showSvg,
            minWidth = minWidth,
            minHeight = minHeight,
            showVideos = showVideos,
            showStreams = showStreams,
            showPng = showPng,
            showGif = showGif,
            minSizeKb = minSizeKb
        )
    }

    // STRICT REQUIREMENT: Show ONLY media items permanently saved in the download folder!
    // Resolved on Dispatchers.IO: these lookups scan directories and every bookmark and used to run
    // on the main thread during composition, which is why opening a bookmark felt frozen.
    var listResolved by remember(stack.id) { mutableStateOf(false) }
    val effectiveMediaList by produceState(
        initialValue = emptyList<MediaInboxItem>(),
        sanitizedStack, showSvg, showVideos, showStreams, showPng, showGif, minWidth, minHeight, minSizeKb
    ) {
        value = withContext(Dispatchers.IO) {
        sanitizedStack.items.mapNotNull { item ->
            val permanentFile = OkHttpDownloader.resolveLocalFile(item.localUri)
                ?: OkHttpDownloader.findExistingSavedFile(
                    context = context,
                    mediaUrl = item.sourceUrl,
                    fileName = item.title,
                    downloadId = item.id,
                    domain = stack.sourceDomain
                )
            if (permanentFile != null && permanentFile.exists() && permanentFile.length() > 0L) {
                item.copy(localUri = Uri.fromFile(permanentFile).toString(), sizeBytes = permanentFile.length())
            } else null
        }
        }
        listResolved = true
    }

    var currentIndex by remember(stack.id) { mutableIntStateOf(0) }
    val totalCards = effectiveMediaList.size
    val safeIndex = if (totalCards > 0) currentIndex.coerceIn(0, totalCards - 1) else 0
    val offsetX = remember { Animatable(0f) }
    var isSwiping by remember { mutableStateOf(false) }

    // Ensure filtered media are queued for permanent download only if any are missing from storage
    LaunchedEffect(stack.id) {
        withContext(Dispatchers.IO) {
        val anyMissing = sanitizedStack.items.any { item ->
            val hasValidLocalFile = OkHttpDownloader.resolveLocalFile(item.localUri) != null
            !hasValidLocalFile && OkHttpDownloader.findExistingSavedFile(
                context = context,
                mediaUrl = item.sourceUrl,
                fileName = item.title,
                downloadId = item.id,
                domain = stack.sourceDomain
            ) == null
        }
        if (anyMissing) {
            pocketRepo.ensureBookmarkMediaPermanentlyStored(stack.id)
        }
        }
    }

    // Preload next deck card into memory cache for smooth swipe
    LaunchedEffect(safeIndex, effectiveMediaList) {
        if (effectiveMediaList.isNotEmpty()) {
            val total = effectiveMediaList.size
            val nextIdx = (safeIndex + 1) % total
            val itm = effectiveMediaList.getOrNull(nextIdx)
            val resolvedLocal = OkHttpDownloader.resolveLocalFile(itm?.localUri)
            if (resolvedLocal != null) {
                val request = ImageRequest.Builder(context)
                    .data(resolvedLocal.absolutePath)
                    .crossfade(true)
                    .build()
                context.imageLoader.enqueue(request)
            }
        }
    }

    // DIRECTLY open the app's built-in media viewer on tapping the card stack
    val openInBuiltInViewer: (Int) -> Unit = { index ->
        if (effectiveMediaList.isNotEmpty()) {
            val mediaItemList = effectiveMediaList.map { itm ->
                val file = OkHttpDownloader.resolveLocalFile(itm.localUri) ?: File(itm.localUri.removePrefix("file://"))
                val name = if (file.exists()) file.name else itm.title.ifBlank { "media_item" }
                val isVid = itm.isVideo || name.endsWith(".mp4", ignoreCase = true) ||
                    name.endsWith(".webm", ignoreCase = true) || name.endsWith(".mkv", ignoreCase = true) ||
                    name.endsWith(".mov", ignoreCase = true)
                MediaItem(
                    id = itm.id.hashCode().toLong(),
                    uri = itm.localUri,
                    name = name,
                    mimeType = if (isVid) "video/*" else "image/*",
                    dateAddedSeconds = itm.dateAddedMillis / 1000,
                    dateTakenMillis = itm.dateAddedMillis,
                    sizeBytes = if (file.exists()) file.length() else itm.sizeBytes,
                    width = 0,
                    height = 0,
                    relativePath = "Download/Emre Gallery",
                    isVideo = isVid
                )
            }
            val targetMediaItem = mediaItemList.getOrNull(index) ?: mediaItemList.first()
            onOpenMediaItem?.invoke(targetMediaItem, mediaItemList)
        }
    }

    val faviconUrl = remember(stack.sourceDomain) {
        if (stack.sourceDomain.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=${stack.sourceDomain}&sz=128"
        } else ""
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(PocketThemeColors.background(isDark)),
        color = PocketThemeColors.background(isDark)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Ambient blurred backdrop of current media item
            val currentItemForBlur = effectiveMediaList.getOrNull(safeIndex)
            val blurMediaPath = currentItemForBlur?.localUri?.removePrefix("file://") ?: ""

            if (blurMediaPath.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(blurMediaPath)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(36.dp)
                        .graphicsLayer {
                            alpha = if (isDark) 0.35f else 0.22f
                        }
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isDark) Color.Black.copy(alpha = 0.50f)
                            else Color.White.copy(alpha = 0.55f)
                        )
                )
            }

            // Main Content: Top Bar & Centered 9:14 Card Deck
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top App Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Back Button
                    Surface(
                        shape = CircleShape,
                        color = barSurface,
                        shadowElevation = if (isDark) 2.dp else 1.dp,
                        border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(onClick = onBack)
                            .testTag("bookmark_viewer_back_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = textColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Center: Clean Bookmark Title & Item Count
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = stack.title.ifBlank { stack.sourceDomain.ifBlank { "Bookmark" } },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (effectiveMediaList.isNotEmpty()) {
                                "${effectiveMediaList.size} stored in Downloads"
                            } else {
                                stack.sourceDomain.ifBlank { "Pocket" }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText,
                            maxLines = 1
                        )
                    }

                    // Action Buttons: Refresh & Delete
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onRefreshMetadata != null) {
                            Surface(
                                shape = CircleShape,
                                color = barSurface,
                                shadowElevation = if (isDark) 2.dp else 1.dp,
                                border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable { onRefreshMetadata.invoke() }
                                    .testTag("bookmark_viewer_refresh_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh bookmark",
                                        tint = textColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = barSurface,
                            shadowElevation = if (isDark) 2.dp else 1.dp,
                            border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.7f) else dividerCol),
                            modifier = Modifier
                                .size(44.dp)
                                .clickable(onClick = onDelete)
                                .testTag("bookmark_viewer_delete_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete bookmark",
                                    tint = if (isDark) Color(0xFFFF6B6B) else Color(0xFFD32F2F),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Center 9:14 Card Deck
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val availableWidth = maxWidth
                    val availableHeight = maxHeight
                    val targetAspect = 9f / 14f

                    val (deckWidth, deckHeight) = if (availableWidth / availableHeight > targetAspect) {
                        (availableHeight * targetAspect) to availableHeight
                    } else {
                        availableWidth to (availableWidth / targetAspect)
                    }

                    if (effectiveMediaList.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(width = deckWidth, height = deckHeight)
                                .testTag("bookmark_card_deck_container"),
                            contentAlignment = Alignment.Center
                        ) {
                            // Layer 2: Deepest peeking background card
                            if (totalCards > 2) {
                                val peek2Index = (safeIndex + 2) % totalCards
                                val item2 = effectiveMediaList.getOrNull(peek2Index)
                                Surface(
                                    shape = RoundedCornerShape(28.dp),
                                    color = PocketThemeColors.stackLayer1(isDark),
                                    border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.35f) else Color(0xFFE2E2E2)),
                                    shadowElevation = if (isDark) 1.dp else 0.dp,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = 0.90f
                                            scaleY = 0.90f
                                            translationY = -40f
                                            alpha = 0.50f
                                        }
                                ) {
                                    BookmarkDeckCardContent(
                                        item = item2,
                                        stack = sanitizedStack,
                                        faviconUrl = faviconUrl,
                                        textColor = textColor,
                                        isDark = isDark,
                                        isFront = false,
                                        isPlayingVideo = false,
                                        isDeckVideoMuted = isDeckVideoMuted,
                                        onToggleMute = { isDeckVideoMuted = !isDeckVideoMuted },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            // Layer 1: Peeking middle background card
                            if (totalCards > 1) {
                                val peek1Index = (safeIndex + 1) % totalCards
                                val item1 = effectiveMediaList.getOrNull(peek1Index)
                                Surface(
                                    shape = RoundedCornerShape(30.dp),
                                    color = PocketThemeColors.stackLayer2(isDark),
                                    border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.5f) else Color(0xFFDCDCDC)),
                                    shadowElevation = if (isDark) 3.dp else 2.dp,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = 0.95f
                                            scaleY = 0.95f
                                            translationY = -20f
                                            alpha = 0.85f
                                        }
                                ) {
                                    BookmarkDeckCardContent(
                                        item = item1,
                                        stack = sanitizedStack,
                                        faviconUrl = faviconUrl,
                                        textColor = textColor,
                                        isDark = isDark,
                                        isFront = false,
                                        isPlayingVideo = false,
                                        isDeckVideoMuted = isDeckVideoMuted,
                                        onToggleMute = { isDeckVideoMuted = !isDeckVideoMuted },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            // Front Active Card: Swipable and tap-to-open built-in viewer
                            val currentItem = effectiveMediaList.getOrNull(safeIndex)
                            Surface(
                                shape = RoundedCornerShape(32.dp),
                                color = cardSurface,
                                border = BorderStroke(1.2.dp, if (isDark) dividerCol.copy(alpha = 0.8f) else Color(0xFFCCCCCC)),
                                shadowElevation = if (isDark) 10.dp else 8.dp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = offsetX.value
                                        rotationZ = (offsetX.value / 28f).coerceIn(-14f, 14f)
                                    }
                                    .draggable(
                                        orientation = Orientation.Horizontal,
                                        enabled = totalCards > 1,
                                        state = rememberDraggableState { delta ->
                                            scope.launch {
                                                offsetX.snapTo(offsetX.value + delta)
                                            }
                                        },
                                        onDragStarted = { isSwiping = true },
                                        onDragStopped = { velocity ->
                                            isSwiping = false
                                            val swipeThreshold = 140f
                                            val shouldSwipeLeft = offsetX.value < -swipeThreshold || velocity < -400f
                                            val shouldSwipeRight = offsetX.value > swipeThreshold || velocity > 400f
                                            scope.launch {
                                                if (totalCards > 1 && shouldSwipeLeft) {
                                                    val targetNext = (safeIndex + 1) % totalCards
                                                    offsetX.animateTo(
                                                        targetValue = -1200f,
                                                        animationSpec = tween(180)
                                                    )
                                                    currentIndex = targetNext
                                                    offsetX.snapTo(0f)
                                                } else if (totalCards > 1 && shouldSwipeRight) {
                                                    val targetPrev = if (safeIndex - 1 < 0) totalCards - 1 else safeIndex - 1
                                                    offsetX.animateTo(
                                                        targetValue = 1200f,
                                                        animationSpec = tween(180)
                                                    )
                                                    currentIndex = targetPrev
                                                    offsetX.snapTo(0f)
                                                } else {
                                                    offsetX.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = spring(
                                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                                            stiffness = Spring.StiffnessMediumLow
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    )
                                    .clickable {
                                        openInBuiltInViewer(safeIndex)
                                    }
                                    .testTag("deck_front_card_$safeIndex")
                            ) {
                                BookmarkDeckCardContent(
                                    item = currentItem,
                                    stack = sanitizedStack,
                                    faviconUrl = faviconUrl,
                                    textColor = textColor,
                                    isDark = isDark,
                                    isFront = true,
                                    isPlayingVideo = !isSwiping,
                                    isDeckVideoMuted = isDeckVideoMuted,
                                    onToggleMute = { isDeckVideoMuted = !isDeckVideoMuted },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    } else {
                        // Empty / downloading state: items being saved to download folder
                        Surface(
                            shape = RoundedCornerShape(32.dp),
                            color = barSurface,
                            border = BorderStroke(1.dp, if (isDark) dividerCol.copy(alpha = 0.5f) else Color(0xFFDDD8E2)),
                            shadowElevation = if (isDark) 3.dp else 2.dp,
                            modifier = Modifier
                                .size(width = deckWidth, height = deckHeight)
                                .testTag("deck_empty_filtered_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                val activities by DownloadActivityManager.activities.collectAsState()
                                val stackActivities = activities.filter { it.stackId == stack.id }
                                val anyActive = stackActivities.any {
                                    it.status == DownloadActivityStatus.DOWNLOADING || it.status == DownloadActivityStatus.RETRYING
                                }
                                val allFailed = listResolved && !anyActive && stackActivities.any { it.status == DownloadActivityStatus.FAILED }
                                val nothingRunning = listResolved && !anyActive
                                if (!nothingRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(36.dp),
                                        strokeWidth = 3.dp,
                                        color = if (isDark) Color.White else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(18.dp))
                                }
                                Text(
                                    text = if (!listResolved) "Loading…" else if (anyActive) "Saving to download folder…"
                                    else if (allFailed) "Couldn’t save this media" else "No saved media yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor
                                )
                                if (nothingRunning) {
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            stackActivities.filter { it.status == DownloadActivityStatus.FAILED }
                                                .forEach { DownloadActivityManager.retryDownload(context, it.id) }
                                            pocketRepo.ensureBookmarkMediaPermanentlyStored(stack.id)
                                        }
                                    }) { Text("Retry download") }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Only filtered media saved to your device are shown here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = secondaryText,
                                    lineHeight = 20.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Bottom Page Counter & Dots
                if (totalCards > 1) {
                    Row(
                        modifier = Modifier.padding(bottom = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${safeIndex + 1} of $totalCards",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = secondaryText
                        )
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Card content for deck layer:
 * Strictly displays verified local files from the download directory.
 */
@Composable
private fun BookmarkDeckCardContent(
    item: MediaInboxItem?,
    stack: MediaInboxStack,
    faviconUrl: String,
    textColor: Color,
    isDark: Boolean,
    isFront: Boolean,
    isPlayingVideo: Boolean,
    isDeckVideoMuted: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val permanentLocalFile = remember(item?.localUri) {
        OkHttpDownloader.resolveLocalFile(item?.localUri)
    }

    val isCurrentVideo = remember(item, permanentLocalFile) {
        item != null && (item.isVideo || permanentLocalFile?.extension?.lowercase(Locale.ROOT) in listOf("mp4", "webm", "mov", "mkv", "avi", "3gp", "ts"))
    }

    if (item != null && permanentLocalFile != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            // Blurred ambient backdrop inside container. Skipped for the playing video card: it forced a
            // second video-frame extraction and a full-size blur right as playback was starting.
            if (!(isCurrentVideo && isFront)) AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(permanentLocalFile.absolutePath)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .blur(24.dp)
                    .graphicsLayer {
                        alpha = if (isDark) 0.35f else 0.25f
                    }
            )

            // Video player for front card, or image preview
            if (isCurrentVideo && isFront) {
                BookmarkDeckVideoPlayer(
                    uri = permanentLocalFile.absolutePath,
                    isPlaying = isPlayingVideo,
                    isMuted = isDeckVideoMuted,
                    onToggleMute = onToggleMute,
                    isDark = isDark,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(22.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(permanentLocalFile.absolutePath)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title.ifBlank { "Media" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isCurrentVideo) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.55f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video preview",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Bottom-start format and size badge on front card
            if (isFront) {
                val sizeFormatted = if (permanentLocalFile.length() > 0L) " · " + formatBytes(permanentLocalFile.length()) else ""
                val formatTag = item.format.ifBlank { if (isCurrentVideo) "VIDEO" else "IMAGE" }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Stored in download folder",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "$formatTag$sizeFormatted · Saved",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (faviconUrl.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = PocketThemeColors.faviconSurface(isDark),
                        shadowElevation = 3.dp,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(faviconUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = stack.sourceDomain,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stack.sourceDomain.ifBlank { "Bookmark Preview" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }
        }
    }
}

/**
 * Loops automatically and manages ExoPlayer lifecycle for video deck cards.
 */
@Composable
fun BookmarkDeckVideoPlayer(
    uri: String,
    isPlaying: Boolean = true,
    isMuted: Boolean = false,
    onToggleMute: (() -> Unit)? = null,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    if (uri.isBlank() || uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(uri) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 3000, 100, 250)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val httpDataSourceFactory = OkHttpDataSource.Factory(OkHttpDownloader.client)
            .setUserAgent(OkHttpDownloader.DEFAULT_USER_AGENT)
        val upstreamDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(upstreamDataSourceFactory)

        val mediaItem = buildPlayerMediaItem(uri)

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(mediaItem)
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                volume = if (isMuted) 0f else 1f
                val recovery = PlaybackRecovery()
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { recovery.reset() }
                    override fun onPlayerError(error: PlaybackException) {
                        recovery.onError(this@apply, error, "BookmarkDeckVideoPlayer")
                    }
                })
                prepare()
                playWhenReady = true
            }
    }

    LaunchedEffect(isMuted, player) {
        player.volume = if (isMuted) 0f else 1f
    }

    LaunchedEffect(isPlaying, player) {
        if (isPlaying) {
            player.play()
        } else {
            player.pause()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_RESUME -> {
                    if (player.playbackState == Player.STATE_IDLE && player.playerError == null) player.prepare()
                    if (isPlaying) player.play()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.clearVideoSurface()
            player.stop()
            player.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                val pv = LayoutInflater.from(ctx)
                    .inflate(R.layout.item_viewer_player, null, false) as PlayerView
                pv.apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
