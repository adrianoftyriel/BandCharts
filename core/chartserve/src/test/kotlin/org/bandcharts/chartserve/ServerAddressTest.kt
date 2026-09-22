package org.bandcharts.chartserve

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressTest {

    @Test
    fun `a plain address is left alone`() {
        assertEquals("https://charts.example.org", ServerAddress.normalise("https://charts.example.org"))
    }

    @Test
    fun `whitespace and trailing slashes go`() {
        assertEquals("https://charts.example.org", ServerAddress.normalise("  https://charts.example.org/// \n"))
    }

    @Test
    fun `an address copied from the admin portal points at the api`() {
        assertEquals("https://charts.example.org", ServerAddress.normalise("https://charts.example.org/admin"))
        assertEquals("https://charts.example.org", ServerAddress.normalise("https://charts.example.org/admin/"))
        assertEquals(
            "https://charts.example.org",
            ServerAddress.normalise("https://charts.example.org/admin/phones?msg=code_created"),
        )
    }

    @Test
    fun `a lan address with a port keeps its port`() {
        assertEquals("http://192.168.1.20:8642", ServerAddress.normalise("http://192.168.1.20:8642/admin/phones"))
    }

    @Test
    fun `a path the server really lives under is kept`() {
        assertEquals("https://example.org/charts", ServerAddress.normalise("https://example.org/charts/admin/users"))
        assertEquals("https://example.org/charts", ServerAddress.normalise("https://example.org/charts/"))
    }

    @Test
    fun `only a whole admin segment counts`() {
        assertEquals("https://example.org/administration", ServerAddress.normalise("https://example.org/administration"))
        assertEquals("https://admin.example.org", ServerAddress.normalise("https://admin.example.org/admin"))
    }

    @Test
    fun `a host with no scheme is left for the client to refuse`() {
        assertEquals("charts.example.org", ServerAddress.normalise("charts.example.org/admin"))
    }
}
