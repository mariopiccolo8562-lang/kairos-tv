package com.kairos.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalRotationTest {
    @Test
    fun rotationIsStableAndDifferentCodesSpreadAcrossCandidates() {
        val candidates = listOf("main", "alt1", "alt2")
        assertEquals(PortalRotation.order(candidates, "DEVICE-123"), PortalRotation.order(candidates, "DEVICE-123"))
        val starts = (0..30).map { PortalRotation.order(candidates, "DEVICE-$it").first() }.toSet()
        assertEquals(candidates.toSet(), starts)
    }

    @Test
    fun firstHealthySkipsBadCandidates() {
        val candidates = listOf("main", "alt1", "alt2")
        val code = "DEVICE-123"
        val order = PortalRotation.order(candidates, code)
        assertEquals(order[1], PortalRotation.firstHealthy(candidates, code) { it == order.first() })
        assertTrue(PortalRotation.firstHealthy(candidates, code) { true } == null)
    }

    @Test
    fun hostSelectionKeepsAutoMainFirstAndUsesRotationSlot() {
        val candidates = listOf("main", "alt1", "alt2")
        val code = "DEVICE-123"
        assertEquals("main", Portals.selectHost("auto", candidates, code, "") { false })
        assertEquals(PortalRotation.order(candidates, code).first(), Portals.selectHost("rot", candidates, code, "") { false })
    }
}
