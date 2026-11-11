package com.holdgrip

/**
 * Manages one continuous virtual touch represented by chained StrokeDescription objects.
 * All methods and callbacks must run on the same thread (the Android main thread in production).
 */
class HoldChain<S : Any>(
    private val port: Port<S>,
    private val log: (String) -> Unit = {}
) {
    interface Callback {
        fun onCompleted()
        fun onCancelled()
    }
    interface Port<S> {
        fun begin(durationMs: Long): S
        fun cont(prev: S, durationMs: Long, willContinue: Boolean): S
        fun dispatch(stroke: S, cb: Callback): Boolean
        fun post(delayMs: Long, r: () -> Unit): Cancelable
    }
    fun interface Cancelable { fun cancel() }
    enum class State { IDLE, OPEN, CLOSING }

    companion object {
        const val SEGMENT_MS = 40L
        const val RETRY_DELAY_MS = 15L
        const val MAX_RETRIES = 20
        const val MAX_CLOSE_ATTEMPTS = 100
        const val CALLBACK_TIMEOUT_MS = 750L
    }

    var state = State.IDLE
        private set
    var held = false
        private set
    var lastEndUnconfirmed = false
        private set

    private var generation = 0L
    private var dispatchSequence = 0L
    private var activeDispatchId: Long? = null
    private var last: S? = null
    private var outstanding = false
    private var retries = 0
    private var closeAttempts = 0
    private var pendingTimer: Cancelable? = null
    private var timeoutTimer: Cancelable? = null

    fun press(swipeMs: Long): Boolean {
        if (state != State.IDLE) {
            log("press ignored: state=" + state)
            return false
        }
        generation++
        held = true
        lastEndUnconfirmed = false
        retries = 0
        closeAttempts = 0
        outstanding = false
        activeDispatchId = null

        val duration = swipeMs.coerceAtLeast(1L)
        val first = try {
            port.begin(duration)
        } catch (error: Exception) {
            log("begin threw: " + error)
            held = false
            return false
        }
        state = State.OPEN
        last = first
        if (send(first, final = false, durationMs = duration)) return true
        log("first dispatch rejected")
        finish(confirmed = true)
        return false
    }

    fun release() {
        if (!held) return
        held = false
        log("release: state=" + state + " outstanding=" + outstanding)
        if (state == State.OPEN && !outstanding) {
            pendingTimer?.cancel()
            pendingTimer = null
            pump()
        }
    }

    fun abort() = release()

    private fun pump() {
        if (state != State.OPEN || outstanding) return
        val previous = last
        if (previous == null) {
            log("missing previous stroke; cannot safely continue")
            finish(confirmed = false)
            return
        }
        val shouldContinue = held
        val next = try {
            port.cont(previous, SEGMENT_MS, shouldContinue)
        } catch (error: Exception) {
            log("cont threw: " + error)
            held = false
            retryOrGiveUp()
            return
        }

        last = next
        if (!shouldContinue) state = State.CLOSING
        if (send(next, final = !shouldContinue, durationMs = SEGMENT_MS)) {
            retries = 0
        } else {
            last = previous
            if (!shouldContinue) state = State.OPEN
            log("dispatch rejected (held=" + held + ")")
            retryOrGiveUp()
        }
    }

    private fun retryOrGiveUp() {
        if (!held) {
            closeAttempts++
            if (closeAttempts > MAX_CLOSE_ATTEMPTS) {
                log("close FAILED after " + closeAttempts + " attempts; virtual touch may still be down")
                finish(confirmed = false)
                return
            }
        } else {
            retries++
            if (retries > MAX_RETRIES) {
                log("continue FAILED after " + retries + " retries; switching to close")
                held = false
                closeAttempts = 0
            }
        }
        val expectedGeneration = generation
        pendingTimer?.cancel()
        pendingTimer = port.post(RETRY_DELAY_MS) {
            pendingTimer = null
            if (expectedGeneration == generation && state == State.OPEN && !outstanding) pump()
        }
    }

    private fun send(stroke: S, final: Boolean, durationMs: Long): Boolean {
        val expectedGeneration = generation
        val dispatchId = ++dispatchSequence
        activeDispatchId = dispatchId
        outstanding = true

        val callback = object : Callback {
            override fun onCompleted() {
                if (!isCurrent(expectedGeneration, dispatchId)) {
                    log("stale onCompleted ignored: dispatch=" + dispatchId)
                    return
                }
                activeDispatchId = null
                outstanding = false
                timeoutTimer?.cancel()
                timeoutTimer = null
                if (final) {
                    log("final UP confirmed")
                    finish(confirmed = true)
                } else pump()
            }

            override fun onCancelled() {
                if (!isCurrent(expectedGeneration, dispatchId)) {
                    log("stale onCancelled ignored: dispatch=" + dispatchId)
                    return
                }
                activeDispatchId = null
                outstanding = false
                timeoutTimer?.cancel()
                timeoutTimer = null
                log("cancelled by system (final=" + final + " held=" + held + ")")
                finish(confirmed = true)
            }
        }

        val accepted = try {
            port.dispatch(stroke, callback)
        } catch (error: Exception) {
            log("dispatch threw: " + error)
            false
        }
        if (!accepted) {
            if (expectedGeneration == generation && activeDispatchId == dispatchId) {
                activeDispatchId = null
                outstanding = false
            }
            return false
        }

        if (isCurrent(expectedGeneration, dispatchId)) {
            timeoutTimer?.cancel()
            timeoutTimer = port.post(durationMs + CALLBACK_TIMEOUT_MS) {
                if (!isCurrent(expectedGeneration, dispatchId)) return@post
                timeoutTimer = null
                activeDispatchId = null
                outstanding = false
                log("callback LOST (final=" + final + " held=" + held + ")")
                if (final) finish(confirmed = false) else pump()
            }
        }
        return true
    }

    private fun isCurrent(expectedGeneration: Long, dispatchId: Long): Boolean =
        expectedGeneration == generation &&
            state != State.IDLE &&
            outstanding &&
            activeDispatchId == dispatchId

    private fun finish(confirmed: Boolean) {
        generation++
        state = State.IDLE
        held = false
        outstanding = false
        activeDispatchId = null
        last = null
        retries = 0
        closeAttempts = 0
        lastEndUnconfirmed = !confirmed
        pendingTimer?.cancel()
        pendingTimer = null
        timeoutTimer?.cancel()
        timeoutTimer = null
    }
}
