package com.example.msdksample

import org.junit.Assert.*
import org.junit.Test

class DualMarkerLandingTest {
    @Test fun configuredDockConvertsCameraDistanceToFootClearance() {
        assertNull(DualMarkerLandingConfig.landingError())
        assertEquals(0.09, DualMarkerLandingConfig.cameraToBoardAtTouchdownM!!, 1e-9)
        assertEquals(0.59, DualMarkerLandingConfig.handoffDepthM!!, 1e-9)
        assertEquals(0.50, DualMarkerLandingConfig.nestClearanceM(0.59)!!, 1e-9)
        assertEquals(0.0, DualMarkerLandingConfig.nestClearanceM(0.09)!!, 1e-9)
    }

    @Test fun bothMarkersShareTheMidpointOrigin() {
        val front = DualMarkerLandingConfig.corners(0.12, 0.15, 0.0)
        val rear = DualMarkerLandingConfig.corners(0.08, -0.15, 0.0)
        assertEquals(0.0, (front + rear).map { it.x }.average(), 1e-9)
        assertEquals(0.0, (front + rear).map { it.y }.average(), 1e-9)
        assertEquals(0.15, front.map { it.y }.average(), 1e-9)
        assertEquals(-0.15, rear.map { it.y }.average(), 1e-9)
        assertEquals(-0.06, front[0].x, 1e-9)
        assertEquals(0.21, front[0].y, 1e-9)
    }

    @Test fun rotationPreservesCornerCorrespondenceAndCenter() {
        val corners = DualMarkerLandingConfig.corners(0.2, 0.3, 90.0)
        assertEquals(-0.1, corners[0].x, 1e-9)
        assertEquals(0.2, corners[0].y, 1e-9)
        assertEquals(0.3, corners.map { it.y }.average(), 1e-9)
    }

    @Test fun cachedMeasurementExpiresEvenWhenCoordinatesRemainValid() {
        assertTrue(LandingVisionPolicy.isFresh(1100, 1000, true, 200))
        assertFalse(LandingVisionPolicy.isFresh(1201, 1000, true, 200))
        assertFalse(LandingVisionPolicy.isFresh(1000, 1100, true, 200))
        assertFalse(LandingVisionPolicy.isFresh(1000, 1000, false, 200))
    }

    @Test fun handoffRequiresHeightAlignmentAndStabilityTogether() {
        assertTrue(LandingVisionPolicy.canHandoff(0.3, 0.35, 0.02, 2.0, true))
        assertFalse(LandingVisionPolicy.canHandoff(0.8, 0.35, 0.02, 2.0, true))
        assertFalse(LandingVisionPolicy.canHandoff(0.3, 0.35, 0.20, 2.0, true))
        assertFalse(LandingVisionPolicy.canHandoff(0.3, 0.35, 0.02, 20.0, true))
        assertFalse(LandingVisionPolicy.canHandoff(0.3, 0.35, 0.02, 2.0, false))
        assertFalse(LandingVisionPolicy.canHandoff(Double.NaN, 0.35, 0.02, 2.0, true))
    }
}
