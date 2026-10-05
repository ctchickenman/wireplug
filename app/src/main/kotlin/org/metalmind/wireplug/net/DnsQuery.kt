package org.metalmind.wireplug.net

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.security.SecureRandom

/**
 * A minimal DNS client message codec for the home check (P1): it builds a
 * TXT or A query and reads the answers. The query is sent by the caller over
 * a socket bound to the current network, so Private DNS and other VPNs do not
 * hide the home name, and TXT records can be read on every API level.
 */
object DnsQuery {
    const val TYPE_A = 1
    const val TYPE_TXT = 16
    private const val CLASS_IN = 1

    class DnsException(message: String) : Exception(message)

    fun newId(): Int = SecureRandom().nextInt(0x10000)

    fun build(id: Int, name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xff); out.write(v and 0xff) }
        u16(id)
        u16(0x0100) // standard query, recursion desired
        u16(1); u16(0); u16(0); u16(0)
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            require(b.isNotEmpty() && b.size <= 63) { "invalid host name" }
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        u16(type)
        u16(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * Parses a response and returns the answers of [type]: TXT records as
     * their joined character-strings, A records as dotted addresses.
     * Throws [DnsException] for errors, including NXDOMAIN.
     */
    fun parse(msg: ByteArray, length: Int, id: Int, type: Int): List<String> {
        if (length < 12) throw DnsException("short response")
        fun u8(i: Int): Int {
            if (i >= length) throw DnsException("truncated response")
            return msg[i].toInt() and 0xff
        }
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (u16(0) != id) throw DnsException("response id mismatch")
        val flags = u16(2)
        if (flags and 0x8000 == 0) throw DnsException("not a response")
        when (val rcode = flags and 0x0f) {
            0 -> {}
            3 -> throw DnsException("name does not exist")
            2 -> throw DnsException("server failure")
            5 -> throw DnsException("query refused")
            else -> throw DnsException("DNS error $rcode")
        }
        val qd = u16(4)
        val an = u16(6)
        fun skipName(start: Int): Int {
            var i = start
            while (true) {
                val len = u8(i)
                when {
                    len == 0 -> return i + 1
                    len and 0xc0 == 0xc0 -> return i + 2
                    else -> i += 1 + len
                }
            }
        }
        var pos = 12
        repeat(qd) { pos = skipName(pos) + 4 }
        val result = mutableListOf<String>()
        repeat(an) {
            pos = skipName(pos)
            val rtype = u16(pos)
            val rdlen = u16(pos + 8)
            val rdata = pos + 10
            if (rdata + rdlen > length) throw DnsException("truncated record")
            if (rtype == type) {
                when (type) {
                    TYPE_TXT -> {
                        val sb = StringBuilder()
                        var i = rdata
                        while (i < rdata + rdlen) {
                            val l = u8(i)
                            sb.append(String(msg, i + 1, minOf(l, rdata + rdlen - i - 1), Charsets.UTF_8))
                            i += 1 + l
                        }
                        result += sb.toString()
                    }
                    TYPE_A -> if (rdlen == 4) {
                        result += InetAddress.getByAddress(msg.copyOfRange(rdata, rdata + 4)).hostAddress!!
                    }
                }
            }
            pos = rdata + rdlen
        }
        return result
    }
}
