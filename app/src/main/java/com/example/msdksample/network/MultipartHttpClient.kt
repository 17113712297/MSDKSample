package com.example.msdksample.network

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object MultipartHttpClient {

    fun postMultipart(
        host: String,
        port: Int,
        requestPath: String,
        accept: String,
        partFieldName: String,
        fileName: String,
        contentType: String,
        contentLength: Long,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        bodyWriter: (OutputStream) -> Unit
    ): String {
        val boundary = "----MSDKSample${System.currentTimeMillis()}"
        val lineBreak = "\r\n"
        val bodyPrefix = buildString {
            append("--").append(boundary).append(lineBreak)
            append("Content-Disposition: form-data; name=\"")
                .append(partFieldName)
                .append("\"; filename=\"")
                .append(fileName)
                .append("\"")
                .append(lineBreak)
            append("Content-Type: ").append(contentType).append(lineBreak)
            append(lineBreak)
        }.toByteArray(StandardCharsets.UTF_8)
        val bodySuffix = (lineBreak + "--" + boundary + "--" + lineBreak)
            .toByteArray(StandardCharsets.UTF_8)
        val totalBodyLength = bodyPrefix.size.toLong() + contentLength + bodySuffix.size.toLong()

        Socket().use { socket ->
            socket.soTimeout = readTimeoutMs
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val requestHeaders = buildString {
                append("POST ").append(requestPath).append(" HTTP/1.1").append(lineBreak)
                append("Host: ").append(host).append(":").append(port).append(lineBreak)
                append("Accept: ").append(accept).append(lineBreak)
                append("Connection: close").append(lineBreak)
                append("Content-Type: multipart/form-data; boundary=")
                    .append(boundary)
                    .append(lineBreak)
                append("Content-Length: ").append(totalBodyLength).append(lineBreak)
                append(lineBreak)
            }.toByteArray(StandardCharsets.UTF_8)

            val output = socket.getOutputStream()
            output.write(requestHeaders)
            output.write(bodyPrefix)
            bodyWriter(output)
            output.write(bodySuffix)
            output.flush()

            val responseBytes = ByteArrayOutputStream()
            BufferedInputStream(socket.getInputStream()).use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val readCount = input.read(buffer)
                    if (readCount < 0) break
                    responseBytes.write(buffer, 0, readCount)
                }
            }

            val responseText = responseBytes.toString(StandardCharsets.UTF_8.name())
            val statusLine = responseText.lineSequence().firstOrNull().orEmpty()
            val statusCode = statusLine
                .split(" ")
                .getOrNull(1)
                ?.toIntOrNull()
                ?: throw IllegalStateException("Invalid HTTP response: $statusLine")
            val responseBody = responseText
                .substringAfter("\r\n\r\n", "")
                .trim()

            if (statusCode !in 200..299) {
                throw IllegalStateException("HTTP $statusCode $responseBody")
            }

            return responseBody.ifBlank { "HTTP $statusCode" }
        }
    }
}
