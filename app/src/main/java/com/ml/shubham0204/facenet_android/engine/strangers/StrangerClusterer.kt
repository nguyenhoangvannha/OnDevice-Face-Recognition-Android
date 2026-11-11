package com.ml.shubham0204.facenet_android.engine.strangers

/** One stranger cluster ("Person_NN"); only embeddings are stored, never images. */
data class StrangerCluster(
    val id: Long,
    /** Monotonic number behind the label; never reused after a delete. */
    val number: Int,
    val appearances: Long,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val numEmbeddings: Int,
) {
    val label: String get() = labelFor(number)

    companion object {
        fun labelFor(number: Int): String = String.format(java.util.Locale.ROOT, "Person_%02d", number)
    }
}

data class StrangerLimits(
    /** Oldest-seen clusters are evicted beyond this. */
    val maxClusters: Int = 200,
    /** Newest embeddings kept per cluster. */
    val maxEmbeddingsPerCluster: Int = 10,
    /** Faces whose shorter side is smaller are not clustered (blurry CCTV faces pollute clusters). */
    val minFacePx: Int = 40,
)

enum class StrangerStatus(
    val wireName: String,
) {
    NEW("new"),
    SEEN("seen"),
    TOO_SMALL("too_small"),
}

data class StrangerMatch(
    val status: StrangerStatus,
    /** Null for [StrangerStatus.TOO_SMALL]. */
    val cluster: StrangerCluster?,
    /** Cosine similarity to the nearest cluster; null when there was none (or too small). */
    val similarity: Float?,
)

/** Persistence for clusters; ObjectBox on the phone, in memory in tests. Calls are serialized by [StrangerClusterer]. */
interface StrangerStore {
    /** Exact nearest stranger embedding: (cluster id, cosine similarity). */
    fun nearest(embedding: FloatArray): Pair<Long, Float>?

    fun get(id: Long): StrangerCluster?

    /** Most recently seen first. */
    fun all(): List<StrangerCluster>

    fun count(): Int

    /** Increments and returns the persisted label counter. */
    fun nextNumber(): Int

    /** New cluster with one embedding, owned by [slotKey] when not null. */
    fun create(
        number: Int,
        nowMs: Long,
        embedding: FloatArray,
        slotKey: String? = null,
    ): StrangerCluster

    /**
     * Updates last-seen; +1 appearance when [countAppearance]. When [embedding] is not null: if [slotKey] is not null and
     * the cluster already has an embedding owned by it, that embedding is replaced in place; otherwise it is added
     * (owned by [slotKey]) and only the newest [maxEmbeddings] are kept.
     */
    fun recordAppearance(
        id: Long,
        nowMs: Long,
        embedding: FloatArray?,
        maxEmbeddings: Int,
        countAppearance: Boolean = true,
        slotKey: String? = null,
    ): StrangerCluster

    fun embeddings(id: Long): List<FloatArray>

    /** Id of the cluster with the oldest last-seen time. */
    fun oldestSeen(): Long?

    fun delete(id: Long): Boolean

    fun deleteAll(): Int
}

/** The household person table, as far as promotion needs it. */
interface HouseholdDirectory {
    fun idsWithName(name: String): List<Long>

    fun nameOf(personId: Long): String?

    fun create(name: String): Long

    fun addEmbeddings(
        personId: Long,
        embeddings: List<FloatArray>,
    )
}

sealed interface PromoteTarget {
    data class ByName(
        val name: String,
    ) : PromoteTarget

    data class ById(
        val personId: Long,
    ) : PromoteTarget
}

sealed interface PromoteResult {
    data object StrangerNotFound : PromoteResult

    data object PersonNotFound : PromoteResult

    data class AmbiguousName(
        val personIds: List<Long>,
    ) : PromoteResult

    data class Promoted(
        val strangerId: Long,
        val strangerLabel: String,
        val personId: Long,
        val name: String,
        val created: Boolean,
        val embeddingsMoved: Int,
    ) : PromoteResult
}

