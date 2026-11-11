package com.ml.shubham0204.facenet_android.engine.strangers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrangerClustererTest {
    private var now = 1_000L
    private val store = InMemoryStrangerStore()
    private val household = FakeHousehold()
    private val clusterer =
        StrangerClusterer(store, household, StrangerLimits(maxClusters = 3, maxEmbeddingsPerCluster = 2, minFacePx = 40)) { now }

    /** Unit vector along axis [i], slightly tilted by [tilt] towards axis i+1. */
    private fun v(i: Int, tilt: Float = 0f) = FloatArray(8).also { it[i] = 1f; it[(i + 1) % 8] = tilt }

    @Test
    fun firstUnknownFaceCreatesPerson01() {
        val m = clusterer.assign(v(0), 80, 0.7f)
        assertEquals(StrangerStatus.NEW, m.status)
        assertEquals("Person_01", m.cluster!!.label)
        assertEquals(1L, m.cluster!!.appearances)
        assertNull(m.similarity)
    }

    @Test
    fun sameFaceAgainIsSeenAndCountsAppearances() {
        clusterer.assign(v(0), 80, 0.7f)
        now += 10
        val m = clusterer.assign(v(0, 0.2f), 80, 0.7f) // cosine ~0.98
        assertEquals(StrangerStatus.SEEN, m.status)
        assertEquals("Person_01", m.cluster!!.label)
        assertEquals(2L, m.cluster!!.appearances)
        assertEquals(1_010L, m.cluster!!.lastSeenMs)
        assertTrue(m.similarity!! > 0.95f)
    }

    @Test
    fun differentFaceBelowThresholdGetsNextNumber() {
        clusterer.assign(v(0), 80, 0.7f)
        val m = clusterer.assign(v(2), 80, 0.7f)
        assertEquals(StrangerStatus.NEW, m.status)
        assertEquals("Person_02", m.cluster!!.label)
        assertEquals(0f, m.similarity!!, 1e-6f)
    }

    @Test
    fun smallFacesAreNotClustered() {
        val m = clusterer.assign(v(0), 39, 0.7f)
        assertEquals(StrangerStatus.TOO_SMALL, m.status)
        assertNull(m.cluster)
        assertEquals(0, store.count())
    }

    @Test
    fun embeddingsPerClusterAreCapped() {
        repeat(5) {
            now += 1
            clusterer.assign(v(0, it * 0.01f), 80, 0.7f)
        }
        val c = clusterer.list().single()
        assertEquals(5L, c.appearances)
        assertEquals(2, c.numEmbeddings)
    }

    @Test
    fun oldestSeenClusterIsEvictedAndNumbersAreNotReused() {
        for (i in 0 until 3) {
            now += 1
            clusterer.assign(v(i * 2), 80, 0.7f)
        }
        now += 1
        clusterer.assign(v(0), 80, 0.7f) // Person_01 seen again: now newest
        now += 1
        val m = clusterer.assign(v(7), 80, 0.7f)
        assertEquals("Person_04", m.cluster!!.label)
        assertEquals(listOf("Person_04", "Person_01", "Person_03"), clusterer.list().map { it.label })

        clusterer.delete(m.cluster!!.id)
        assertEquals("Person_05", clusterer.assign(v(5), 80, 0.7f).cluster!!.label)
    }

    @Test
    fun promoteByNewNameCreatesPersonWithAllEmbeddings() {
        clusterer.assign(v(0), 80, 0.7f)
        val id = clusterer.assign(v(0, 0.1f), 80, 0.7f).cluster!!.id
        val r = clusterer.promote(id, PromoteTarget.ByName("An")) as PromoteResult.Promoted
        assertTrue(r.created)
        assertEquals("An", r.name)
        assertEquals("Person_01", r.strangerLabel)
        assertEquals(2, r.embeddingsMoved)
        assertEquals(2, household.embeddings.getValue(r.personId).size)
        assertEquals(0, clusterer.count())
    }

    @Test
    fun promoteToExistingPersonByNameOrId() {
        val an = household.create("An")
        val id1 = clusterer.assign(v(0), 80, 0.7f).cluster!!.id
        val r1 = clusterer.promote(id1, PromoteTarget.ByName("An")) as PromoteResult.Promoted
        assertEquals(an, r1.personId)
        assertEquals(false, r1.created)

        val id2 = clusterer.assign(v(3), 80, 0.7f).cluster!!.id
        val r2 = clusterer.promote(id2, PromoteTarget.ById(an)) as PromoteResult.Promoted
        assertEquals(an, r2.personId)
        assertEquals(2, household.embeddings.getValue(an).size)
    }

    @Test
    fun promoteErrors() {
        assertEquals(PromoteResult.StrangerNotFound, clusterer.promote(42, PromoteTarget.ByName("An")))
        val id = clusterer.assign(v(0), 80, 0.7f).cluster!!.id
        assertEquals(PromoteResult.PersonNotFound, clusterer.promote(id, PromoteTarget.ById(999)))
        household.create("Bo")
        household.create("Bo")
        assertTrue(clusterer.promote(id, PromoteTarget.ByName("Bo")) is PromoteResult.AmbiguousName)
        assertEquals(1, clusterer.count()) // nothing moved on error
    }

    @Test
    fun deleteAllClearsEverything() {
        clusterer.assign(v(0), 80, 0.7f)
        clusterer.assign(v(4), 80, 0.7f)
        assertEquals(2, clusterer.deleteAll())
        assertEquals(0, clusterer.count())
        assertEquals("Person_03", clusterer.assign(v(0), 80, 0.7f).cluster!!.label)
    }

    @Test
    fun labelsUseTwoDigitsThenGrow() {
        assertEquals("Person_07", StrangerCluster.labelFor(7))
        assertEquals("Person_123", StrangerCluster.labelFor(123))
    }
}
