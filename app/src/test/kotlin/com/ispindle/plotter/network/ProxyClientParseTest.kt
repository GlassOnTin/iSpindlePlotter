package com.ispindle.plotter.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the contract between the proxy's `GET /readings` output and the
 * phone's deserialization into [ProxyClient.Response] / [IspindlePayload].
 * Uses the same lenient Json config as [ProxyClient].
 */
class ProxyClientParseTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun parsesReadingsResponse() {
        val body = """
          {"cursor":3,"readings":[
            {"id":2,"ts":1781387458156,"ip":"192.168.0.50","payload":{"name":"iSpindel","ID":1,"angle":45.6,"temperature":20.1,"temp_units":"C","battery":3.9,"gravity":1.05,"interval":900,"RSSI":-67}},
            {"id":3,"ts":1781387468156,"ip":"192.168.0.50","payload":{"ID":1,"angle":40.0,"temperature":19.0,"battery":3.8}}
          ]}
        """.trimIndent()
        val resp = json.decodeFromString<ProxyClient.Response>(body)

        assertEquals(3L, resp.cursor)
        assertEquals(2, resp.readings.size)

        val first = resp.readings[0]
        assertEquals(2L, first.id)
        assertEquals(1781387458156L, first.ts)
        assertEquals("192.168.0.50", first.ip)
        assertEquals(45.6, first.payload.angle, 1e-9)
        assertEquals(1.05, first.payload.gravity!!, 1e-9)
        assertEquals(-67, first.payload.rssi)

        // Minimal payload: only the required fields, optionals fall to defaults.
        val second = resp.readings[1]
        assertEquals(40.0, second.payload.angle, 1e-9)
        assertNull(second.payload.gravity)
        assertEquals("C", second.payload.tempUnits)
    }

    @Test
    fun parsesEmptyBatch() {
        val resp = json.decodeFromString<ProxyClient.Response>("""{"cursor":7,"readings":[]}""")
        assertEquals(7L, resp.cursor)
        assertEquals(0, resp.readings.size)
    }
}
