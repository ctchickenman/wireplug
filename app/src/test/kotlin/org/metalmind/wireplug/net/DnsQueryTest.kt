package org.metalmind.wireplug.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsQueryTest {
    /** Builds a response to [query] with the given answer records. */
    private fun response(query: ByteArray, rcode: Int, answers: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        out.write(0x81); out.write(0x80 or rcode)
        out.write(0); out.write(1)
        out.write(0); out.write(answers.size)
        out.write(0); out.write(0); out.write(0); out.write(0)
        out.write(query, 12, query.size - 12)
        for ((type, rdata) in answers) {
            out.write(0xc0); out.write(12) // pointer to the question name
            out.write(0); out.write(type)
            out.write(0); out.write(1)
            out.write(byteArrayOf(0, 0, 0, 60))
            out.write(rdata.size shr 8); out.write(rdata.size and 0xff)
            out.write(rdata)
        }
        return out.toByteArray()
    }

    @Test fun txtRecord() {
        val q = DnsQuery.build(0x1234, "internal.domain", DnsQuery.TYPE_TXT)
        val txt = byteArrayOf(4) + "True".toByteArray()
        val r = response(q, 0, listOf(DnsQuery.TYPE_TXT to txt))
        assertEquals(listOf("True"), DnsQuery.parse(r, r.size, 0x1234, DnsQuery.TYPE_TXT))
    }

    @Test fun aRecord() {
        val q = DnsQuery.build(7, "internal.domain.", DnsQuery.TYPE_A)
        val r = response(q, 0, listOf(5 to byteArrayOf(1, 2), DnsQuery.TYPE_A to byteArrayOf(10, 11, 12, 13)))
        assertEquals(listOf("10.11.12.13"), DnsQuery.parse(r, r.size, 7, DnsQuery.TYPE_A))
    }

    @Test fun nxdomain() {
        val q = DnsQuery.build(9, "internal.domain", DnsQuery.TYPE_TXT)
        val r = response(q, 3, emptyList())
        assertThrows(DnsQuery.DnsException::class.java) { DnsQuery.parse(r, r.size, 9, DnsQuery.TYPE_TXT) }
    }

    @Test fun idMismatch() {
        val q = DnsQuery.build(9, "a.b", DnsQuery.TYPE_A)
        val r = response(q, 0, emptyList())
        assertThrows(DnsQuery.DnsException::class.java) { DnsQuery.parse(r, r.size, 10, DnsQuery.TYPE_A) }
    }
}
