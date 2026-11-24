package com.atomvpn.mobile.engine.atom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AtomCoreConfigTest {
    @Test
    fun unknownAutomaticCarrierUsesDualStackMasqueHttp2() {
        val plan = AtomCoreConfig.outerPlan(
            settings = AtomEngineSettings.DEFAULT.copy(carrierMode = AtomCoreConfig.CARRIER_AUTO),
            mci = false,
            carrierClass = "unknown",
        )

        assertEquals(AtomCoreConfig.OUTER_LADDER, plan.protocols)
        assertEquals("both", plan.ipMode)
        assertEquals("h2", plan.masqueTransport)
        assertFalse(plan.fixedOrder)
    }

    @Test
    fun knownIrancellAutomaticCarrierKeepsItsExistingHttp3Policy() {
        val plan = AtomCoreConfig.outerPlan(
            settings = AtomEngineSettings.DEFAULT.copy(carrierMode = AtomCoreConfig.CARRIER_AUTO),
            mci = false,
            carrierClass = "irancell",
        )

        assertEquals("v4", plan.ipMode)
        assertEquals("h3", plan.masqueTransport)
    }

    @Test
    fun knownMciAutomaticCarrierKeepsItsExistingHttp2Policy() {
        val plan = AtomCoreConfig.outerPlan(
            settings = AtomEngineSettings.DEFAULT.copy(carrierMode = AtomCoreConfig.CARRIER_AUTO),
            mci = true,
            carrierClass = "mci",
        )

        assertEquals(listOf("masque"), plan.protocols)
        assertEquals("both", plan.ipMode)
        assertEquals("h2", plan.masqueTransport)
    }
}
