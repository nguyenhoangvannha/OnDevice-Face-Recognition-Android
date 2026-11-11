package com.ml.shubham0204.facenet_android.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType

/** A stranger cluster ("Person_NN"). No images are stored, only [StrangerEmbeddingRecord]s. */
@Entity
data class StrangerRecord(
    @Id var strangerID: Long = 0,
    var number: Int = 0,
    var appearances: Long = 0,
    var firstSeenMs: Long = 0,
    @Index var lastSeenMs: Long = 0,
)

@Entity
data class StrangerEmbeddingRecord(
    @Id var recordID: Long = 0,
    @Index var strangerID: Long = 0,
    var addedMs: Long = 0,
    /** Event slot that owns this embedding (see EventState.slotKey); null for requests without event_id. */
    var slotKey: String? = null,
    // Same model as FaceImageRecord. Matching uses an exact scan (bounded to a few thousand vectors);
    // the HNSW index is there for larger caps.
    @HnswIndex(
        dimensions = 512,
        distanceType = VectorDistanceType.COSINE,
    ) var embedding: FloatArray = floatArrayOf(),
)

/** Single row holding the last used "Person_NN" number, so numbers are never reused. */
@Entity
data class StrangerSequence(
    @Id var id: Long = 0,
    var lastNumber: Int = 0,
)
