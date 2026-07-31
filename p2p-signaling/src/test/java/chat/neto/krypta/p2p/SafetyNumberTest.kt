package chat.neto.krypta.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNumberTest {

    private val alice = "12D3KooWBpddCdyinZ27dFoHma5BK8mqQg7aHEtzggzWMP6yRx7K"
    private val bob = "12D3KooWLf8epxajtBmE88KPyweQEwK4SPSPHT1ozbxiwGM9nV3d"

    @Test
    fun `is symmetric regardless of argument order`() {
        assertEquals(SafetyNumber.compute(alice, bob), SafetyNumber.compute(bob, alice))
    }

    @Test
    fun `is deterministic`() {
        assertEquals(SafetyNumber.compute(alice, bob), SafetyNumber.compute(alice, bob))
    }

    @Test
    fun `changes if a peerId is substituted (the MITM case)`() {
        val attacker = "12D3KooWAttackerSubstitutedThisPeerIdInTheChannel00"
        assertNotEquals(SafetyNumber.compute(alice, bob), SafetyNumber.compute(alice, attacker))
    }

    @Test
    fun `renders 60 digits in groups of five`() {
        val sn = SafetyNumber.compute(alice, bob)
        val groups = sn.split(" ")
        assertEquals(12, groups.size)
        assertTrue(groups.all { it.length == 5 && it.all(Char::isDigit) })
    }
}
