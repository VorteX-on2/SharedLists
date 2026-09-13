package dev.sharedlists.spike.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class ForegroundSession(
    private val scope: CoroutineScope,
    private val releaseTransport: suspend () -> Unit,
) {
    private var streamJob: Job? = null

    fun foreground(openStream: suspend () -> Unit) {
        check(streamJob == null) { "stream already active" }
        streamJob = scope.launch { openStream() }
    }

    suspend fun background() {
        streamJob?.cancelAndJoin()
        streamJob = null
        releaseTransport()
    }

    val isActive: Boolean
        get() = streamJob?.isActive == true
}
