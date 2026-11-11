package com.ml.shubham0204.facenet_android.engine.strangers

/** Client event ids: Frigate ids look like `1794372518.917899-f7uo07`. */
object EventIds {
    const val MAX_LENGTH = 64
    private val PATTERN = Regex("^[A-Za-z0-9._:-]{1,$MAX_LENGTH}$")

    fun isValid(raw: String): Boolean = PATTERN.matches(raw)
}

/** What one event has done to stranger clusters so far. Mutated only under the [StrangerClusterer] lock. */
class EventState internal constructor(
    var lastSeenMs: Long,
) {
    /** Cluster id → sharpest face (Laplacian variance) stored from this event; null when the sharpness was unknown. */
    internal val clusters = LinkedHashMap<Long, Float?>()
}

/** Handle for one request that carried an `event_id`. */
class EventSession internal constructor(
    val eventId: String,
    /** True when the same event id was seen before (within the TTL and still remembered). */
    val repeat: Boolean,
    internal val state: EventState,
)

/**
 * Recent event ids, in memory only: bounded LRU ([maxEvents]) with a TTL since the event was last seen.
 * A restart forgets them, so the first frame after a restart counts as a new event (at most one extra appearance).
 */
class EventTracker(
    val maxEvents: Int = 1_000,
    val ttlMs: Long = 24 * 60 * 60 * 1000L,
) {
    private val events = LinkedHashMap<String, EventState>(16, 0.75f, true)

    /** Not thread-safe by itself; [StrangerClusterer] serializes calls. */
    fun begin(
        eventId: String,
        nowMs: Long,
    ): EventSession {
        purge(nowMs)
        val existing = events[eventId]
        val state = existing ?: EventState(nowMs).also { events[eventId] = it }
        state.lastSeenMs = nowMs
        while (events.size > maxEvents) events.remove(events.keys.first())
        return EventSession(eventId, existing != null, state)
    }

    fun size(): Int = events.size

    /** Forgets clusters that no longer exist (deleted, promoted, evicted). */
    internal fun forgetCluster(id: Long) {
        events.values.forEach { it.clusters.remove(id) }
    }

    internal fun clear() = events.values.forEach { it.clusters.clear() }

    private fun purge(nowMs: Long) {
        val it = events.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value.lastSeenMs >= ttlMs) it.remove()
        }
    }
}
