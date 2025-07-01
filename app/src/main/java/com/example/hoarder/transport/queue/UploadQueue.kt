package com.example.hoarder.transport.queue

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UploadQueue {
    private val regularData = AtomicReference<String?>(null)
    private val forcedData = AtomicReference<String?>(null)
    private val isWaitingForForced = AtomicBoolean(false)

    fun queueRegular(data: String) {
        if (!isWaitingForForced.get()) {
            regularData.set(data)
        }
    }

    fun queueForced(data: String) {
        forcedData.set(data)
        isWaitingForForced.set(true)
        regularData.set(null)
    }

    fun getNextItem(): Pair<String?, Boolean> {
        val forced = forcedData.getAndSet(null)
        if (forced != null) {
            isWaitingForForced.set(false)
            return Pair(forced, true) // true indicates it was a forced item
        }

        if (isWaitingForForced.get()) {
            return Pair(null, false)
        }

        val regular = regularData.getAndSet(null)
        return Pair(regular, false)
    }

    fun clear() {
        regularData.set(null)
        forcedData.set(null)
        isWaitingForForced.set(false)
    }
}