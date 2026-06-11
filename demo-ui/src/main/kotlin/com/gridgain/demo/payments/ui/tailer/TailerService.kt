package com.gridgain.demo.payments.ui.tailer

import com.gridgain.demo.payments.ui.model.TailerEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Hot broadcast bus for tailer events. Each tailer source ("gg-to-postgres",
 * "gg-to-mariadb", "cdc") owns an independent MutableSharedFlow; WebSocket
 * route handlers subscribe to the corresponding flow.
 *
 * Buffer is bounded with DROP_OLDEST so high-rate generator phases don't OOM
 * the JVM — the UI's rolling log can tolerate sub-sampling at top rates per
 * CLAUDE.md §5.
 */
class TailerService {
    private val flows: Map<String, MutableSharedFlow<TailerEvent>> = listOf(
        "gg-to-postgres", "gg-to-mariadb", "cdc",
    ).associateWith {
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 1024,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    fun publish(source: String, event: TailerEvent) {
        flows[source]?.tryEmit(event)
    }

    fun subscribe(source: String): SharedFlow<TailerEvent> {
        val flow = flows[source]
            ?: throw NoSuchElementException("Unknown tailer source: $source (expected gg-to-postgres, gg-to-mariadb, cdc)")
        return flow.asSharedFlow()
    }
}
