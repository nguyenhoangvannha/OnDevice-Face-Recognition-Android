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

    fun create(
        number: Int,
        nowMs: Long,
        embedding: FloatArray,
    ): StrangerCluster

    /** +1 appearance, adds [embedding] and keeps only the newest [maxEmbeddings]. */
    fun recordAppearance(
        id: Long,
        nowMs: Long,
        embedding: FloatArray,
        maxEmbeddings: Int,
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun assign(
        embedding: FloatArray,
        faceMinSidePx: Int,
        threshold: Float,
    ): StrangerMatch {
        if (faceMinSidePx < limits.minFacePx) return StrangerMatch(StrangerStatus.TOO_SMALL, null, null)
        val now = clock()
        val nearest = store.nearest(embedding)
        if (nearest != null && !nearest.second.isNaN() && nearest.second >= threshold) {
            val cluster = store.recordAppearance(nearest.first, now, embedding, limits.maxEmbeddingsPerCluster)
            return StrangerMatch(StrangerStatus.SEEN, cluster, nearest.second)
        }
        while (store.count() >= limits.maxClusters) {
            val oldest = store.oldestSeen() ?: break
            store.delete(oldest)
        }
        val cluster = store.create(store.nextNumber(), now, embedding)
        return StrangerMatch(StrangerStatus.NEW, cluster, nearest?.second)
    }

    @Synchronized
    fun list(): List<StrangerCluster> = store.all()

    @Synchronized
    fun count(): Int = store.count()

    @Synchronized
    fun delete(id: Long): Boolean = store.delete(id)

    @Synchronized
    fun deleteAll(): Int = store.deleteAll()

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
        return PromoteResult.Promoted(strangerId, cluster.label, personId, name, created, embeddings.size)
    }
}
