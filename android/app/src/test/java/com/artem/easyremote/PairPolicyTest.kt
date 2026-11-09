package com.artem.easyremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairPolicyTest {
    private fun pc(fp: String) = Pc(fp, "PC $fp", "192.168.1.2", 47821, "id$fp", "tok")

    @Test fun firstPcIsFree() {
        assertFalse(PairPolicy.needsCredit(true, emptyList(), null))
        assertFalse(PairPolicy.needsCredit(true, emptyList(), "aa"))
    }

    @Test fun secondPcNeedsCredit() {
        assertTrue(PairPolicy.needsCredit(true, listOf(pc("aa")), "bb"))
        assertTrue(PairPolicy.needsCredit(true, listOf(pc("aa")), null))
        assertTrue(PairPolicy.needsCredit(true, listOf(pc("aa"), pc("bb")), "cc"))
    }

    @Test fun rePairingKnownPcIsFree() {
        assertFalse(PairPolicy.needsCredit(true, listOf(pc("aa")), "aa"))
    }

    @Test fun directBuildHasNoLimit() {
        assertFalse(PairPolicy.needsCredit(false, listOf(pc("aa"), pc("bb")), "cc"))
    }
}
