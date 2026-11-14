# Fixes

## Downloads stuck / never finishing (Reddit, Imgur, etc.)
- OkHttpDownloader: every request got *browser-navigation* headers (Accept: text/html, Sec-Fetch-Dest: document).
  Media CDNs (i.redd.it, preview.redd.it, i.imgur.com...) answer that with an HTML page, which the downloader
  rejected. Media URLs now get image/video headers. Also fixes blank Coil thumbnails and ExoPlayer requests.
- Downloads are now cancellable (in-flight OkHttp calls are cancelled with the coroutine).
- gallery_dl_runner.py: fallback used `gallery_dl.net.HttpUserAgent` (does not exist) so it always failed silently;
  replaced with a real direct-HTTP downloader. yt-dlp no longer runs on plain images; each engine runs once, not twice;
  the global gallery-dl lock is no longer held during network transfer.
- MediaDownloadWorker: stall watchdog around the non-interruptible Python engine, real error + HTTP status/Retry-After
  reported, CancellationException no longer swallowed, all bookmarks sharing a file are updated, activity store is
  initialised before use, `.jpeg` / Pocket items use the direct path.
- DownloadActivityManager: locked read-modify-write (races could leave items "downloading" forever), init race that
  could wipe history, late progress ticks no longer undo pause, watchdog recovers items whose worker died,
  more transient errors auto-retry.
- DownloadQueueManager: pause/cancel leaked the in-flight flag; start/complete are now idempotent; blocking
  WorkManager `.get()` has a timeout.
- PocketRepository: stale FINISHED/DOWNLOADING/PAUSED records no longer block re-queueing.

## Bookmark screen slow / video never starts
- File lookups (directory scans + dedup keys over all bookmarks) ran on the main thread; now on IO, and
  `deduplicationKey` is memoized (it compiled dozens of regexes per call).
- Players treated any path containing "dash" as a DASH manifest, so saved Reddit videos (DASH_720_*.mp4) failed,
  then looped forever on `prepare()`. Fixed in both bookmark deck and viewer players; retries are bounded.
- Backgrounding called stop() and resume never re-prepared; now pause + re-prepare.
- Empty deck no longer shows an endless spinner: loading / saving / failed with a Retry button.

Not compiled here (no Android SDK in my sandbox): please run a Gradle build.
