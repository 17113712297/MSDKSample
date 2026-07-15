package com.example.msdksample.transfer

data class VideoUploadCommand(
    val siteId: Int,
    val deviceId: Int,
    val airlineKey: String,
    val detectTimeCur: String
)
