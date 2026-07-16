package com.example.msdksample.network

import java.net.URI

object StreamAddressResolver {

    data class ParsedStreamAddress(
        val host: String,
        val port: Int?,
        val app: String?,
        val stream: String?
    ) {
        val streamPath: String?
            get() {
                val appName = app?.trim().orEmpty()
                val streamName = stream?.trim().orEmpty()
                if (appName.isBlank() || streamName.isBlank()) {
                    return null
                }
                return "/$appName/$streamName"
            }
    }

    fun parse(streamAddress: String): ParsedStreamAddress? {
        if (streamAddress.isBlank()) return null

        val parsedUri = runCatching { URI(streamAddress) }.getOrNull()
        val host = parsedUri?.host?.takeIf { it.isNotBlank() }
            ?: extractHostFromFallback(streamAddress)
            ?: return null
        val port = parsedUri?.port?.takeIf { it > 0 } ?: extractPortFromFallback(streamAddress)
        val pathSegments = extractPathSegments(parsedUri?.path, streamAddress)
        val app = pathSegments.firstOrNull()
        val stream = pathSegments.drop(1).takeIf { it.isNotEmpty() }?.joinToString("/")

        return ParsedStreamAddress(
            host = host,
            port = port,
            app = app,
            stream = stream
        )
    }

    fun extractHost(streamAddress: String): String? {
        return parse(streamAddress)?.host
    }

    private fun extractHostFromFallback(streamAddress: String): String? {
        val hostPort = extractAuthority(streamAddress)
        return hostPort.substringBefore(":").ifBlank { null }
    }

    private fun extractPortFromFallback(streamAddress: String): Int? {
        val hostPort = extractAuthority(streamAddress)
        val portText = hostPort.substringAfter(':', "").trim()
        return portText.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun extractAuthority(streamAddress: String): String {
        return streamAddress
            .removePrefix("rtmp://")
            .removePrefix("RTMP://")
            .substringBefore("/")
            .substringBefore("?")
            .trim()
    }

    private fun extractPathSegments(parsedPath: String?, streamAddress: String): List<String> {
        val path = parsedPath
            ?.takeIf { it.isNotBlank() }
            ?: streamAddress
                .removePrefix("rtmp://")
                .removePrefix("RTMP://")
                .substringAfter("/", "")
                .substringBefore("?")

        return path.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
