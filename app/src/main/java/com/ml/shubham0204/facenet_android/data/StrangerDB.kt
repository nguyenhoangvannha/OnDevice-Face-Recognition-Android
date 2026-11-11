package com.ml.shubham0204.facenet_android.data

import com.ml.shubham0204.facenet_android.engine.SimilarityMath
import com.ml.shubham0204.facenet_android.engine.strangers.HouseholdDirectory
import com.ml.shubham0204.facenet_android.engine.strangers.StrangerCluster
import com.ml.shubham0204.facenet_android.engine.strangers.StrangerStore
import io.objectbox.BoxStore

/** ObjectBox-backed [StrangerStore]. Callers serialize access (StrangerClusterer is synchronized). */
class StrangerDB(
    private val store: BoxStore = ObjectBoxStore.store,
) : StrangerStore {
    private val clusters = store.boxFor(StrangerRecord::class.java)
    private val vectors = store.boxFor(StrangerEmbeddingRecord::class.java)
    private val sequence = store.boxFor(StrangerSequence::class.java)

    override fun nearest(embedding: FloatArray): Pair<Long, Float>? =
        SimilarityMath.bestMatch(embedding, vectors.all) { it.embedding }?.let { (record, score) -> record.strangerID to score }

    override fun get(id: Long): StrangerCluster? = clusters.get(id)?.toCluster()

    override fun all(): List<StrangerCluster> = clusters.all.sortedByDescending { it.lastSeenMs }.map { it.toCluster() }

    override fun count(): Int = clusters.count().toInt()

    override fun nextNumber(): Int =
        store.callInTx {
            val row = sequence.all.firstOrNull() ?: StrangerSequence()
            row.lastNumber += 1
            sequence.put(row)
            row.lastNumber
        }

    override fun create(
        number: Int,
        nowMs: Long,
        embedding: FloatArray,
    ): StrangerCluster =
        store.callInTx {
            val record = StrangerRecord(number = number, appearances = 1, firstSeenMs = nowMs, lastSeenMs = nowMs)
            clusters.put(record)
            vectors.put(StrangerEmbeddingRecord(strangerID = record.strangerID, addedMs = nowMs, embedding = embedding))
            record.toCluster()
        }

    override fun recordAppearance(
        id: Long,
        nowMs: Long,
        embedding: FloatArray,
        maxEmbeddings: Int,
    ): StrangerCluster =
        store.callInTx {
            val record = clusters.get(id) ?: error("Stranger $id not found")
            record.appearances += 1
            record.lastSeenMs = nowMs
            clusters.put(record)
            vectors.put(StrangerEmbeddingRecord(strangerID = id, addedMs = nowMs, embedding = embedding))
            val own = vectorsOf(id).sortedByDescending { it.addedMs }
            if (own.size > maxEmbeddings) vectors.remove(own.drop(maxEmbeddings))
            record.toCluster()
        }

    override fun embeddings(id: Long): List<FloatArray> = vectorsOf(id).map { it.embedding }

    override fun oldestSeen(): Long? = clusters.all.minByOrNull { it.lastSeenMs }?.strangerID

    override fun delete(id: Long): Boolean =
        store.callInTx {
            if (clusters.get(id) == null) return@callInTx false
            vectors.remove(vectorsOf(id))
            clusters.remove(id)
            true
        }

    override fun deleteAll(): Int =
        store.callInTx {
            val n = clusters.count().toInt()
            vectors.removeAll()
            clusters.removeAll()
            n
        }

    private fun vectorsOf(id: Long): List<StrangerEmbeddingRecord> =
        vectors.query(StrangerEmbeddingRecord_.strangerID.equal(id)).build().use { it.find() }

    private fun StrangerRecord.toCluster(): StrangerCluster =
        StrangerCluster(
            id = strangerID,
            number = number,
            appearances = appearances,
            firstSeenMs = firstSeenMs,
            lastSeenMs = lastSeenMs,
            numEmbeddings = vectors.query(StrangerEmbeddingRecord_.strangerID.equal(strangerID)).build().use { it.count().toInt() },
        )
}

/** [HouseholdDirectory] over the existing person and face tables. */
class HouseholdDB(
    private val personDB: PersonDB,
    private val imagesVectorDB: ImagesVectorDB,
) : HouseholdDirectory {
    override fun idsWithName(name: String): List<Long> =
        personDB.getAllOnce().filter { it.personName == name }.map { it.personID }

    override fun nameOf(personId: Long): String? = personDB.get(personId)?.personName

    override fun create(name: String): Long =
        personDB.addPerson(PersonRecord(personName = name, numImages = 0, addTime = System.currentTimeMillis()))

    override fun addEmbeddings(
        personId: Long,
        embeddings: List<FloatArray>,
    ) {
        val person = personDB.get(personId) ?: error("Person $personId not found")
        embeddings.forEach {
            imagesVectorDB.addFaceImageRecord(FaceImageRecord(personID = personId, personName = person.personName, faceEmbedding = it))
        }
        person.numImages += embeddings.size
        personDB.addPerson(person)
    }
}