/** Groups unknown faces into "Person_NN" clusters; all methods are serialized. */
class StrangerClusterer(
    private val store: StrangerStore,
    private val household: HouseholdDirectory,
    val limits: StrangerLimits = StrangerLimits(),
    val events: EventTracker = EventTracker(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Starts (or resumes) an event; see [assign] for what a repeat changes. */
    @Synchronized
    fun beginEvent(eventId: String): EventSession = events.begin(eventId, clock())

    /**
     * Assigns an unknown face to a cluster.
     *
     * With an [event]: a cluster counts at most one appearance per event and the event owns at most one embedding slot in
     * it; a later frame of the same event replaces that slot's embedding only when it is sharper than the one stored. On a repeated event a face that matches
     * no cluster joins the event's most similar cluster instead of creating a new one (a Frigate event tracks one person).
     */
    @Synchronized
    fun assign(
        embedding: FloatArray,
        faceMinSidePx: Int,
        threshold: Float,
        sharpness: Float? = null,
        event: EventSession? = null,
    ): StrangerMatch {
        if (faceMinSidePx < limits.minFacePx) return StrangerMatch(StrangerStatus.TOO_SMALL, null, null)
        val now = clock()
        val eventClusters = event?.state?.clusters
        eventClusters?.keys?.retainAll { store.get(it) != null }
        val nearest = store.nearest(embedding)
        if (nearest != null && !nearest.second.isNaN() && nearest.second >= threshold) {
            val (id, similarity) = nearest
            if (eventClusters != null && eventClusters.containsKey(id)) {
                return StrangerMatch(StrangerStatus.SEEN, sameEvent(id, now, embedding, sharpness, eventClusters, event!!.state.slotKey), similarity)
            }
            val cluster = store.recordAppearance(id, now, embedding, limits.maxEmbeddingsPerCluster, slotKey = event?.state?.slotKey)
            eventClusters?.put(id, sharpness)
            return StrangerMatch(StrangerStatus.SEEN, cluster, similarity)
        }
        if (event != null && event.repeat && !eventClusters.isNullOrEmpty()) {
            val (id, similarity) =
                eventClusters.keys
                    .map { id -> id to (store.embeddings(id).maxOfOrNull { cosine(embedding, it) } ?: 0f) }
                    .maxBy { it.second }
            return StrangerMatch(StrangerStatus.SEEN, sameEvent(id, now, embedding, sharpness, eventClusters, event!!.state.slotKey), similarity)
        }
        while (store.count() >= limits.maxClusters) {
            val oldest = store.oldestSeen() ?: break
            store.delete(oldest)
            events.forgetCluster(oldest)
        }
        val cluster = store.create(store.nextNumber(), now, embedding, event?.state?.slotKey)
        eventClusters?.put(cluster.id, sharpness)
        return StrangerMatch(StrangerStatus.NEW, cluster, nearest?.second)
    }

    /** Another frame of an event that already counted [id]: no new appearance; replace the event's slot only if sharper. */
    private fun sameEvent(
        id: Long,
        now: Long,
        embedding: FloatArray,
        sharpness: Float?,
        eventClusters: MutableMap<Long, Float?>,
        slotKey: String,
    ): StrangerCluster {
        val best = eventClusters[id]
        val sharper = sharpness != null && (best == null || sharpness > best)
        if (sharper) eventClusters[id] = sharpness
        return store.recordAppearance(
            id,
            now,
            if (sharper) embedding else null,
            limits.maxEmbeddingsPerCluster,
            countAppearance = false,
            slotKey = slotKey,
        )
    }

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Float = com.ml.shubham0204.facenet_android.engine.SimilarityMath.cosineSimilarity(a, b).takeUnless { it.isNaN() } ?: 0f

    @Synchronized
    fun list(): List<StrangerCluster> = store.all()

    @Synchronized
    fun count(): Int = store.count()

    @Synchronized
    fun delete(id: Long): Boolean = store.delete(id).also { events.forgetCluster(id) }

    @Synchronized
    fun deleteAll(): Int = store.deleteAll().also { events.clear() }

    /** Moves the cluster's embeddings into a household person (existing or new) and deletes the cluster. */
    @Synchronized
    fun promote(
        strangerId: Long,
        target: PromoteTarget,
    ): PromoteResult {
        val cluster = store.get(strangerId) ?: return PromoteResult.StrangerNotFound
        val (personId, name, created) =
            when (target) {
                is PromoteTarget.ById -> {
                    val name = household.nameOf(target.personId) ?: return PromoteResult.PersonNotFound
                    Triple(target.personId, name, false)
                }
                is PromoteTarget.ByName -> {
                    val ids = household.idsWithName(target.name)
                    when (ids.size) {
                        0 -> Triple(household.create(target.name), target.name, true)
                        1 -> Triple(ids[0], target.name, false)
                        else -> return PromoteResult.AmbiguousName(ids)
                    }
                }
            }
        val embeddings = store.embeddings(strangerId)
        household.addEmbeddings(personId, embeddings)
        store.delete(strangerId)
        events.forgetCluster(strangerId)
        return PromoteResult.Promoted(strangerId, cluster.label, personId, name, created, embeddings.size)
    }
}
