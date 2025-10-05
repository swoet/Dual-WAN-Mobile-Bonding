package com.example.dualwan

import kotlinx.coroutines.channels.Channel

// Minimal placeholder scheduler for M1
object FlowScheduler {
    private val ch = Channel<Any>(Channel.UNLIMITED)

    fun submit(flow: Any) {
        // In M1, we simply enqueue and drop. Later milestones will implement steering.
        ch.trySend(flow)
    }
}
