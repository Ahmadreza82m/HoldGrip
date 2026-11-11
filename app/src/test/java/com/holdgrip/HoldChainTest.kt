package com.holdgrip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldChainTest {
    private data class Stroke(val parent: Stroke?, val duration: Long, val continues: Boolean)
    private class FakePort : HoldChain.Port<Stroke> {
        data class Scheduled(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        val callbacks = mutableListOf<HoldChain.Callback>()
        val strokes = mutableListOf<Stroke>()
        val scheduled = mutableListOf<Scheduled>()
        override fun begin(durationMs: Long) = Stroke(null, durationMs, true)
        override fun cont(prev: Stroke, durationMs: Long, willContinue: Boolean) = Stroke(prev, durationMs, willContinue)
        override fun dispatch(stroke: Stroke, cb: HoldChain.Callback): Boolean {
            strokes += stroke
            callbacks += cb
            return true
        }
        override fun post(delayMs: Long, r: () -> Unit): HoldChain.Cancelable {
            val item = Scheduled(delayMs, r)
            scheduled += item
            return HoldChain.Cancelable { item.cancelled = true }
        }
        fun complete(index: Int) = callbacks[index].onCompleted()
        fun fireNextTimer() {
            val item = scheduled.firstOrNull { !it.cancelled } ?: error("No active timer")
            item.cancelled = true
            item.task()
        }
    }

    @Test fun releaseWhileStrokeIsOutstandingSendsOneFinalContinuation() {
        val port = FakePort()
        val chain = HoldChain(port)
        assertTrue(chain.press(120L))
        assertEquals(HoldChain.State.OPEN, chain.state)
        chain.release()
        port.complete(0)
        assertEquals(2, port.strokes.size)
        assertFalse(port.strokes[1].continues)
        assertEquals(HoldChain.State.CLOSING, chain.state)
        port.complete(1)
        assertEquals(HoldChain.State.IDLE, chain.state)
        assertFalse(chain.lastEndUnconfirmed)
    }

    @Test fun lateCallbackFromTimedOutDispatchCannotAdvanceNewDispatch() {
        val port = FakePort()
        val chain = HoldChain(port)
        assertTrue(chain.press(120L))
        port.fireNextTimer()
        assertEquals(2, port.strokes.size)
        port.complete(0)
        assertEquals(2, port.strokes.size)
        assertEquals(HoldChain.State.OPEN, chain.state)
        chain.release()
        port.complete(1)
        assertEquals(3, port.strokes.size)
        assertFalse(port.strokes[2].continues)
        port.complete(2)
        assertEquals(HoldChain.State.IDLE, chain.state)
        assertFalse(chain.lastEndUnconfirmed)
    }
}
