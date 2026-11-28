package com.example.msdksample.transfer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.msdksample.WaypointPhotoStore
import com.example.msdksample.network.MultipartHttpClient
import com.example.msdksample.network.StreamAddressResolver
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraStorageInfos
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
    private val cameraIndexProvider: () -> ComponentIndexType,
    private val photoStore: WaypointPhotoStore,
    /** 媒体自动上传总开关，关闭时视频与照片都不上传 */
    private val enabledProvider: () -> Boolean
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
        private const val CLEAR_DELETE_BATCH = 20
        private const val MAX_CLEAR_ROUNDS = 20
        private const val VIDEO_DIR = "videos"
        private const val PUBLIC_VIDEO_SUBDIR = "MSDKSample"

        private val sharedPendingTransfer = AtomicReference<VideoUploadCommand?>()
        private val sharedRecordStopSequence = AtomicLong(0L)
        @Volatile private var sharedLastRecordStopEventTimeMs = 0L
        @Volatile private var sharedAwaitingCommandAfterStop = false

        /** 传输、清理、补传共用媒体管理器，三者必须互斥 */
        @Volatile private var sharedTransferRunning = false
        private val sharedCleanupRunning = AtomicBoolean(false)
        private val sharedRetryRunning = AtomicBoolean(false)

        /** 是否有媒体传输、清理或补传正在占用媒体管理器 */
        fun isMediaBusy(): Boolean =
            sharedTransferRunning || sharedCleanupRunning.get() || sharedRetryRunning.get()

        /** 当前已连接的相机部件，由主界面在可用相机回调里写入，清理时逐个遍历 */
        @Volatile
        var knownCameraIndexes: List<ComponentIndexType> = emptyList()
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

    /** 上传失败的视频/照片暂存区，工具面板的“补传失败媒体”从这里取任务 */
    private val failedUploadStore = FailedUploadStore(appContext)

    /** 最近一次读取到的无人机存储占用，用于清理结果里给出可核对的数字 */
    @Volatile
    private var storageUsageText: String = ""
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
        if (!enabledProvider()) {
            emitStatus("媒体自动上传已关闭，本次视频与照片均不上传")
            Log.i(TAG, "Skip 1.1 command because media auto upload is disabled")
            return
        }
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
        if (!enabledProvider()) {
            emitStatus("媒体自动上传已关闭，停录后不拉取 SD 卡")
            Log.i(TAG, "Skip record-stop transfer because media auto upload is disabled")
            return
        }
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

    /**
     * 用户把总开关切到关闭时调用。
     *
     * 清掉已排队的 1.1 指令、未到点的倒计时、本地待传照片，
     * 让"关闭"对当前这一批也立即生效，不是只影响下一次。
     * 已经在跑的传输不会被打断，但会在下一个检查点自行停下。
     */
    fun cancelPendingMediaTransfer() {
        cancelPendingCountdown()
        val hadPendingCommand = sharedPendingTransfer.getAndSet(null) != null
        sharedAwaitingCommandAfterStop = false
        val droppedPhotos = photoStore.pending().size
        photoStore.clearPending()
        if (hadPendingCommand || droppedPhotos > 0) {
            Log.i(
                TAG,
                "Cancelled pending media transfer: command=$hadPendingCommand, photos=$droppedPhotos"
            )
            emitStatus("媒体上传已关闭，已丢弃排队的上传任务与 $droppedPhotos 张待传照片")
        }
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
            sharedTransferRunning = true
            try {
                runTransferSafely(command)
            } finally {
                sharedTransferRunning = false
                workerRunning.set(false)
            }
        }
    }

    /**
     * 一键清除无人机 SD 卡与遥控器本地全部视频/图片。
     *
     * 与自动上传互斥：传输进行中直接拒绝，避免两个线程同时操作媒体管理器。
     * 清理在传输线程池里串行执行，回调在后台线程触发。
     */
    fun clearAllMediaFiles(onFinished: ((Boolean, String) -> Unit)? = null) {
        if (sharedTransferRunning) {
            emitStatus("媒体传输进行中，请等传输结束后再清理")
            onFinished?.invoke(false, "媒体传输进行中")
            return
        }
        if (!sharedCleanupRunning.compareAndSet(false, true)) {
            emitStatus("清理任务正在执行，请稍候")
            return
        }
        emitStatus("已开始清理媒体文件")
        executor.execute {
            var success = false
            var summary = "清理未完成"
            try {
                summary = performClearAllMedia()
                success = true
            } catch (error: Exception) {
                summary = error.message ?: "清理失败"
                Log.w(TAG, "Clear all media failed: ${error.message}", error)
                emitStatus("清理失败：$summary")
            } finally {
                sharedCleanupRunning.set(false)
                Log.i(TAG, "clearAllMediaFiles finished success=$success summary=$summary")
                onFinished?.invoke(success, summary)
            }
        }
    }

    /**
     * 手动补传自动上传失败的媒体。
     *
     * 与自动上传的不同点：
     * - 不受“媒体自动上传”开关限制（用户主动点了就是要传）；
     * - 不需要 1.1 指令，也不需要 45 秒安全窗口，直接用失败时留下的本地副本重传；
     * - 沿用失败时的批次前缀（`{detectTimeCur}-vcr-0001.mp4` / `-pic-000N.jpg`），前端不会串批次；
     * - 一个批次全部补传成功后才补发 `sendPicOver`，部分失败则下次继续补。
     *
     * 录制中拒绝执行：既要避免干扰当前录像，也要避免和下一批自动上传抢媒体管理器。
     */
    fun retryFailedUploads(onFinished: ((Boolean, String) -> Unit)? = null) {
        val pending = failedUploadStore.entries()
        if (pending.isEmpty()) {
            emitStatus("没有需要补传的媒体")
            onFinished?.invoke(true, "没有需要补传的媒体")
            return
        }
        if (isCameraRecording()) {
            emitStatus("正在录像，请停止录制后再补传")
            onFinished?.invoke(false, "正在录像")
            return
        }
        if (sharedTransferRunning || sharedCleanupRunning.get()) {
            emitStatus("媒体任务进行中，请稍后补传")
            onFinished?.invoke(false, "媒体任务进行中")
            return
        }
        if (!sharedRetryRunning.compareAndSet(false, true)) {
            emitStatus("补传任务正在执行，请稍候")
            onFinished?.invoke(false, "补传任务正在执行")
            return
        }
        emitStatus("开始补传 ${pending.size} 个失败媒体")
        executor.execute {
            var success = false
            var summary = "补传未完成"
            try {
                summary = performRetryFailedUploads()
                success = true
            } catch (error: Exception) {
                summary = error.message ?: "补传失败"
                Log.w(TAG, "Retry failed uploads error: ${error.message}", error)
                emitStatus("补传失败：$summary")
            } finally {
                sharedRetryRunning.set(false)
                Log.i(TAG, "retryFailedUploads finished success=$success summary=$summary")
                onFinished?.invoke(success, summary)
            }
        }
    }

    /** 工具面板展示用：待补传的数量与总字节数 */
    fun failedUploadSummary(): FailedUploadStore.Summary = failedUploadStore.summary()

    private fun performRetryFailedUploads(): String {
        val host = StreamAddressResolver.extractHost(streamAddressProvider.invoke().trim())
        if (host.isNullOrBlank()) {
            emitStatus("未配置可用 RTMP 主机，无法补传")
            return "未配置 RTMP 主机"
        }

        val entries = failedUploadStore.entries()
        if (entries.isEmpty()) {
            emitStatus("没有需要补传的媒体")
            return "没有需要补传的媒体"
        }

        var uploaded = 0
        var failed = 0
        val total = entries.size

        entries.groupBy { it.detectTimeCur }.forEach { (detectTimeCur, batchEntries) ->
            var uploadedInBatch = 0
            var batchAllOk = true
            batchEntries.forEach { entry ->
                val file = failedUploadStore.file(entry)
                if (!file.isFile || file.length() <= 0L) {
                    failedUploadStore.remove(entry.id)
                    Log.w(TAG, "补传跳过已丢失的文件: ${entry.uploadName}")
                    return@forEach
                }
                emitStatus("补传中 ${uploaded + failed + 1}/$total: ${entry.uploadName}")
                runCatching {
                    when (entry.type) {
                        FailedUploadStore.TYPE_PHOTO -> uploadPhotoFile(host, file, entry.uploadName)
                        else -> uploadVideoFile(host, file, entry.uploadName)
                    }
                }.onSuccess {
                    uploaded += 1
                    uploadedInBatch += 1
                    failedUploadStore.remove(entry.id)
                    Log.i(TAG, "补传成功: ${entry.uploadName}")
                    emitStatus("补传成功 $uploaded/$total: ${entry.uploadName}")
                }.onFailure { error ->
                    failed += 1
                    batchAllOk = false
                    failedUploadStore.markFailed(entry.id, error.message ?: "未知异常")
                    Log.w(TAG, "补传失败: ${entry.uploadName}: ${error.message}")
                    emitStatus("补传失败 ${entry.uploadName}：${error.message ?: "未知异常"}")
                }
            }

            if (batchAllOk && uploadedInBatch > 0) {
                val command = batchEntries.first().command()
                runCatching {
                    notifyVideoUploadCompleted(host, command)
                    Log.i(TAG, "补传后已完成通知: detectTimeCur=$detectTimeCur")
                }.onFailure { error ->
                    Log.w(TAG, "补传后完成通知失败: ${error.message}")
                    emitStatus("补传完成但完成通知失败：${error.message ?: "未知异常"}")
                }
            }
        }

        val left = failedUploadStore.summary().count
        val summary = buildString {
            append("成功 $uploaded 个，失败 $failed 个")
            if (left > 0) append("，仍有 $left 个待补传")
        }
        emitStatus(
            when {
                uploaded == 0 && failed == 0 -> "补传结束：没有可上传的文件"
                failed == 0 -> "补传完成：$summary"
                else -> "补传结束：$summary"
            }
        )
        return summary
    }

    private fun performClearAllMedia(): String {
        emitStatus("清理遥控器本地缓存与已导出文件")
        val localDeleted = clearLocalMediaFiles()
        emitStatus("遥控器本地已清理 $localDeleted 个文件")

        var cameraDeleted = 0
        var cameraNote = ""
        if (isCameraRecording()) {
            cameraNote = "正在录像，SD 卡未清理"
            emitStatus(cameraNote)
        } else {
            runCatching { cameraDeleted = clearCameraMediaFiles() }.onFailure { error ->
                cameraNote = "SD 卡未清理：${error.message ?: "未知异常"}"
                Log.w(TAG, "Clear camera media failed: ${error.message}", error)
                emitStatus(cameraNote)
            }
        }

        val summary = buildString {
            append("遥控器 $localDeleted 个")
            append("，无人机 $cameraDeleted 个")
            if (storageUsageText.isNotBlank()) append("，$storageUsageText")
            if (cameraNote.isNotBlank()) append("（$cameraNote）")
        }
        val nothingToDelete = localDeleted == 0 && cameraDeleted == 0 && cameraNote.isBlank()
        emitStatus(
            if (nothingToDelete) {
                "媒体清理完成：没有可删除的文件" +
                    if (storageUsageText.isNotBlank()) "（$storageUsageText）" else ""
            } else {
                "媒体清理完成：$summary"
            }
        )
        return summary
    }

    private fun clearCameraMediaFiles(): Int {
        val indexes = candidateCameraIndexes()
        Log.i(TAG, "Clear camera media for indexes=$indexes")
        var deleted = 0
        var remaining = 0
        indexes.forEach { index ->
            availableStorageLocations().forEach { location ->
                val result = runCatching { clearOneStorage(index, location) }.getOrElse { error ->
                    Log.w(TAG, "Clear $index/$location failed: ${error.message}")
                    // 只有当前正在使用的相机才提示失败，其它部件（如视觉辅助相机）没有存储属正常
                    if (index == currentCameraIndex()) {
                        emitStatus("${storageLabel(location)}清理未完成：${error.message ?: "未知异常"}")
                    }
                    StorageClearResult(0, 0)
                }
                deleted += result.deleted
                remaining += result.remaining
            }
        }
        clearUploadedMediaRecord()
        Log.i(TAG, "Camera media cleared: deleted=$deleted remaining=$remaining")
        // 剩余数量写进提示，避免界面显示“清理完成”但实际还有文件
        if (remaining > 0) emitStatus("无人机存储仍有 $remaining 个文件未能删除")
        return deleted
    }

    /** 逐个相机部件、逐个存储位置清理，返回删除数与清理后剩余数 */
    private fun clearOneStorage(
        index: ComponentIndexType,
        location: CameraStorageLocation
    ): StorageClearResult {
        val mediaManager = MediaDataCenter.getInstance().mediaManager
        switchMediaSource(mediaManager, index, location)
        var deleted = 0
        var failed = 0
        try {
            for (round in 1..MAX_CLEAR_ROUNDS) {
                val files = pullMediaFileList(mediaManager, MediaFileFilter.ALL)
                if (files.isEmpty()) break
                emitStatus("${storageLabel(location)} 第 $round 轮：待删除 ${files.size} 个文件")
                var deletedInRound = 0
                files.chunked(CLEAR_DELETE_BATCH).forEach { chunk ->
                    val ok = runCatching {
                        awaitCompletion("delete all media files", DELETE_TIMEOUT_MS) { callback ->
                            mediaManager.deleteMediaFiles(chunk, callback)
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Delete media batch failed: ${error.message}")
                    }.isSuccess
                    if (ok) {
                        deleted += chunk.size
                        deletedInRound += chunk.size
                        emitStatus("${storageLabel(location)} 已删除 $deleted 个文件")
                    } else {
                        failed += chunk.size
                    }
                }
                runCatching { awaitMediaListUpToDate(mediaManager, DELETE_TIMEOUT_MS) }
                if (deletedInRound == 0) {
                    Log.w(TAG, "No media deleted in round $round for $index/$location, stop retrying")
                    break
                }
            }
            val left = runCatching { pullMediaFileList(mediaManager, MediaFileFilter.ALL).size }
                .getOrDefault(-1)
            Log.i(TAG, "Storage $index/$location: deleted=$deleted failed=$failed remaining=$left")
            if (failed > 0) {
                emitStatus("${storageLabel(location)}有 $failed 个文件删除失败")
            }
            return StorageClearResult(deleted, left.coerceAtLeast(0))
        } finally {
            runCatching {
                awaitCompletion("disable media manager", DISABLE_TIMEOUT_MS) { mediaManager.disable(it) }
            }
        }
    }

    private fun switchMediaSource(
        mediaManager: IMediaManager,
        index: ComponentIndexType,
        location: CameraStorageLocation
    ) {
        // 切换数据源前必须先关闭，否则沿用上一次的相机部件
        runCatching {
            awaitCompletion("disable media manager", DISABLE_TIMEOUT_MS) { mediaManager.disable(it) }
        }
        val dataSource = MediaFileListDataSource.Builder()
            .setLocation(location)
            .setIndexType(index)
            .build()
        mediaManager.setMediaFileDataSource(dataSource)
        Log.i(TAG, "Configured media source index=$index location=$location")
        emitStatus("正在启用${storageLabel(location)}：$index")
        awaitCompletion("enable media manager", ENABLE_TIMEOUT_MS) { mediaManager.enable(it) }
        Log.i(TAG, "Media manager enabled for $index/$location")
    }

    private fun candidateCameraIndexes(): List<ComponentIndexType> {
        val current = currentCameraIndex()
        val indexes = (knownCameraIndexes + current)
            .filter { it != ComponentIndexType.UNKNOWN }
            .distinct()
        return indexes.ifEmpty { listOf(ComponentIndexType.LEFT_OR_MAIN) }
    }

    /**
     * 当前相机实际存在的存储位置。
     *
     * 只查厂商上报的 CameraStorageInfos，查不到就退回 SD 卡，
     * 避免在不支持内置存储的机型上每次都要等一遍启用超时。
     */
    private fun availableStorageLocations(): List<CameraStorageLocation> {
        val latch = CountDownLatch(1)
        var locations: List<CameraStorageLocation> = emptyList()
        runCatching {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(CameraKey.KeyCameraStorageInfos),
                object : CommonCallbacks.CompletionCallbackWithParam<CameraStorageInfos> {
                    override fun onSuccess(value: CameraStorageInfos?) {
                        value?.cameraStorageInfoList?.forEach { info ->
                            Log.i(
                                TAG,
                                "Storage ${info.storageType} state=${info.storageState} " +
                                    "capacity=${info.storageCapacity} left=${info.storageLeftCapacity}"
                            )
                            if (info.storageType == CameraStorageLocation.SDCARD) {
                                val capacity = info.storageCapacity ?: 0
                                val left = info.storageLeftCapacity ?: 0
                                val used = (capacity - left).coerceAtLeast(0)
                                storageUsageText =
                                    "SD 卡已用 ${formatMb(used)} / 共 ${formatMb(capacity)}"
                            }
                        }
                        locations = value?.cameraStorageInfoList
                            ?.mapNotNull { it.storageType }
                            ?.filter {
                                it == CameraStorageLocation.SDCARD ||
                                    it == CameraStorageLocation.INTERNAL ||
                                    it == CameraStorageLocation.INTERNAL_SSD
                            }
                            .orEmpty()
                        latch.countDown()
                    }

                    override fun onFailure(error: IDJIError) {
                        latch.countDown()
                    }
                }
            )
        }
        latch.await(2, TimeUnit.SECONDS)
        Log.i(TAG, "Available storage locations=$locations")
        return locations.ifEmpty { listOf(CameraStorageLocation.SDCARD) }
    }

    private fun storageLabel(location: CameraStorageLocation): String = when (location) {
        CameraStorageLocation.SDCARD -> "SD 卡"
        CameraStorageLocation.INTERNAL -> "无人机内置存储"
        CameraStorageLocation.INTERNAL_SSD -> "无人机 SSD"
        else -> location.name
    }

    private fun currentCameraIndex(): ComponentIndexType =
        runCatching { cameraIndexProvider.invoke() }.getOrDefault(ComponentIndexType.LEFT_OR_MAIN)

    private fun formatMb(megabytes: Int): String =
        if (megabytes >= 1024) {
            String.format(Locale.US, "%.1f GB", megabytes / 1024.0)
        } else {
            "$megabytes MB"
        }

    private data class StorageClearResult(val deleted: Int, val remaining: Int)

    private fun clearLocalMediaFiles(): Int {
        var deleted = 0
        val pendingPhotos = photoStore.pending().size
        photoStore.clearPending()
        deleted += pendingPhotos

        // 待补传的失败媒体同样属于“遥控器本地媒体”，一并清掉
        deleted += failedUploadStore.clearAll()

        val videoDir = File(appContext.filesDir, VIDEO_DIR)
        videoDir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) deleted += 1
        }

        deleted += clearExportedPublicVideos()
        return deleted
    }

    private fun clearExportedPublicVideos(): Int {
        var deleted = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%$PUBLIC_VIDEO_SUBDIR%")
            runCatching {
                appContext.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        if (appContext.contentResolver.delete(uri, null, null) > 0) deleted += 1
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to clear exported public videos: ${error.message}", error)
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                PUBLIC_VIDEO_SUBDIR
            )
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.delete()) deleted += 1
            }
        }
        return deleted
    }

    private fun clearUploadedMediaRecord() {
        runCatching { prefs.edit().remove(PREF_KEY_LAST_UPLOADED_ID).apply() }
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
        if (!enabledProvider()) {
            Log.i(TAG, "Abort transfer because media auto upload is disabled")
            emitStatus("媒体自动上传已关闭，取消本批次传输")
            return
        }

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

        // 等待窗口期间用户可能把开关关掉，这里再确认一次，避免白拉一遍 SD 卡
        if (!enabledProvider()) {
            Log.i(TAG, "Abort transfer after settle window because auto upload got disabled")
            emitStatus("媒体自动上传已关闭，取消本批次传输")
            return
        }

        // 航点抓拍的照片体积小、传得快，先传掉，前端能立刻看到点位图；
        // 单张失败只记日志，不阻断后面的视频上传。
        uploadPendingPhotos(host, command)

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
            try {
                uploadVideoFile(host, localFile, uploadFileName)
            } catch (uploadError: Exception) {
                // 上传失败：本地副本留在暂存区等补传，同时删掉刚导出的公开副本，避免同一段视频占两份空间。
                // SD 卡上的原始片段同样保留，补传成功后再走正常删除流程。
                if (exportedVideoTarget != null) deleteExportedVideo(exportedVideoTarget)
                val kept = failedUploadStore.adopt(
                    source = localFile,
                    uploadName = uploadFileName,
                    command = command,
                    type = FailedUploadStore.TYPE_VIDEO,
                    error = uploadError.message
                )
                emitStatus(
                    if (kept) {
                        "视频上传失败，已留存到遥控器，可在工具里补传"
                    } else {
                        "视频上传失败，且无法留存本地副本"
                    }
                )
                throw uploadError
            }
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

    private fun pullMediaFileList(
        mediaManager: IMediaManager,
        filter: MediaFileFilter = MediaFileFilter.VIDEO
    ): List<MediaFile> {
        val stateLatch = CountDownLatch(1)
        val stateListener = MediaFileListStateListener { state ->
            if (state == MediaFileListState.UP_TO_DATE) {
                stateLatch.countDown()
            }
        }
        mediaManager.addMediaFileListStateListener(stateListener)

        try {
            val param = PullMediaFileListParam.Builder()
                .filter(filter)
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

    /**
     * 上传本次任务攒下的航点照片。
     *
     * 命名 `{detectTimeCur}-pic-000N.jpg`，与视频的 `-vcr-` 共用同一批次前缀，
     * 前端按前缀归组即可把图与视频归到同一次任务下。
     *
     * 单张失败不重试，也不立刻丢弃：把文件挪进失败暂存区并记下本批次前缀，
     * 用户可在工具面板点“补传失败媒体”重新上传，补传时仍用 `{detectTimeCur}-pic-000N.jpg`，
     * 不会因为换了批次前缀把旧图算进新任务。失败也不阻断后面的视频上传。
     */
    private fun uploadPendingPhotos(host: String, command: VideoUploadCommand) {
        val photos = photoStore.pending()
        if (photos.isEmpty()) {
            Log.i(TAG, "No pending waypoint photos to upload")
            emitStatus("本次任务没有待上传的航点照片")
            return
        }

        emitStatus("开始上传 ${photos.size} 张航点照片")
        var uploadedCount = 0
        photos.forEachIndexed { index, photo ->
            val uploadFileName = String.format(
                Locale.US,
                "%s-pic-%04d.jpg",
                command.detectTimeCur,
                index + 1
            )
            runCatching {
                uploadPhotoFile(host, photo, uploadFileName)
            }.onSuccess {
                uploadedCount += 1
                photoStore.delete(photo)
                Log.i(TAG, "Waypoint photo uploaded: $uploadFileName (from ${photo.name})")
                emitStatus("照片上传中 $uploadedCount/${photos.size}")
            }.onFailure { error ->
                // 失败保留：挪进暂存区，等用户手动补传，避免旧图被下一批次重新命名
                val kept = failedUploadStore.adopt(
                    source = photo,
                    uploadName = uploadFileName,
                    command = command,
                    type = FailedUploadStore.TYPE_PHOTO,
                    error = error.message
                )
                // 暂存失败就只能按老逻辑丢弃，否则会被下一批次套新前缀重命名
                if (!kept) photoStore.delete(photo)
                Log.w(
                    TAG,
                    "Waypoint photo upload failed, kept=$kept ${photo.name} ($uploadFileName): ${error.message}"
                )
                emitStatus(
                    if (kept) {
                        "照片上传失败 $uploadFileName，已留存可手动补传"
                    } else {
                        "照片上传失败 $uploadFileName：${error.message ?: "未知异常"}，已丢弃"
                    }
                )
            }
        }
        val failedCount = photos.size - uploadedCount
        emitStatus(
            if (failedCount == 0) {
                "航点照片上传完成 $uploadedCount/${photos.size}"
            } else {
                "航点照片上传完成 $uploadedCount/${photos.size}，$failedCount 张失败已丢弃"
            }
        )
    }

    private fun uploadPhotoFile(host: String, localFile: File, uploadFileName: String) {
        val encodedName = URLEncoder.encode(uploadFileName, StandardCharsets.UTF_8.name())
        val requestPath = "/upload2WRJ?file=$encodedName"

        val response = FileInputStream(localFile).use { input ->
            MultipartHttpClient.postMultipart(
                host = host,
                port = UPLOAD_PORT,
                requestPath = requestPath,
                accept = "*/*",
                partFieldName = "file",
                fileName = uploadFileName,
                contentType = "image/jpeg",
                contentLength = localFile.length(),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            ) { output ->
                input.copyTo(output, 64 * 1024)
            }
        }.trim()

        if (response != "1") {
            throw IllegalStateException("图片上传返回异常: $response")
        }
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
