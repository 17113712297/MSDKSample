package com.example.msdksample

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LandingSafetyPolicyTest {
    @Test fun preflightAndLandingCannotOwnCameraAtSameTime() {
        val gate = VisionOperationGate()
        val preflight = gate.acquire(VisionOperationGate.Owner.PREFLIGHT)!!
        assertNull(gate.acquire(VisionOperationGate.Owner.LANDING))
        assertNull(gate.acquire(VisionOperationGate.Owner.PREFLIGHT))
        gate.release(preflight)
        val landing = gate.acquire(VisionOperationGate.Owner.LANDING)!!
        gate.release(preflight) // 迟到的预检结束回调不能解锁新的降落。
        assertTrue(gate.owns(landing))
        assertNull(gate.acquire(VisionOperationGate.Owner.PREFLIGHT))
        gate.release(landing)
        assertNotNull(gate.acquire(VisionOperationGate.Owner.PREFLIGHT))
    }

    @Test fun simultaneousRequestsHaveOnlyOneWinner() {
        val gate = VisionOperationGate()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val winners = Collections.synchronizedList(mutableListOf<VisionOperationGate.Lease>())
        repeat(8) { index -> Thread {
            ready.countDown()
            start.await()
            gate.acquire(VisionOperationGate.Owner.entries[index % 2])?.let { winners.add(it) }
            done.countDown()
        }.start() }
        assertTrue(ready.await(3, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, winners.size)
    }

    @Test fun downwardViewRequiresActualFiniteAttitudeAndBodyHeadingAlignment() {
        assertTrue(LandingSafetyPolicy.gimbalReady(-90.0, 0.0, 0.0))
        assertFalse(LandingSafetyPolicy.gimbalReady(-87.0, 0.0, 0.0))
        assertFalse(LandingSafetyPolicy.gimbalReady(-90.0, 4.0, 0.0))
        assertFalse(LandingSafetyPolicy.gimbalReady(-90.0, 0.0, 5.0))
        assertFalse(LandingSafetyPolicy.gimbalReady(Double.NaN, 0.0, 0.0))
    }

    @Test fun handoffRejectsDriftDescentTiltAndUnknownTelemetry() {
        assertTrue(LandingSafetyPolicy.motionSettled(0.03, 0.03, 0.05, 2.0, 2.0))
        assertFalse(LandingSafetyPolicy.motionSettled(0.04, 0.04, 0.0, 0.0, 0.0))
        assertFalse(LandingSafetyPolicy.motionSettled(0.0, 0.0, -0.11, 0.0, 0.0))
        assertFalse(LandingSafetyPolicy.motionSettled(0.0, 0.0, 0.0, 3.1, 0.0))
        assertFalse(LandingSafetyPolicy.motionSettled(Double.NaN, 0.0, 0.0, 0.0, 0.0))
    }

    @Test fun reportedAuthorityRequiresEnabledAdvancedAndMsdkOwnerTogether() {
        assertTrue(LandingSafetyPolicy.authorityValid(true, true, true))
        assertFalse(LandingSafetyPolicy.authorityValid(false, true, true))
        assertFalse(LandingSafetyPolicy.authorityValid(true, false, true))
        assertFalse(LandingSafetyPolicy.authorityValid(true, true, false))
    }

    @Test fun hysteresisPreventsThresholdChatterWithoutExceedingFourCentimeters() {
        val gate = LandingAlignmentGate()
        assertFalse(gate.update(0.035, 1.0, true))
        assertTrue(gate.update(0.029, 1.0, true))
        assertTrue(gate.update(0.039, 2.5, true))
        assertFalse(gate.update(0.041, 1.0, true))
        assertFalse(gate.update(0.039, 1.0, true))
        assertTrue(gate.update(0.029, 1.0, true))
        assertFalse(gate.update(0.029, 3.0, true))
        assertFalse(gate.update(0.029, 2.5, true))
        assertTrue(gate.update(0.029, 1.0, true))
        assertFalse(gate.update(0.029, 1.0, false))
    }

    @Test fun yawControlSlowsNearAlignmentThreshold() {
        assertEquals(1.68, LandingSafetyPolicy.alignmentYawRate(2.1), 1e-9)
        assertEquals(-1.68, LandingSafetyPolicy.alignmentYawRate(-2.1), 1e-9)
        assertEquals(20.0, LandingSafetyPolicy.alignmentYawRate(100.0), 1e-9)
        assertEquals(0.0, LandingSafetyPolicy.alignmentYawRate(Double.NaN), 1e-9)
    }

    /** C：大角度 + 帧率健康时才把上限从 20 提到 35 °/s */
    @Test fun largeYawErrorUsesFastCapOnlyAtHealthyFrameRate() {
        assertEquals(35.0, LandingSafetyPolicy.alignmentYawRate(100.0, 5.0), 1e-9)
        assertEquals(-35.0, LandingSafetyPolicy.alignmentYawRate(-100.0, 5.0), 1e-9)
        assertEquals(20.0, LandingSafetyPolicy.alignmentYawRate(100.0, 2.9), 1e-9)  // 帧率不足，不放大
        assertEquals(20.0, LandingSafetyPolicy.alignmentYawRate(25.0, 5.0), 1e-9)   // 误差不足，不放大
        assertEquals(20.0, LandingSafetyPolicy.alignmentYawRate(30.0, 5.0), 1e-9)   // 恰好 30° 仍走常规上限
        assertEquals(12.0, LandingSafetyPolicy.alignmentYawRate(15.0, 5.0), 1e-9)   // 比例段不受上限影响
    }

    /** A：丢帧时只允许在 300 ms 窗口内保持 yaw 角速度 */
    @Test fun yawHoldWindowIsBounded() {
        assertEquals(300L, LandingSafetyPolicy.YAW_HOLD_MS)
        assertFalse(LandingSafetyPolicy.shouldHoldYaw(1000, 0))        // 还没看到过目标
        assertTrue(LandingSafetyPolicy.shouldHoldYaw(1000, 1000))
        assertTrue(LandingSafetyPolicy.shouldHoldYaw(1300, 1000))      // 刚好 300 ms
        assertFalse(LandingSafetyPolicy.shouldHoldYaw(1301, 1000))     // 超窗口不再保持
        assertFalse(LandingSafetyPolicy.shouldHoldYaw(1000, 1200))     // 时间倒退不保持
    }

    @Test fun frameRateEstimatorIgnoresDuplicatesAndStaleFrames() {
        val est = FrameRateEstimator(window = 4)
        assertEquals(0.0, est.hz(), 1e-9)
        est.record(1000)
        assertEquals(0.0, est.hz(), 1e-9)          // 单帧无法估计
        est.record(1100)
        assertEquals(10.0, est.hz(), 1e-9)
        est.record(1200); est.record(1300); est.record(1400)
        assertEquals(10.0, est.hz(), 1e-9)
        est.record(1400)                            // 重复时间戳不计
        assertEquals(10.0, est.hz(), 1e-9)
        est.record(2000)                            // 窗口滑到 1200..2000 → 3 段 / 0.8 s
        assertEquals(3.75, est.hz(), 1e-9)
        est.reset()
        assertEquals(0.0, est.hz(), 1e-9)
    }

    @Test fun calibratedIntrinsicsScaleButDoNotSilentlyAcceptDifferentCrop() {
        val source = LandingCameraCalibration(1920, 1080, 1400.0, 1390.0, 960.0, 530.0,
            listOf(-0.1, 0.02, 0.001, 0.0, 0.0))
        val scaled = LandingCameraModel.intrinsics(1280, 720, source)!!
        assertTrue(scaled.calibrated)
        assertEquals(1400.0 * 2 / 3, scaled.fx, 1e-9)
        assertEquals(530.0 * 2 / 3, scaled.cy, 1e-9)
        assertEquals(source.distortion, scaled.distortion)
        assertNull(LandingCameraModel.intrinsics(1280, 960, source))
        assertNull(LandingCameraModel.intrinsics(1280, 720, source.copy(fx = Double.NaN)))
        assertNull(LandingCameraModel.intrinsics(1280, 720, source.copy(distortion = listOf(0.0))))
        assertFalse(LandingCameraModel.intrinsics(1280, 720, null)!!.calibrated)
    }

    @Test fun slowProcessorKeepsLatestPendingFrameInsteadOfBuildingBacklog() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Int>())
        val processor = LatestFrameProcessor<Int>({ frame ->
            if (frame == 1) { entered.countDown(); proceed.await() }
            results.add(frame)
            if (frame == 3) finished.countDown()
        }, { throw AssertionError(it) })
        try {
            processor.submit(1)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            processor.submit(2)
            processor.submit(3)
            proceed.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), results.toList())
            assertEquals(1L, processor.droppedFrames)
        } finally { proceed.countDown(); processor.close() }
    }

    @Test fun closingProcessorDiscardsPendingFramesAndRejectsNewSubmissions() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Int>())
        val processor = LatestFrameProcessor<Int>({ frame ->
            results.add(frame)
            entered.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { exited.countDown() }
        }, { throw AssertionError(it) })
        processor.submit(1)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        processor.submit(2)
        processor.close()
        processor.submit(3)
        assertTrue(exited.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(1), results.toList())
    }
}
