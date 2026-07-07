package com.example.msdksample.transfer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.msdksample.network.MultipartHttpClient
import com.example.msdksample.network.StreamAddressResolver
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.DateTime
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.file.FileListRequestTimeOrderType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileFilter
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.MediaFileListState
import dji.v5.manager.datacenter.media.MediaFileListStateListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import dji.v5.manager.interfaces.IMediaManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VideoTransferManager(
    context: Context,
    private val streamAddressProvider: () -> String,
    private val cameraIndexProvider: () -> ComponentIndexType
) {

    companion object {
        private const val TAG = "VideoTransfer"
        private const val PREFS_NAME = "video_transfer_prefs"
        private const val PREF_KEY_LAST_UPLOADED_ID = "last_uploaded_id"
        private const val UPLOAD_PORT = 7000
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val ENABLE_TIMEOUT_MS = 10_000L
        private const val DISABLE_TIMEOUT_MS = 5_000L
        private const val PULL_TIMEOUT_MS = 15_000L
        private const val DOWNLOAD_TIMEOUT_MS = 10 * 60_000L
        private const val RECORD_SETTLE_MS = 8_000L
        private const val EMPTY_LIST_RETRY_DELAY_MS = 3_000L
        private const val MAX_PULL_ATTEMPTS = 5
        private const val MEDIA_PULL_COUNT = 100
        private const val MEDIA_LOG_LIMIT = 12
        private const val VIDEO_DIR = "videos"
        private const val PUBLIC_VIDEO_SUBDIR = "MSDKSample"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "video-transfer").apply { isDaemon = true }
    }
    private val workerRunning = AtomicBoolean(false)
    private val pendingTransfer = AtomicBoolean(false)

    fun enqueueLatestVideoTransfer() {
        pendingTransfer.set(true)
        if (workerRunning.compareAndSet(false, true)) {
            executor.execute { drainPendingTransfers() }
        } else {
            Log.i(TAG, "Coalesced latest-video transfer request while another transfer is running")
        }
    }

    fun release() {
        executor.shutdownNow()
    }

    private fun drainPendingTransfers() {
        try {
            while (pendingTransfer.getAndSet(false)) {
                runTransferSafely()
            }
        } finally {
            workerRunning.set(false)
            if (pendingTransfer.get() && workerRunning.compareAndSet(false, true)) {
                executor.execute { drainPendingTransfers() }
            }
        }
    }

    private fun runTransferSafely() {
        runCatching {
            transferLatestVideo()
        }.onFailure { error ->
            Log.w(TAG, "Latest video transfer failed: ${error.message}", error)
        }
    }

    private fun transferLatestVideo() {
        val streamAddress = streamAddressProvider.invoke().trim()
        val host = StreamAddressResolver.extractHost(streamAddress)
        if (host.isNullOrBlank()) {
            Log.w(TAG, "Skip latest video transfer because RTMP host is unavailable: $streamAddress")
            return
        }

        Log.i(TAG, "Latest video transfer queued for host=$host:$UPLOAD_PORT")
        Thread.sleep(RECORD_SETTLE_MS)

        val mediaManager = MediaDataCenter.getInstance().mediaManager
        configureMediaSource(mediaManager)
        enableMediaManager(mediaManager)

        try {
            val mediaFile = pullLatestVideo(mediaManager) ?: run {
                Log.i(TAG, "No uploadable MP4 media file found on camera storage")
                return
            }

            val mediaId = buildMediaId(mediaFile)
            if (mediaId == lastUploadedMediaId()) {
                Log.i(TAG, "Skip upload because latest video was already uploaded: $mediaId")
                return
            }

            val localFile = downloadMediaFile(mediaFile)
            val uploadFileName = buildUploadFileName(mediaFile.getFileName())
            exportVideoToPublicDirectory(localFile, uploadFileName)
            uploadVideoFile(host, localFile, uploadFileName)
            markUploaded(mediaId)
            Log.i(TAG, "Latest video transfer completed successfully: $uploadFileName")
        } finally {
            disableMediaManager(mediaManager)
        }
    }

    private fun configureMediaSource(mediaManager: IMediaManager) {
        val cameraIndex = cameraIndexProvider.invoke()
        val dataSource = MediaFileListDataSource.Builder()
            .setLocation(CameraStorageLocation.SDCARD)
            .setIndexType(cameraIndex)
            .build()
        mediaManager.setMediaFileDataSource(dataSource)
        Log.i(TAG, "Configured media source index=$cameraIndex location=${CameraStorageLocation.SDCARD}")
    }

    private fun enableMediaManager(mediaManager: IMediaManager) {
        awaitCompletion("enable media manager", ENABLE_TIMEOUT_MS) { callback ->
            mediaManager.enable(callback)
        }
        Log.i(TAG, "Media manager enabled")
    }

    private fun disableMediaManager(mediaManager: IMediaManager) {
        runCatching {
            awaitCompletion("disable media manager", DISABLE_TIMEOUT_MS) { callback ->
                mediaManager.disable(callback)
            }
            Log.i(TAG, "Media manager disabled")
        }.onFailure { error ->
            Log.w(TAG, "Failed to disable media manager cleanly: ${error.message}")
        }
    }

    private fun pullLatestVideo(mediaManager: IMediaManager): MediaFile? {
        repeat(MAX_PULL_ATTEMPTS) { attempt ->
            val allFiles = pullMediaFileList(mediaManager)
            logPulledMediaFiles(allFiles, attempt + 1)

            val latestMp4 = allFiles
                .asSequence()
                .filter(::isDownloadableMediaFile)
                .filter(::isMp4MediaFile)
                .sortedWith(mediaSortComparator())
                .firstOrNull()

            if (latestMp4 != null) {
                Log.i(TAG, "Selected latest MP4: ${describeMediaFile(latestMp4)}")
                return latestMp4
            }

            val latestVideoFallback = allFiles
                .asSequence()
                .filter(::isDownloadableMediaFile)
                .sortedWith(mediaSortComparator())
                .firstOrNull()

            if (latestVideoFallback != null) {
                Log.w(TAG, "No MP4 found, fallback to latest video: ${describeMediaFile(latestVideoFallback)}")
                return latestVideoFallback
            }

            if (attempt + 1 < MAX_PULL_ATTEMPTS) {
                Log.i(TAG, "No downloadable video found after pull attempt ${attempt + 1}, retrying shortly")
                Thread.sleep(EMPTY_LIST_RETRY_DELAY_MS)
            }
        }

        return null
    }

    private fun pullMediaFileList(mediaManager: IMediaManager): List<MediaFile> {
        val stateLatch = CountDownLatch(1)
        val stateListener = MediaFileListStateListener { state ->
            if (state == MediaFileListState.UP_TO_DATE) {
                stateLatch.countDown()
            }
        }
        mediaManager.addMediaFileListStateListener(stateListener)

        try {
            val param = PullMediaFileListParam.Builder()
                .filter(MediaFileFilter.VIDEO)
                .mediaFileIndex(-1)
                .count(MEDIA_PULL_COUNT)
                .orderType(FileListRequestTimeOrderType.NEW_FIRST)
                .build()

            awaitCompletion("pull media file list", PULL_TIMEOUT_MS) { callback ->
                mediaManager.pullMediaFileListFromCamera(param, callback)
            }

            if (mediaManager.mediaFileListState != MediaFileListState.UP_TO_DATE) {
                val updated = stateLatch.await(PULL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!updated) {
                    throw IllegalStateException("Media file list did not reach UP_TO_DATE in time")
                }
            }

            val files = mediaManager.mediaFileListData.data.orEmpty()
            Log.i(TAG, "Media file list pull completed with state=${mediaManager.mediaFileListState} count=${files.size}")
            return files
        } finally {
            mediaManager.removeMediaFileListStateListener(stateListener)
        }
    }

    private fun downloadMediaFile(mediaFile: MediaFile): File {
        val targetDir = File(appContext.filesDir, VIDEO_DIR).apply { mkdirs() }
        val targetFile = File(targetDir, "${buildMediaId(mediaFile)}_${mediaFile.getFileName()}")
        val output = FileOutputStream(targetFile, false)
        val latch = CountDownLatch(1)
        val failureRef = AtomicReference<IDJIError?>()

        Log.i(TAG, "Downloading media file to ${targetFile.absolutePath}")

        mediaFile.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
            override fun onStart() {
                Log.i(TAG, "Media download started: ${mediaFile.getFileName()}")
            }

            override fun onProgress(total: Long, current: Long) {
                Log.d(TAG, "Media download progress: $current/$total")
            }

            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                output.write(data)
            }

            override fun onFinish() {
                output.flush()
                output.close()
                latch.countDown()
            }

            override fun onFailure(error: IDJIError) {
                failureRef.set(error)
                runCatching { output.flush() }
                runCatching { output.close() }
                latch.countDown()
            }
        })

        val finished = latch.await(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching {
                mediaFile.stopPullOriginalMediaFileFromCamera(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() = Unit
                    override fun onFailure(error: IDJIError) = Unit
                })
            }
            runCatching { output.close() }
            throw IllegalStateException("Timed out while downloading ${mediaFile.getFileName()}")
        }

        failureRef.get()?.let { error ->
            throw IllegalStateException(error.description() ?: error.errorCode())
        }

        Log.i(TAG, "Media download finished: ${targetFile.absolutePath}")
        return targetFile
    }

    private fun uploadVideoFile(host: String, localFile: File, uploadFileName: String) {
        val encodedName = URLEncoder.encode(uploadFileName, StandardCharsets.UTF_8.name())
        val requestPath = "/upload2WRJ?file=$encodedName"

        Log.i(TAG, "Uploading latest video to http://$host:$UPLOAD_PORT$requestPath")

        val response = FileInputStream(localFile).use { input ->
            MultipartHttpClient.postMultipart(
                host = host,
                port = UPLOAD_PORT,
                requestPath = requestPath,
                accept = "*/*",
                partFieldName = "file",
                fileName = uploadFileName,
                contentType = "video/mp4",
                contentLength = localFile.length(),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            ) { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val readCount = input.read(buffer)
                    if (readCount < 0) break
                    output.write(buffer, 0, readCount)
                }
            }
        }.trim()

        if (response != "1") {
            throw IllegalStateException("Upload endpoint returned unexpected body: $response")
        }

        Log.i(TAG, "Latest video upload succeeded with response=$response")
    }

    private fun exportVideoToPublicDirectory(localFile: File, publicFileName: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportVideoViaMediaStore(localFile, publicFileName)
            } else {
                exportVideoViaLegacyPublicDirectory(localFile, publicFileName)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to export video to public directory: ${error.message}", error)
        }
    }

    private fun exportVideoViaMediaStore(localFile: File, publicFileName: String) {
        val relativePath = "${Environment.DIRECTORY_MOVIES}/$PUBLIC_VIDEO_SUBDIR"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, publicFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert returned null")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(localFile).use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Failed to open MediaStore output stream")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "Exported video to public directory: $relativePath/$publicFileName")
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun exportVideoViaLegacyPublicDirectory(localFile: File, publicFileName: String) {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            PUBLIC_VIDEO_SUBDIR
        ).apply { mkdirs() }
        val targetFile = uniquePublicFile(publicDir, publicFileName)
        FileInputStream(localFile).use { input ->
            FileOutputStream(targetFile).use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "Exported video to public directory: ${targetFile.absolutePath}")
    }

    private fun uniquePublicFile(publicDir: File, fileName: String): File {
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex >= 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex >= 0) fileName.substring(dotIndex) else ""
        var candidate = File(publicDir, fileName)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(publicDir, "${baseName}_$counter$extension")
            counter += 1
        }
        return candidate
    }

    private fun awaitCompletion(
        actionLabel: String,
        timeoutMs: Long,
        action: (CommonCallbacks.CompletionCallback) -> Unit
    ) {
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<IDJIError?>()
        action.invoke(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                latch.countDown()
            }

            override fun onFailure(error: IDJIError) {
                errorRef.set(error)
                latch.countDown()
            }
        })

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            throw IllegalStateException("Timed out while trying to $actionLabel")
        }

        errorRef.get()?.let { error ->
            throw IllegalStateException(error.description() ?: error.errorCode())
        }
    }

    private fun buildMediaId(mediaFile: MediaFile): String {
        return buildString {
            append(mediaFile.getFileIndex())
            append('_')
            append(mediaDateRank(mediaFile.getDate()))
            append('_')
            append(mediaFile.getFileSize())
            append('_')
            append(mediaFile.getFileName())
        }
    }

    private fun buildUploadFileName(originalFileName: String): String {
        val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        return "$timestamp-vcr-$originalFileName"
    }

    private fun isDownloadableMediaFile(mediaFile: MediaFile): Boolean {
        val downloadable = mediaFile.getFileSize() > 0L
        if (downloadable && (!mediaFile.isValid() || !mediaFile.isHasOriginalFile())) {
            Log.w(
                TAG,
                "Proceeding with media despite metadata flags: ${describeMediaFile(mediaFile)}"
            )
        }
        return downloadable
    }

    private fun isMp4MediaFile(mediaFile: MediaFile): Boolean {
        val fileName = mediaFile.getFileName()
        return mediaFile.getFileType() == MediaFileType.MP4 || fileName.endsWith(".mp4", ignoreCase = true)
    }

    private fun mediaSortComparator(): Comparator<MediaFile> {
        return compareByDescending<MediaFile> { mediaDateRank(it.getDate()) }
            .thenByDescending { preferredVideoVariantRank(it.getFileName()) }
            .thenByDescending { it.getFileIndex() }
    }

    private fun preferredVideoVariantRank(fileName: String): Int {
        return when {
            fileName.contains("_V.", ignoreCase = true) -> 3
            fileName.contains("_S.", ignoreCase = true) -> 2
            fileName.contains("_T.", ignoreCase = true) -> 1
            else -> 0
        }
    }

    private fun logPulledMediaFiles(files: List<MediaFile>, attempt: Int) {
        Log.i(TAG, "Pulled ${files.size} media files on attempt=$attempt")
        files.take(MEDIA_LOG_LIMIT).forEachIndexed { index, mediaFile ->
            Log.i(TAG, "Media[$index]: ${describeMediaFile(mediaFile)}")
        }
    }

    private fun describeMediaFile(mediaFile: MediaFile): String {
        return "index=${mediaFile.getFileIndex()} name=${mediaFile.getFileName()} type=${mediaFile.getFileType()} size=${mediaFile.getFileSize()} hasOriginal=${mediaFile.isHasOriginalFile()} valid=${mediaFile.isValid()} date=${mediaDateRank(mediaFile.getDate())}"
    }

    private fun mediaDateRank(dateTime: DateTime?): Long {
        if (dateTime == null) return Long.MIN_VALUE
        val year = dateTime.getYear() ?: return Long.MIN_VALUE
        val month = dateTime.getMonth() ?: return Long.MIN_VALUE
        val day = dateTime.getDay() ?: return Long.MIN_VALUE
        val hour = dateTime.getHour() ?: 0
        val minute = dateTime.getMinute() ?: 0
        val second = dateTime.getSecond() ?: 0
        return GregorianCalendar(year, month - 1, day, hour, minute, second).timeInMillis
    }

    private fun lastUploadedMediaId(): String? {
        return prefs.getString(PREF_KEY_LAST_UPLOADED_ID, null)
    }

    private fun markUploaded(mediaId: String) {
        prefs.edit().putString(PREF_KEY_LAST_UPLOADED_ID, mediaId).apply()
    }
}
