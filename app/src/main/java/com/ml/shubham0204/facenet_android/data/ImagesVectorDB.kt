package com.ml.shubham0204.facenet_android.data

import com.ml.shubham0204.facenet_android.engine.SimilarityMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.Single

@Single
class ImagesVectorDB {
    private val imagesBox = ObjectBoxStore.store.boxFor(FaceImageRecord::class.java)

    fun addFaceImageRecord(record: FaceImageRecord) {
        imagesBox.put(record)
    }

    fun getNearestEmbeddingPersonName(
        embedding: FloatArray,
        flatSearch: Boolean,
    ): FaceImageRecord? {
        // Enabling `flatSearch` disables ObjectBox's vector search (ANN based on HNSW)
        // and performs a linear-search to precisely compute the nearest neighbors
        if (flatSearch) {
            val allRecords = imagesBox.all
            if (allRecords.isEmpty()) return null
            val numThreads = 4
            // Upstream used `size / numThreads`, which is 0 (and makes chunked() throw)
            // when there are fewer than 4 records.
            val batchSize = maxOf(1, (allRecords.size + numThreads - 1) / numThreads)
            val batches = allRecords.chunked(batchSize)
            val results =
                runBlocking {
                    batches
                        .map { batch ->
                            async(Dispatchers.Default) {
                                var bestMatch: FaceImageRecord? = null
                                var bestDistance = Float.NEGATIVE_INFINITY
                                for (record in batch) {
                                    val distance = SimilarityMath.cosineSimilarity(embedding, record.faceEmbedding)
                                    if (distance > bestDistance) {
                                        bestDistance = distance
                                        bestMatch = record
                                    }
                                }
                                Pair(bestMatch, bestDistance)
                            }
                        }.awaitAll()
                }
            return results.maxByOrNull { it.second }?.first
        }
        /*
        Use maxResultCount to set the maximum number of objects to return by the ANN condition.
        Hint: it can also be used as the "ef" HNSW parameter to increase the search quality in combination
        with a query limit. For example, use maxResultCount of 100 with a Query limit of 10 to have 10 results
        that are of potentially better quality than just passing in 10 for maxResultCount
        (quality/performance tradeoff).
         */
        return imagesBox
            .query(FaceImageRecord_.faceEmbedding.nearestNeighbors(embedding, 10))
            .build()
            .findWithScores()
            .map { it.get() }
            .firstOrNull()
    }


    fun count(): Long = imagesBox.count()

    fun removeFaceRecordsWithPersonID(personID: Long) {
        imagesBox.removeByIds(
            imagesBox
                .query(FaceImageRecord_.personID.equal(personID))
                .build()
                .findIds()
                .toList(),
        )
    }
}
