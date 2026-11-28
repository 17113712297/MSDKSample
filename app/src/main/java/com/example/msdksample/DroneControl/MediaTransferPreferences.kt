package com.example.msdksample

import android.content.Context

/**
 * 媒体自动上传总开关。
 *
 * 关闭后：停录不再拉 SD 卡视频、到点也不再抓图，视频和图片都不会上传前端。
 * 默认开启，与原有行为一致。
 */
object MediaTransferPreferences {

    private const val PREFS_NAME = "media_transfer_prefs"
    private const val KEY_AUTO_UPLOAD = "auto_upload_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoUploadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPLOAD, true)

    fun setAutoUploadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPLOAD, enabled).apply()
    }
}
