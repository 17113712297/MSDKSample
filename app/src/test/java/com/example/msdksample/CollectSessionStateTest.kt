package com.example.msdksample

import com.example.msdksample.CollectSessionState.Phase
import org.junit.Assert.*
import org.junit.Test

class CollectSessionStateTest {
    @Test fun idleSessionDoesNotAcceptStopOrGeneratedResults() {
        val session = CollectSessionState()
        assertFalse(session.isCollecting)
        assertFalse(session.stopPending)
        assertFalse(session.requestStop(100))
        assertFalse(session.acceptGenerated(Phase.MAP_2D_DONE))
        assertFalse(session.acceptGenerated(Phase.PIXEL_DONE))
        assertEquals(Phase.IDLE, session.phase)
    }

    @Test fun acceptedStartActivatesSessionAndStopRequestKeepsItActive() {
        val session = CollectSessionState()
        session.acceptStart()
        assertEquals(Phase.RUNNING, session.phase)
        assertTrue(session.isCollecting)
        assertTrue(session.requestStop(100))
        assertTrue(session.stopPending)
        assertTrue(session.isCollecting)
        assertEquals(Phase.RUNNING, session.phase)
    }

    @Test fun repeatedStopDoesNotReplaceOriginalDeadline() {
        val session = CollectSessionState()
        session.acceptStart()
        assertTrue(session.requestStop(100))
        assertFalse(session.requestStop(9_000))
        assertTrue(session.expireStop(10_100))
        assertFalse(session.stopPending)
    }

    @Test fun acceptedStopClearsSessionAndItsPendingRequest() {
        val session = CollectSessionState()
        session.acceptStart()
        session.requestStop(100)
        session.acknowledgeStop(true)
        assertEquals(Phase.IDLE, session.phase)
        assertFalse(session.isCollecting)
        assertFalse(session.stopPending)
        assertFalse(session.expireStop(20_000))
    }

    @Test fun rejectedStopKeepsCurrentPhaseAndAllowsRetry() {
        val session = CollectSessionState()
        session.acceptStart()
        session.acceptGenerated(Phase.PIXEL_DONE)
        session.requestStop(100)
        session.acknowledgeStop(false)
        assertTrue(session.isCollecting)
        assertEquals(Phase.PIXEL_DONE, session.phase)
        assertFalse(session.stopPending)
        assertTrue(session.requestStop(200))
    }

    @Test fun timeoutKeepsSessionActiveAndUnlocksRetryAtTenSeconds() {
        val session = CollectSessionState()
        session.acceptStart()
        session.requestStop(100)
        assertFalse(session.expireStop(10_099))
        assertTrue(session.stopPending)
        assertTrue(session.expireStop(10_100))
        assertTrue(session.isCollecting)
        assertFalse(session.stopPending)
        assertTrue(session.requestStop(10_101))
    }

    @Test fun lateStopSuccessAfterTimeoutStillEndsConfirmedSession() {
        val session = CollectSessionState()
        session.acceptStart()
        session.requestStop(100)
        session.expireStop(10_100)
        session.acknowledgeStop(true)
        assertFalse(session.isCollecting)
        assertEquals(Phase.IDLE, session.phase)
    }

    @Test fun generatedResultsKeepActiveSessionAndCannotResurrectStoppedSession() {
        val session = CollectSessionState()
        session.acceptStart()
        assertTrue(session.acceptGenerated(Phase.MAP_2D_DONE))
        assertTrue(session.isCollecting)
        session.requestStop(100)
        assertTrue(session.acceptGenerated(Phase.PIXEL_DONE))
        assertTrue(session.stopPending)
        session.acknowledgeStop(true)
        assertFalse(session.acceptGenerated(Phase.MAP_2D_DONE))
        assertFalse(session.acceptGenerated(Phase.PIXEL_DONE))
        assertFalse(session.isCollecting)
    }
}
