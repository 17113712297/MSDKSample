package com.example.msdksample

import java.util.concurrent.Executors

/** 单工作线程、最多一张待处理帧。慢帧不会在 SDK 回调线程堆积成旧画面。 */
class LatestFrameProcessor<T>(private val process: (T) -> Unit, private val onError: (Throwable) -> Unit) {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "landing-vision").apply { isDaemon = true } }
    private var pending: T? = null
    private var running = false
    private var closed = false
    @Volatile var droppedFrames = 0L
        private set

    fun submit(frame: T) = synchronized(lock) {
        if (closed) return@synchronized
        if (pending != null) droppedFrames++
        pending = frame
        if (!running) {
            running = true
            executor.execute { drain() }
        }
    }

    private fun drain() {
        while (true) {
            val frame = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null || closed) { running = false; null } else next
            } ?: return
            try { process(frame) } catch (error: Throwable) { onError(error) }
        }
    }

    fun close() = synchronized(lock) {
        closed = true; pending = null
        executor.shutdownNow()
    }
}
