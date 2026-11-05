package com.gammapdf.gamma.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which touch a claim takes (ClaimQueue), the cases of ipad/GammaIPadTests/PencilTests.swift ClaimQueueTests. */
class ClaimQueueTest {
    private val hereX = 100.0
    private val hereY = 200.0
    private val thereX = 300.0
    private val thereY = 260.0

    @Test
    fun aClaimTakesTheTouchThatIsDownWhereItsPointerWent() {
        val queue = ClaimQueue<Int>()
        queue.began(1, hereX, hereY, 10.0)
        assertEquals(1, queue.claim(101.0, 199.0, 10.01))
        assertNull("one claim, one touch", queue.claim(hereX, hereY, 10.02))
    }

    @Test
    fun aClaimWithNoTouchThereTakesNothing() {
        val queue = ClaimQueue<Int>()
        assertNull(queue.claim(hereX, hereY, 1.0))
        queue.began(1, hereX, hereY, 2.0)
        assertNull("a touch elsewhere is not this pointer's", queue.claim(thereX, thereY, 2.01))
        assertEquals(1, queue.claim(hereX, hereY, 2.02))
    }

    @Test
    fun aLateClaimNeverTakesAFinishedTouchNorTheNextOne() {
        val queue = ClaimQueue<Int>()
        queue.began(1, hereX, hereY, 0.0)
        queue.ended(1, 0.04) // a dot, done before its claim arrived
        queue.began(2, hereX, hereY, 0.1) // the next stroke, at the same spot
        assertNull("the dot's claim: the page drew it", queue.claim(hereX, hereY, 0.12))
        assertEquals("the next stroke's own claim", 2, queue.claim(hereX, hereY, 0.13))
    }

    @Test
    fun anUnclaimedTapElsewhereNeverStandsInTheWay() {
        val queue = ClaimQueue<Int>(grace = 0.5)
        queue.began(1, thereX, thereY, 0.0)
        queue.ended(1, 0.05) // a tap on a button: never claimed
        queue.began(2, hereX, hereY, 0.2)
        assertEquals(2, queue.claim(hereX, hereY, 0.21))
    }

    @Test
    fun strokesWrittenRightAfterAnUnclaimedTapAreAllTaken() {
        val queue = ClaimQueue<Int>(grace = 0.5)
        queue.began(1, thereX, thereY, 0.0)
        queue.ended(1, 0.05)
        var time = 0.2
        for ((touch, x) in listOf(2 to 100.0, 3 to 130.0, 4 to 160.0)) {
            queue.began(touch, x, 200.0, time)
            assertEquals("no chain of missed claims", touch, queue.claim(x, 200.0, time + 0.01))
            queue.ended(touch, time + 0.3)
            time += 0.4
        }
    }

    @Test
    fun anUnclaimedTapIsForgottenAfterTheGrace() {
        val queue = ClaimQueue<Int>(grace = 0.5)
        queue.began(1, hereX, hereY, 0.0)
        queue.ended(1, 0.05)
        queue.began(2, hereX, hereY, 2.0)
        assertEquals(2, queue.claim(hereX, hereY, 2.01))
    }

    @Test
    fun aReusedIdentityEndsOnlyTheTouchThatIsDown() {
        val queue = ClaimQueue<Int>(grace = 0.5)
        queue.began(1, thereX, thereY, 0.0)
        queue.ended(1, 0.05)
        queue.began(1, hereX, hereY, 0.2) // the same key, a new touch
        queue.ended(1, 0.3)
        assertNull("the new touch is over, not still down", queue.claim(hereX, hereY, 0.31))
    }

    @Test
    fun endedTouchesDoNotPileUp() {
        val queue = ClaimQueue<Int>(grace = 0.5)
        for (i in 0 until 100) {
            queue.began(i, hereX, hereY, i.toDouble())
            queue.ended(i, i + 0.1)
        }
        queue.began(100, hereX, hereY, 200.0)
        assertEquals("a touch going down forgets the long-ended ones", 1, queue.count)
        assertEquals(100, queue.claim(hereX, hereY, 200.01))
    }

    @Test
    fun touchesTheSystemForgotAreNotClaimable() {
        val queue = ClaimQueue<Int>()
        queue.began(1, hereX, hereY, 0.0)
        queue.endAll(0.1)
        assertNull(queue.claim(hereX, hereY, 0.2))
        assertTrue(queue.isEmpty)
    }

    @Test
    fun theReachIsTenCssPxInclusive() {
        val queue = ClaimQueue<Int>()
        assertEquals(0.5, queue.grace, 0.0)
        assertEquals(10.0, queue.reach, 0.0)
        queue.began(1, hereX, hereY, 0.0)
        assertNull("just past the reach", queue.claim(hereX + 10.01, hereY, 0.01))
        assertEquals("at the reach", 1, queue.claim(hereX + 6, hereY + 8, 0.02))
    }
}
