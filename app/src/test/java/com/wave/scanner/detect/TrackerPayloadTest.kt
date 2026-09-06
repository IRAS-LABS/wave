package com.wave.scanner.detect

import com.wave.scanner.data.db.Band
import com.wave.scanner.scan.RadioObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple Find My payload parsing.
 *
 * This is the single highest-consequence parser in the app. Company ID 0x004C is broadcast
 * by every iPhone, iPad, Mac and AirPod in range, so treating "Apple manufacturer data" as
 * "AirTag" produces a continuous stream of HIGH-threat tracker alerts on any city street.
 * Only the 0x19-length separated form means a tag away from its owner.
 */
class TrackerPayloadTest {

    private val APPLE = 0x004C

    private fun ble(
        mfg: Map<Int, ByteArray> = emptyMap(),
        uuids: Set<String> = emptySet(),
        address: String? = "11:22:33:44:55:66"
    ) = RadioObservation(
        band = Band.BLE,
        address = address,
        name = null,
        rssi = -70,
        timestamp = 0L,
        manufacturerData = mfg,
        serviceUuids = uuids
    )

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A full separated-tag advertisement: type 0x12, length 0x19, then status + key. */
    private fun separated(status: Int) =
        bytes(0x12, 0x19, status) + ByteArray(22) { (it + 1).toByte() }

    // ------------------------------------------------------------- the false-positive gate

    @Test
    fun `nearby paired iPhone is not reported as a tracker`() {
        // The paired/nearby form: type 0x12, length 0x02. This is what your own phone and
        // every phone around you emits.
        val hit = TrackerPayloads.identify(ble(mapOf(APPLE to bytes(0x12, 0x02, 0x00))))
        assertNull("a paired Apple device must never be reported as a tracker", hit)
    }

    @Test
    fun `non find-my apple payloads are ignored`() {
        // 0x07 is proximity-pairing (AirPods), 0x10 is nearby-info, 0x0C is handoff.
        for (type in listOf(0x07, 0x10, 0x0C, 0x00, 0xFF)) {
            val hit = TrackerPayloads.identify(ble(mapOf(APPLE to bytes(type, 0x19, 0x00))))
            assertNull("apple payload type 0x%02x must be ignored".format(type), hit)
        }
    }

    @Test
    fun `truncated apple payloads do not crash and do not match`() {
        for (data in listOf(bytes(), bytes(0x12), bytes(0x12, 0x19), bytes(0x12, 0x19, 0x04))) {
            // Must not throw on any of these.
            val hit = TrackerPayloads.identify(ble(mapOf(APPLE to data)))
            if (data.size < 4) {
                assertNull("payload of ${data.size} bytes must not match", hit)
            }
        }
    }

    // -------------------------------------------------------------- the true-positive path

    @Test
    fun `separated airtag is detected`() {
        val hit = TrackerPayloads.identify(ble(mapOf(APPLE to separated(status = 0x04))))
        assertNotNull("a separated Find My tag must be detected", hit)
        assertEquals("apple_findmy", hit!!.signature.id)
        assertTrue("status bit 0x04 means maintained/separated", hit.isSeparated)
    }

    @Test
    fun `separated flag reflects the status bit rather than being hardcoded`() {
        val maintained = TrackerPayloads.identify(ble(mapOf(APPLE to separated(0x04))))
        val notMaintained = TrackerPayloads.identify(ble(mapOf(APPLE to separated(0x00))))
        assertTrue(maintained!!.isSeparated)
        assertFalse(
            "status without bit 0x04 must not claim the tag is separated",
            notMaintained!!.isSeparated
        )
    }

    /**
     * The payload key is what gives continuity across a MAC rotation, so two sightings of
     * the same advertisement must produce the same key, and two different tags must not.
     */
    @Test
    fun `payload key is derived from the advertisement not the address`() {
        val payload = separated(0x04)
        val a = TrackerPayloads.identify(ble(mapOf(APPLE to payload), address = "AA:AA:AA:AA:AA:AA"))
        val b = TrackerPayloads.identify(ble(mapOf(APPLE to payload), address = "BB:BB:BB:BB:BB:BB"))
        assertEquals(
            "same payload from a rotated address must yield the same identity key",
            a!!.payloadKey, b!!.payloadKey
        )

        val other = separated(0x04).also { it[10] = 0x7F }
        val c = TrackerPayloads.identify(ble(mapOf(APPLE to other), address = "AA:AA:AA:AA:AA:AA"))
        org.junit.Assert.assertNotEquals(
            "different payloads must not collide onto one key", a.payloadKey, c!!.payloadKey
        )
    }

    // ------------------------------------------------------------------------ other families

    @Test
    fun `tile is detected from its service uuids`() {
        for (uuid in listOf("feed", "feec")) {
            val hit = TrackerPayloads.identify(ble(uuids = setOf(uuid)))
            assertEquals("tile", hit?.signature?.id)
        }
    }

    @Test
    fun `chipolo is detected from its service uuid`() {
        assertEquals("chipolo", TrackerPayloads.identify(ble(uuids = setOf("fe33")))?.signature?.id)
    }

    @Test
    fun `samsung smarttag is detected from its service uuid`() {
        assertEquals(
            "samsung_smarttag",
            TrackerPayloads.identify(ble(uuids = setOf("fd5a")))?.signature?.id
        )
    }

    @Test
    fun `an unremarkable ble device matches nothing`() {
        assertNull(TrackerPayloads.identify(ble(uuids = setOf("180f", "180a"))))
        assertNull(TrackerPayloads.identify(ble(mfg = mapOf(0x0006 to bytes(0x01, 0x02)))))
    }
}
