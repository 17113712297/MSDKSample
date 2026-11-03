package com.example.msdksample

import org.junit.Assert.*
import org.junit.Test

class LandingDockSessionTest {
    private fun confirm(session: LandingDockSession, id: Long, type: LandingDockType, start: Long = 1000L) {
        repeat(11) { i ->
            session.observe(id, type == LandingDockType.NEW_DUAL, type == LandingDockType.LEGACY_MULTI, start + i * 50L)
        }
        assertEquals(type, session.lockedType())
    }

    @Test fun confirmationNeedsBothFreshFramesAndMinimumDuration() {
        val session = LandingDockSession()
        val id = session.begin()
        repeat(10) { i -> assertNull(session.observe(id, true, false, 1000L + i * 50)) }
        assertNull(session.observe(id, true, false, 1450)) // 同一时间戳不重复计数
        assertEquals(LandingDockType.NEW_DUAL, session.observe(id, true, false, 1500))
    }

    @Test fun sparseOrAlternatingCandidatesCannotLock() {
        val session = LandingDockSession()
        val id = session.begin()
        repeat(30) { i -> assertNull(session.observe(id, true, false, 1000L + i * 250)) }
        repeat(30) { i -> assertNull(session.observe(id, i % 2 == 0, i % 2 != 0, 10000L + i * 50)) }
        assertNull(session.lockedType())
    }

    @Test fun ambiguityOrLossRestartsConfirmation() {
        val session = LandingDockSession()
        val id = session.begin()
        repeat(10) { i -> session.observe(id, true, false, 1000L + i * 50) }
        assertNull(session.observe(id, true, true, 1500))
        assertNull(session.observe(id, true, false, 1550))
        assertNull(session.observe(id, false, false, 1600))
        assertNull(session.observe(id, true, false, 1650))
        assertNull(session.lockedType())
    }

    @Test fun lockedTypeSurvivesLossOtherDictionaryAndCacheReset() {
        for (type in LandingDockType.entries) {
            val session = LandingDockSession()
            val id = session.begin()
            confirm(session, id, type)
            repeat(30) { i ->
                session.observe(id, false, false, 2000L + i * 100)
                session.observe(id, type != LandingDockType.NEW_DUAL, type != LandingDockType.LEGACY_MULTI, 2050L + i * 100)
                assertEquals(type, session.lockedType())
            }
            assertTrue(session.accepts(id, type))
            assertFalse(session.accepts(id, LandingDockType.entries.first { it != type }))
        }
    }

    @Test fun activeTaskCannotBeRestartedToClearItsLock() {
        val session = LandingDockSession()
        val id = session.begin()
        confirm(session, id, LandingDockType.LEGACY_MULTI)
        assertThrows(IllegalStateException::class.java) { session.begin() }
        assertEquals(LandingDockType.LEGACY_MULTI, session.lockedType())
    }

    @Test fun stoppedTaskKeepsDiagnosticTypeButRejectsLateFrames() {
        val session = LandingDockSession()
        val id = session.begin()
        confirm(session, id, LandingDockType.NEW_DUAL)
        session.freeze()
        assertEquals(LandingDockType.NEW_DUAL, session.lockedType())
        assertNull(session.activeId())
        assertNull(session.observe(id, false, true, 2000))
        assertFalse(session.accepts(id, LandingDockType.NEW_DUAL))
    }

    @Test fun onlyNewSessionCanSelectAnotherTypeAndOldResultsAreRejected() {
        val session = LandingDockSession()
        val oldId = session.begin()
        confirm(session, oldId, LandingDockType.NEW_DUAL)
        session.freeze()
        val newId = session.begin()
        assertNotEquals(oldId, newId)
        assertNull(session.lockedType())
        assertNull(session.observe(oldId, true, false, 2000))
        confirm(session, newId, LandingDockType.LEGACY_MULTI, 2100)
        assertFalse(session.accepts(oldId, LandingDockType.LEGACY_MULTI))
        assertTrue(session.accepts(newId, LandingDockType.LEGACY_MULTI))
        assertFalse(session.accepts(null, LandingDockType.LEGACY_MULTI)) // 预检测量不能进入降落
    }

    @Test fun unknownLegacyOffsetsAreExcludedAndKnownGeometryPreservesBoardOrigin() {
        assertFalse(LegacyMarkerLandingConfig.markers.containsKey(6))
        assertFalse(LegacyMarkerLandingConfig.markers.containsKey(8))
        assertEquals(12, LegacyMarkerLandingConfig.markers.size)
        for (marker in LegacyMarkerLandingConfig.markers.values) {
            val corners = marker.corners()
            assertEquals(marker.x, corners.map { it.x }.average(), 1e-9)
            assertEquals(marker.y, corners.map { it.y }.average(), 1e-9)
            assertEquals(marker.sizeM, corners[1].x - corners[0].x, 1e-9)
        }
        assertEquals(0.18, LegacyMarkerLandingConfig.markers.getValue(5).y, 1e-9)
        assertEquals(-0.18, LegacyMarkerLandingConfig.markers.getValue(7).y, 1e-9)
    }

    @Test fun bothDocksUseSameLevelSupportAndFourCentimeterAlignment() {
        assertNull(LandingDockGeometry.landingError())
        for (type in LandingDockType.entries) {
            assertEquals(0.09, LandingDockGeometry.touchdownDepthM(type), 1e-9)
            assertEquals(0.59, LandingDockGeometry.handoffDepthM(type), 1e-9)
            assertEquals(0.50, LandingDockGeometry.clearanceM(type, 0.59), 1e-9)
            assertTrue(LandingVisionPolicy.canHandoff(0.59, LandingDockGeometry.handoffDepthM(type), 0.04, 2.9, true))
            assertFalse(LandingVisionPolicy.canHandoff(0.59, LandingDockGeometry.handoffDepthM(type), 0.041, 2.9, true))
            assertFalse(LandingVisionPolicy.canHandoff(0.59, LandingDockGeometry.handoffDepthM(type), 0.03, 3.0, true))
        }
    }
}
