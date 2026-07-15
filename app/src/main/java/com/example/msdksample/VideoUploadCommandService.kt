package com.example.msdksample

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.msdksample.server.VideoCommandHttpServer
import com.example.msdksample.transfer.VideoTransferManager
import dji.sdk.keyvalue.value.common.ComponentIndexType

class VideoUploadCommandService : Service() {
    companion object {
        private const val TAG = "VideoUploadCommandSvc"
        private const val CHANNEL_ID = "video_upload_command_channel"
        private const val NOTIFICATION_ID = 20032

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VideoUploadCommandService::class.java))
        }
    }

    private lateinit var videoTransferManager: VideoTransferManager
    private lateinit var commandServer: VideoCommandHttpServer

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val streamController = LiveStreamController(applicationContext)
        videoTransferManager = VideoTransferManager(
            context = applicationContext,
            streamAddressProvider = { streamController.getConfiguredStreamAddress() },
            cameraIndexProvider = { ComponentIndexType.LEFT_OR_MAIN }
        ).also { manager ->
            manager.statusCallback = { status -> Log.i(TAG, status) }
        }
        commandServer = VideoCommandHttpServer { command ->
            videoTransferManager.enqueueLatestVideoTransfer(command)
        }
        commandServer.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        if (::commandServer.isInitialized) commandServer.stop()
        if (::videoTransferManager.isInitialized) videoTransferManager.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Video command server", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Video command server active")
        .setContentText("Listening for video commands on port 20032")
        .setOngoing(true)
        .build()
}
