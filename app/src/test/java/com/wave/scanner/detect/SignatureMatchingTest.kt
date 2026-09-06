package com.wave.scanner.detect

import com.wave.scanner.data.db.Band
import com.wave.scanner.scan.RadioObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signature matching, tested from the adversarial side.
 *
 * A missed detection costs one entry in a list. A false positive costs trust: the whole
 * point of the app is that an alert means something, and a HIGH-threat "Flock Safety ALPR"
 * fired by a neighbour's joke SSID teaches the user to ignore the next real one. So most of
 * what follows asserts that things do NOT match.
 */
class SignatureMatchingTest {

    private fun named(name: String?) = RadioObservation(
        band = Band.WIFI,
        address = "AA:BB:CC:DD:EE:FF",
        name = name,
        rssi = -60,
        timestamp = 0L
    )

    /** The first signature in Signatures.ALL that claims this sighting, if any. */
    private fun match(name: String? = null, vendor: String? = null): Signature? =
        Signatures.ALL.firstOrNull { it.matches(named(name), vendor) }

    // ------------------------------------------------------------------ vendor matching

    @Test
    fun `flock safety vendor string is detected`() {
        assertEquals("flock", match(vendor = "Flock Safety")?.id)
        assertEquals("flock", match(vendor = "FLOCK SAFETY, INC.")?.id)
    }

    @Test
    fun `axon enterprise vendor string is detected`() {
        assertEquals("axon", match(vendor = "Axon Enterprise, Inc.")?.id)
        // Axon was Taser International until 2017; older OUI rows still carry that name.
        assertEquals("axon", match(vendor = "TASER International")?.id)
    }

    /**
     * The registry contains several companies whose names merely contain these words. Every
     * one of them would be a HIGH-threat false positive.
     */
    @Test
    fun `unrelated companies sharing a substring are not matched by vendor`() {
        val innocents = listOf(
            "Flock Audio Inc.",
            "Axon Networks Inc.",
            "Axonne Inc.",
            "Interaxon Inc.",
            "Maxon Electronics",
            "Falcon Electronics",
            "Sparrow Systems"
        )
        for (v in innocents) {
            assertNull("vendor \"$v\" must not match any signature", match(vendor = v))
        }
    }

    // -------------------------------------------------------------------- name matching

    @Test
    fun `genuine flock and axon broadcast names are detected`() {
        assertEquals("flock", match(name = "Flock-Falcon-LR-1234")?.id)
        assertEquals("axon", match(name = "AXON-BODY-3-X8812")?.id)
    }

    /**
     * The regression this suite was written to find.
     *
     * "Millennium Falcon" and "Jack Sparrow" are among the most common joke SSIDs in
     * existence, and Flock names two of its products Falcon and Sparrow. A pattern that
     * matches the bare words turns every Star Wars fan on the street into a HIGH-threat
     * ALPR camera.
     */
    @Test
    fun `common joke SSIDs are not reported as ALPR cameras`() {
        val jokes = listOf(
            "Millennium Falcon",
            "Jack Sparrow",
            "The Maltese Falcon",
            "Falconry Club Guest",
            "Sparrow's Nest"
        )
        for (n in jokes) {
            assertNull("SSID \"$n\" must not match any signature", match(name = n))
        }
    }

    /** Words that merely begin with "axon" must not trip the body-camera rule. */
    @Test
    fun `axon prefix does not match unrelated names`() {
        for (n in listOf("Axonne-Guest", "Axonify Training", "AxonData")) {
            assertNull("name \"$n\" must not match any signature", match(name = n))
        }
    }

    /**
     * "PD" is two letters and appears inside ordinary network names. The law-enforcement
     * SSID rule is a heuristic capped at MEDIUM, but it should still not fire on these.
     */
    @Test
    fun `law enforcement SSID heuristic does not fire on ordinary names`() {
        for (n in listOf("iPad", "Copdock Farm", "Update-Server", "Sheriffield Ltd")) {
            assertNull("name \"$n\" must not match any signature", match(name = n))
        }
    }

    @Test
    fun `law enforcement SSID heuristic still catches real patterns and stays MEDIUM`() {
        val hit = match(name = "Sheriff-MDT")
        assertEquals("le_ssid_pattern", hit?.id)
        assertEquals(
            "name-only heuristics must never be rated above MEDIUM",
            com.wave.scanner.data.db.Threat.MEDIUM,
            hit?.threat
        )
    }

    // ------------------------------------------------------------------------- ordering

    /**
     * Signatures.ALL puts trackers first so a device matching two families is reported as
     * the more serious one. If that ordering is ever disturbed this fails.
     */
    @Test
    fun `trackers are evaluated before other families`() {
        val firstTracker = Signatures.ALL.indexOfFirst { it in Signatures.TRACKERS }
        val firstCamera = Signatures.ALL.indexOfFirst { it in Signatures.CAMERAS }
        assertTrue("trackers must precede cameras", firstTracker < firstCamera)
    }

    @Test
    fun `no signature matches an empty sighting`() {
        assertNull(match(name = null, vendor = null))
        assertNull(match(name = "", vendor = ""))
    }

    @Test
    fun `every signature declares at least one facet to match on`() {
        for (s in Signatures.ALL) {
            val hasFacet = s.vendorRegex != null || s.nameRegex != null ||
                s.serviceUuids.isNotEmpty() || s.companyIds.isNotEmpty()
            assertTrue("signature ${s.id} can never match anything", hasFacet)
        }
    }

    @Test
    fun `signature ids are unique`() {
        val ids = Signatures.ALL.map { it.id }
        assertEquals("duplicate signature ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}",
            ids.size, ids.toSet().size)
    }
}
