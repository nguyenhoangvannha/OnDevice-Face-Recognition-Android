package com.ml.shubham0204.facenet_android.engine.strangers

import com.ml.shubham0204.facenet_android.engine.SimilarityMath

/** Test double with the same contract as the ObjectBox StrangerDB. */
class InMemoryStrangerStore : StrangerStore {
    private data class Row(var cluster: StrangerCluster, val vectors: MutableList<Pair<Long, FloatArray>>)

    private val rows = linkedMapOf<Long, Row>()
    private var nextId = 1L
    private var lastNumber = 0

    override fun nearest(embedding: FloatArray): Pair<Long, Float>? =
        SimilarityMath.bestMatch(embedding, rows.values.flatMap { r -> r.vectors.map { r.cluster.id to it.second } }) { it.second }
            ?.let { (pair, score) -> pair.first to score }

    override fun get(id: Long): StrangerCluster? = rows[id]?.cluster

    override fun all(): List<StrangerCluster> = rows.values.map { it.cluster }.sortedByDescending { it.lastSeenMs }

    override fun count(): Int = rows.size

    override fun nextNumber(): Int = ++lastNumber

    override fun create(
        number: Int,
        nowMs: Long,
        embedding: FloatArray,
    ): StrangerCluster {
        val id = nextId++
        val cluster = StrangerCluster(id, number, 1, nowMs, nowMs, 1)
        rows[id] = Row(cluster, mutableListOf(nowMs to embedding))
        return cluster
    }

    override fun recordAppearance(
        id: Long,
        nowMs: Long,
        embedding: FloatArray?,
        maxEmbeddings: Int,
        countAppearance: Boolean,
    ): StrangerCluster {
        val row = rows.getValue(id)
        if (embedding != null) {
            row.vectors += nowMs to embedding
            row.vectors.sortByDescending { it.first }
            while (row.vectors.size > maxEmbeddings) row.vectors.removeAt(row.vectors.lastIndex)
        }
        val appearances = row.cluster.appearances + if (countAppearance) 1 else 0
        row.cluster = row.cluster.copy(appearances = appearances, lastSeenMs = nowMs, numEmbeddings = row.vectors.size)
        return row.cluster
    }

    override fun embeddings(id: Long): List<FloatArray> = rows[id]?.vectors?.map { it.second } ?: emptyList()

    override fun oldestSeen(): Long? = rows.values.minByOrNull { it.cluster.lastSeenMs }?.cluster?.id

    override fun delete(id: Long): Boolean = rows.remove(id) != null

    override fun deleteAll(): Int = rows.size.also { rows.clear() }
}

class FakeHousehold : HouseholdDirectory {
    val names = linkedMapOf<Long, String>()
    val embeddings = linkedMapOf<Long, MutableList<FloatArray>>()
    private var nextId = 100L

    override fun idsWithName(name: String): List<Long> = names.filterValues { it == name }.keys.toList()

    override fun nameOf(personId: Long): String? = names[personId]

    override fun create(name: String): Long = nextId++.also { names[it] = name }

    override fun addEmbeddings(
        personId: Long,
        embeddings: List<FloatArray>,
    ) {
        this.embeddings.getOrPut(personId) { mutableListOf() } += embeddings
    }
}
