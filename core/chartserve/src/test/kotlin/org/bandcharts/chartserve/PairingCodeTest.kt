package org.bandcharts.chartserve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    @Test
    fun `a code as the server prints it is valid`() {
        assertTrue(PairingCode.looksValid("K7QM-3PDX"))
    }

    @Test
    fun `lowercase and stray whitespace are tolerated`() {
        assertTrue(PairingCode.looksValid(" k7qm-3pdx \n"))
    }

    @Test
    fun `normalise upper-cases and trims`() {
        assertEquals("K7QM-3PDX", PairingCode.normalise(" k7qm-3pdx "))
    }

    @Test
    fun `a code missing its dash is not valid`() {
        assertFalse(PairingCode.looksValid("K7QM3PDX"))
    }

    @Test
    fun `the excluded characters never appear in a valid code`() {
        assertFalse(PairingCode.looksValid("I7QM-3PDX"))
        assertFalse(PairingCode.looksValid("K0QM-3PDX"))
        assertFalse(PairingCode.looksValid("K7QM-3PD1"))
        assertFalse(PairingCode.looksValid("K7QM-3PDO"))
    }

    @Test
    fun `blank is not valid`() {
        assertFalse(PairingCode.looksValid(""))
    }
}
