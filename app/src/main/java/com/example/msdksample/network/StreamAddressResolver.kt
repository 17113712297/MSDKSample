package com.example.msdksample.network

import java.net.URI

object StreamAddressResolver {

    fun extractHost(streamAddress: String): String? {
        if (streamAddress.isBlank()) return null

        val parsed = runCatching { URI(streamAddress).host }.getOrNull()
        if (!parsed.isNullOrBlank()) {
            return parsed
        }

        val normalized = streamAddress.removePrefix("rtmp://")
        val hostPort = normalized.substringBefore("/").substringBefore("?")
        return hostPort.substringBefore(":").ifBlank { null }
    }
}
