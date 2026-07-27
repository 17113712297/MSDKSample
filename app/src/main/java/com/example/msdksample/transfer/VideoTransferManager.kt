package com.example.msdksample.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.msdksample.network.MultipartHttpClient
import com.example.msdksample.network.StreamAddressResolver
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.DateTime
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.file.FileListRequestTimeOrderType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        private const val DELETE_TIMEOUT_MS = 30_000L
        private const val DOWNLOAD_TIMEOUT_MS = 10 * 60_000L
        private const val RECORD_SETTLE_MS = 45_000L
        private const val TRANSFER_GATE_POLL_MS = 1_000L
        private const val STATUS_PROGRESS_STEP_PERCENT = 5L
        private const val STATUS_PROGRESS_INTERVAL_MS = 1_000L
        private const val EMPTY_LIST_RETRY_DELAY_MS = 3_000L
        private const val MAX_PULL_ATTEMPTS = 5
        private const val MEDIA_PULL_COUNT = 100
        private const val MEDIA_LOG_LIMIT = 12
        private const val VIDEO_DIR = "videos"
        private const val PUBLIC_VIDEO_SUBDIR = "MSDKSample"

        private val sharedPendingTransfer = AtomicReference<VideoUploadCommand?>()
        private val sharedRecordStopSequence = AtomicLong(0L)
        @Volatile private var sharedLastRecordStopEventTimeMs = 0L
        @Volatile private var sharedAwaitingCommandAfterStop = false
    }

    private data class ExportedVideoTarget(
        val uri: Uri? = null,
        val file: File? = null
    )

    private data class SelectedVideoBatch(
        val primary: MediaFile,
        val relatedFiles: List<MediaFile>
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "video-transfer").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "video-transfer-scheduler").apply { isDaemon = true }
    }
    private val workerRunning = AtomicBoolean(false)
    private val countdownFutureRef = AtomicReference<ScheduledFuture<*>?>(null)
    var statusCallback: ((String) -> Unit)? = null
    var onTransferFinished: ((Boolean, String) -> Unit)? = null

    fun enqueueLatestVideoTransfer(command: VideoUploadCommand) {
        sharedPendingTransfer.set(command)
        emitStatus("已收到 1.1 上传指令，等待录像结束后再拉取 SD 卡视频")
        emitStatus("视频传输已排队，等待安全拉取窗口")
        Log.i(
            TAG,
            "Stored latest 1.1 command: siteId=${command.siteId}, deviceId=${command.deviceId}, detectTimeCur=${command.detectTimeCur}"
        )

        val stopTimeMs = sharedLastRecordStopEventTimeMs
        if (sharedAwaitingCommandAfterStop && stopTimeMs > 0L && !isCameraRecording()) {
            sharedAwaitingCommandAfterStop = false
            enqueueLatestVideoTransferAfterRecordStop()
        }
    }

    fun enqueueLatestVideoTransferAfterRecordStop() {
        val stopTimeMs = System.currentTimeMillis()
        sharedLastRecordStopEventTimeMs = stopTimeMs
        val sequence = sharedRecordStopSequence.incrementAndGet()
        if (sharedPendingTransfer.get() == null) {
            sharedAwaitingCommandAfterStop = true
            cancelPendingCountdown()
            emitStatus("录像已结束，等待 1.1 上传指令；未收到完整字段不会倒计时或拉取 SD 卡视频")
            Log.i(TAG, "Record stopped without a pending 1.1 video upload command")
            return
        }

        sharedAwaitingCommandAfterStop = false
        cancelPendingCountdown()
        emitStatus("检测到录制结束，45 秒后开始拉取 SD 卡视频")
        Log.i(TAG, "Record stop detected, schedule transfer after 45s. seq=$sequence stopTimeMs=$stopTimeMs")
        val countdownFuture = scheduler.scheduleAtFixedRate({
            if (sharedRecordStopSequence.get() != sequence) {
                return@scheduleAtFixedRate
            }
            if (sharedPendingTransfer.get() == null) {
                return@scheduleAtFixedRate
            }
            val elapsedMs = System.currentTimeMillis() - stopTimeMs
            val remainingMs = (RECORD_SETTLE_MS - elapsedMs).coerceAtLeast(0L)
            val remainingSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
            if (remainingSeconds > 0L) {
                emitStatus("录制结束倒计时: ${remainingSeconds}s 后开始拉取 SD 卡视频")
            }
        }, 0L, 1L, TimeUnit.SECONDS)
        countdownFutureRef.set(countdownFuture)
        scheduler.schedule({
            if (sharedRecordStopSequence.get() != sequence) {
                Log.i(TAG, "Skip outdated scheduled transfer. seq=$sequence latest=${sharedRecordStopSequence.get()}")
                return@schedule
            }

            cancelPendingCountdown()
            if (sharedPendingTransfer.get() == null) {
                sharedAwaitingCommandAfterStop = true
                return@schedule
            }
            emitStatus("录制结束已满 45 秒，准备拉取 SD 卡视频")
            startPendingTransferWorker()
        }, RECORD_SETTLE_MS, TimeUnit.MILLISECONDS)
    }

    fun release() {
        cancelPendingCountdown()
        scheduler.shutdownNow()
        executor.shutdownNow()
    }

    private fun startPendingTransferWorker() {
        if (!workerRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Skip starting video transfer because another transfer is running")
            emitStatus("已有视频传输任务正在执行，最新 1.1 指令已保留")
            return
        }

        val command = sharedPendingTransfer.getAndSet(null)
        if (command == null) {
            workerRunning.set(false)
            return
        }

        executor.execute {
            try {
                runTransferSafely(command)
            } finally {
                workerRunning.set(false)
            }
        }
    }

    private fun runTransferSafely(command: VideoUploadCommand) {
        var success = false
        var summary = "Video transfer completed"
        runCatching {
            transferLatestVideo(command)
            success = true
        }.onFailure { error ->
            summary = error.message ?: "Unknown transfer error"
            Log.w(TAG, "Latest video transfer failed: ${error.message}", error)
            emitStatus("视频传输失败: ${error.message ?: "未知异常"}")
        }
        runCatching {
            onTransferFinished?.invoke(success, summary)
        }.onFailure { error ->
            Log.w(TAG, "Transfer finished callback failed: ${error.message}", error)
        }
    }

    private fun transferLatestVideo(command: VideoUploadCommand) {
        val streamAddress = streamAddressProvider.invoke().trim()
        val host = StreamAddressResolver.extractHost(streamAddress)
        if (host.isNullOrBlank()) {
            Log.w(TAG, "Skip latest video transfer because RTMP host is unavailable: $streamAddress")
            emitStatus("未配置可用 RTMP 主机，跳过视频传输")
            return
        }

        emitStatus("等待安全拉取窗口: host=$host:$UPLOAD_PORT")
        waitForSafeTransferWindow()
        sharedLastRecordStopEventTimeMs = 0L

        val mediaManager = MediaDataCenter.getInstance().mediaManager
        emitStatus("开始接管媒体管理器，准备读取无人机 SD 卡")
        configureMediaSource(mediaManager)
        enableMediaManager(mediaManager)

        try {
            val videoBatch = pullLatestVideoBatch(mediaManager) ?: run {
                Log.i(TAG, "No uploadable MP4 media file found on camera storage")
                emitStatus("未在无人机 SD 卡中找到可上传视频")
                return
            }

            val mediaFile = videoBatch.primary
            val mediaId = buildMediaId(mediaFile)
            val uploadId = "$mediaId|${command.detectTimeCur}"
            if (uploadId == lastUploadedMediaId()) {
                Log.i(TAG, "Skip upload because latest video was already uploaded: $mediaId")
                emitStatus("最新视频已上传过，跳过重复传输")
                return
            }

            emitStatus("开始从无人机 SD 卡拉取: ${mediaFile.getFileName()}")
            val localFile = downloadMediaFile(mediaFile)
            val uploadFileName = buildUploadFileName(command)
            val exportedVideoTarget = exportVideoToPublicDirectory(localFile, uploadFileName)
            emitStatus("开始上传到前端: $uploadFileName")
            uploadVideoFile(host, localFile, uploadFileName)
            notifyVideoUploadCompleted(host, command)
            markUploaded(uploadId)
            deleteCameraMediaFiles(mediaManager, videoBatch.relatedFiles)
            if (exportedVideoTarget != null) {
                deleteExportedVideo(exportedVideoTarget)
                deleteLocalWorkingFile(localFile)
            } else {
                Log.w(TAG, "Keeping local working file because public export did not complete: ${localFile.absolutePath}")
            }
            Log.i(TAG, "Latest video transfer completed successfully: $uploadFileName")
            emitStatus("视频拉取并上传完成: $uploadFileName")
        } finally {
            disableMediaManager(mediaManager)
        }
    }

    private fun waitForSafeTransferWindow() {
        var lastLogTimeMs = 0L
        var observedRecordStopTimeMs = 0L

        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Interrupted while waiting for safe transfer window")
            }

            val now = System.currentTimeMillis()
            val isRecording = runCatching {
                KeyManager.getInstance().getValue(
                    KeyTools.createKey(CameraKey.KeyIsRecording, cameraIndexProvider.invoke())
                ) ?: false
            }.getOrDefault(true)

            val recordStopTimeMs = when {
                isRecording -> {
                    observedRecordStopTimeMs = 0L
                    0L
                }
                sharedLastRecordStopEventTimeMs > 0L -> sharedLastRecordStopEventTimeMs
                else -> {
                    if (observedRecordStopTimeMs == 0L) {
                        observedRecordStopTimeMs = now
                    }
                    observedRecordStopTimeMs
                }
            }
            val timeSinceRecordStopMs = (now - recordStopTimeMs).coerceAtLeast(0L)
            val hasRecentRecordStopEvent = recordStopTimeMs > 0L
            val canTransferAfterRecordStop =
                !isRecording &&
                    timeSinceRecordStopMs >= RECORD_SETTLE_MS
            if (canTransferAfterRecordStop) {
                val detail = "停录已满 ${timeSinceRecordStopMs / 1000}s，允许开始拉取 SD 卡"
                Log.i(TAG, "Transfer gate passed: $detail")
                emitStatus("允许开始拉取 SD 卡: $detail")
                return
            }

            if (now - lastLogTimeMs >= 1_000L) {
                lastLogTimeMs = now
                val status = when {
                    isRecording -> "录制中，等待结束录制"
                    hasRecentRecordStopEvent ->
                        "已停录，等待 ${((RECORD_SETTLE_MS - timeSinceRecordStopMs).coerceAtLeast(0L)) / 1000}s"
                    else -> "等待录制结束事件，收到后 45 秒再拉取 SD 卡"
                }
                Log.i(TAG, "Waiting for safe transfer window: $status")
                emitStatus("安全窗口检查中: $status")
            }

            Thread.sleep(TRANSFER_GATE_POLL_MS)
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
        emitStatus("已锁定 SD 卡媒体源: $cameraIndex")
    }

    private fun enableMediaManager(mediaManager: IMediaManager) {
        emitStatus("正在启用媒体管理器")
        awaitCompletion("enable media manager", ENABLE_TIMEOUT_MS) { callback ->
            mediaManager.enable(callback)
        }
        Log.i(TAG, "Media manager enabled")
        emitStatus("媒体管理器已启用")
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

    private fun pullLatestVideoBatch(mediaManager: IMediaManager): SelectedVideoBatch? {
        repeat(MAX_PULL_ATTEMPTS) { attempt ->
            emitStatus("正在读取无人机 SD 卡文件列表，第 ${attempt + 1} 次尝试")
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
                emitStatus("已选中最新 MP4: ${latestMp4.getFileName()}")
                return SelectedVideoBatch(
                    primary = latestMp4,
                    relatedFiles = findRelatedVideoFiles(latestMp4, allFiles)
                )
            }

            val latestVideoFallback = allFiles
                .asSequence()
                .filter(::isDownloadableMediaFile)
                .sortedWith(mediaSortComparator())
                .firstOrNull()

            if (latestVideoFallback != null) {
                Log.w(TAG, "No MP4 found, fallback to latest video: ${describeMediaFile(latestVideoFallback)}")
                emitStatus("未找到 MP4，回退为最新视频: ${latestVideoFallback.getFileName()}")
                return SelectedVideoBatch(
                    primary = latestVideoFallback,
                    relatedFiles = findRelatedVideoFiles(latestVideoFallback, allFiles)
                )
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
        var lastProgressPercent = -1L

        Log.i(TAG, "Downloading media file to ${targetFile.absolutePath}")

        mediaFile.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
            override fun onStart() {
                Log.i(TAG, "Media download started: ${mediaFile.getFileName()}")
                emitStatus("开始下载 SD 卡视频: ${mediaFile.getFileName()}")
            }

            override fun onProgress(total: Long, current: Long) {
                Log.d(TAG, "Media download progress: $current/$total")
                if (total > 0L) {
                    val percent = (current * 100L / total).coerceIn(0L, 100L)
                    if (percent >= lastProgressPercent + 5 || percent == 100L) {
                        lastProgressPercent = percent
                        emitStatus("SD 卡拉取中: $percent%")
                    }
                }
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
        emitStatus("SD 卡拉取完成，准备上传前端")
        return targetFile
    }

    private fun uploadVideoFile(host: String, localFile: File, uploadFileName: String) {
        val encodedName = URLEncoder.encode(uploadFileName, StandardCharsets.UTF_8.name())
        val requestPath = "/upload2WRJ?file=$encodedName"
        val totalBytes = localFile.length().coerceAtLeast(1L)
        val uploadStartTimeMs = System.currentTimeMillis()
        var uploadedBytes = 0L
        var lastProgressPercent = -1L
        var lastStatusEmitTimeMs = 0L

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
                    uploadedBytes += readCount
                    val percent = (uploadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
                    val now = System.currentTimeMillis()
                    if (
                        percent >= lastProgressPercent + STATUS_PROGRESS_STEP_PERCENT ||
                        percent == 100L ||
                        now - lastStatusEmitTimeMs >= STATUS_PROGRESS_INTERVAL_MS
                    ) {
                        lastProgressPercent = percent
                        lastStatusEmitTimeMs = now
                        emitStatus(
                            "前端上传中: $percent% (${formatTransferRate(uploadedBytes, uploadStartTimeMs, now)})"
                        )
                    }
                }
            }
        }.trim()

        if (response != "1") {
            throw IllegalStateException("Upload endpoint returned unexpected body: $response")
        }

        Log.i(TAG, "Latest video upload succeeded with response=$response")
        emitStatus("前端上传成功")
    }

    private fun exportVideoToPublicDirectory(localFile: File, publicFileName: String): ExportedVideoTarget? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ExportedVideoTarget(uri = exportVideoViaMediaStore(localFile, publicFileName))
            } else {
                ExportedVideoTarget(file = exportVideoViaLegacyPublicDirectory(localFile, publicFileName))
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to export video to public directory: ${error.message}", error)
        }.getOrNull()
    }

    private fun deleteLocalWorkingFile(localFile: File) {
        runCatching {
            if (localFile.exists() && localFile.delete()) {
                Log.i(TAG, "Deleted local working video after successful upload: ${localFile.absolutePath}")
                emitStatus("已清理遥控器本地工作副本")
            } else if (localFile.exists()) {
                Log.w(TAG, "Failed to delete local working video: ${localFile.absolutePath}")
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to delete local working video: ${error.message}", error)
        }
    }

    private fun deleteExportedVideo(exportedVideoTarget: ExportedVideoTarget) {
        runCatching {
            exportedVideoTarget.uri?.let { uri ->
                val deletedCount = appContext.contentResolver.delete(uri, null, null)
                if (deletedCount > 0) {
                    Log.i(TAG, "Deleted exported public video from MediaStore: $uri")
                    emitStatus("已自动删除遥控器里的已上传视频")
                } else {
                    Log.w(TAG, "Failed to delete exported public video from MediaStore: $uri")
                }
                return@runCatching
            }

            exportedVideoTarget.file?.let { file ->
                if (file.exists() && file.delete()) {
                    Log.i(TAG, "Deleted exported public video: ${file.absolutePath}")
                    emitStatus("已自动删除遥控器里的已上传视频")
                } else if (file.exists()) {
                    Log.w(TAG, "Failed to delete exported public video: ${file.absolutePath}")
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to delete exported public video: ${error.message}", error)
        }
    }

    private fun deleteCameraMediaFiles(mediaManager: IMediaManager, mediaFiles: List<MediaFile>) {
        if (mediaFiles.isEmpty()) {
            return
        }

        runCatching {
            Log.i(TAG, "Deleting ${mediaFiles.size} related camera video file(s)")
            awaitCompletion("delete camera media files", DELETE_TIMEOUT_MS) { callback ->
                mediaManager.deleteMediaFiles(mediaFiles, callback)
            }
            awaitMediaListUpToDate(mediaManager, DELETE_TIMEOUT_MS)
            emitStatus("已自动删除无人机 SD 卡中的本次录像视频")
        }.onFailure { error ->
            Log.w(TAG, "Failed to delete camera media files: ${error.message}", error)
        }
    }

    private fun cancelPendingCountdown() {
        countdownFutureRef.getAndSet(null)?.cancel(false)
    }

    private fun isCameraRecording(): Boolean {
        return runCatching {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(CameraKey.KeyIsRecording, cameraIndexProvider.invoke())
            ) ?: false
        }.getOrDefault(true)
    }

    private fun formatTransferRate(transferredBytes: Long, startTimeMs: Long, nowTimeMs: Long): String {
        val elapsedMs = (nowTimeMs - startTimeMs).coerceAtLeast(1L)
        val bytesPerSecond = transferredBytes * 1000.0 / elapsedMs.toDouble()
        val kiloBytes = 1024.0
        val megaBytes = kiloBytes * 1024.0
        return when {
            bytesPerSecond >= megaBytes ->
                String.format(Locale.US, "%.2f MB/s", bytesPerSecond / megaBytes)
            bytesPerSecond >= kiloBytes ->
                String.format(Locale.US, "%.1f KB/s", bytesPerSecond / kiloBytes)
            else ->
                String.format(Locale.US, "%.0f B/s", bytesPerSecond)
        }
    }

    private fun emitStatus(message: String) {
        Log.i(TAG, message)
        statusCallback?.invoke(message)
    }

    private fun exportVideoViaMediaStore(localFile: File, publicFileName: String): Uri {
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
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun exportVideoViaLegacyPublicDirectory(localFile: File, publicFileName: String): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            PUBLIC_VIDEO_SUBDIR
        ).apply { mkdirs() }
        val targetFile = uniquePublicFile(publicDir, publicFileName)
        FileInputStream(localFile).use { input ->
            FileOutputStream(targetFile).use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "Exported video to public directory: ${targetFile.absolutePath}")
        return targetFile
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

    private fun awaitMediaListUpToDate(mediaManager: IMediaManager, timeoutMs: Long) {
        if (mediaManager.mediaFileListState == MediaFileListState.UP_TO_DATE) {
            return
        }

        val latch = CountDownLatch(1)
        val listener = MediaFileListStateListener { state ->
            if (state == MediaFileListState.UP_TO_DATE) {
                latch.countDown()
            }
        }
        mediaManager.addMediaFileListStateListener(listener)
        try {
            if (mediaManager.mediaFileListState == MediaFileListState.UP_TO_DATE) {
                return
            }
            val updated = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!updated) {
                throw IllegalStateException("Media file list did not return to UP_TO_DATE in time")
            }
        } finally {
            mediaManager.removeMediaFileListStateListener(listener)
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

    private fun notifyVideoUploadCompleted(host: String, command: VideoUploadCommand) {
        val query = buildString {
            append("siteId=").append(command.siteId)
            append("&deviceId=").append(command.deviceId)
            append("&airlineKey=").append(URLEncoder.encode(command.airlineKey, StandardCharsets.UTF_8.name()))
            append("&takeoffState=1")
            append("&detectTimeCur=").append(URLEncoder.encode(command.detectTimeCur, StandardCharsets.UTF_8.name()))
        }
        val connection = (URL("http://$host:$UPLOAD_PORT/sendPicOver?$query").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val statusCode = connection.responseCode
            val body = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (statusCode !in 200..299 || !Regex("\\\"resultCode\\\"\\s*:\\s*1").containsMatchIn(body)) {
                throw IllegalStateException("sendPicOver failed: HTTP $statusCode $body")
            }
            Log.i(TAG, "sendPicOver succeeded: $body")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUploadFileName(command: VideoUploadCommand): String {
        return "${command.detectTimeCur}-vcr-0001.mp4"
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

    private fun findRelatedVideoFiles(primary: MediaFile, allFiles: List<MediaFile>): List<MediaFile> {
        val primaryBaseName = recordingBaseName(primary.getFileName())
        val related = allFiles
            .asSequence()
            .filter(::isDownloadableMediaFile)
            .filter(::isMp4MediaFile)
            .filter { candidate ->
                primaryBaseName.isNotBlank() &&
                    primaryBaseName == recordingBaseName(candidate.getFileName())
            }
            .distinctBy { buildMediaId(it) }
            .sortedWith(mediaSortComparator())
            .toList()

        Log.i(
            TAG,
            "Selected related camera video files for deletion: ${related.joinToString { it.getFileName() }}"
        )
        return related
    }

    private fun recordingBaseName(fileName: String): String {
        val baseName = fileName.substringBeforeLast('.', fileName)
        return baseName.replace(Regex("(?i)_[VST]$"), "")
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
