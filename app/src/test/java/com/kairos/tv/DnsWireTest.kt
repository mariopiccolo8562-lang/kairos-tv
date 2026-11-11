package com.kairos.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsWireTest {
    private val dns = ByteArray(29) { (it * 7 + 3).toByte() } // domanda DNS finta: 12 byte di intestazione + nome

    /** pacchetto IPv4 + UDP come lo manda il sistema: 10.111.222.1:40000 → 10.111.222.2:53 */
    private fun packet(payload: ByteArray = dns, proto: Int = 17, dport: Int = 53, ver: Int = 4, opts: Int = 0, frag: Int = 0): ByteArray {
        val ihl = 20 + opts
        val p = ByteArray(ihl + 8 + payload.size)
        p[0] = ((ver shl 4) or (ihl / 4)).toByte()
        p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte()
        p[6] = (frag shr 8).toByte(); p[7] = frag.toByte()
        p[8] = 64; p[9] = proto.toByte()
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12); byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 16)
        p[ihl] = (40000 shr 8).toByte(); p[ihl + 1] = 40000.toByte()
        p[ihl + 2] = (dport shr 8).toByte(); p[ihl + 3] = dport.toByte()
        val ulen = 8 + payload.size
        p[ihl + 4] = (ulen shr 8).toByte(); p[ihl + 5] = ulen.toByte()
        payload.copyInto(p, ihl + 8)
        return p
    }

    @Test fun domandaLetta() {
        val p = packet()
        val q = DnsWire.parse(p, p.size)
        assertNotNull(q)
        assertEquals(40000, q!!.srcPort); assertEquals(53, q.dstPort)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), q.src)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), q.dst)
        assertArrayEquals(dns, q.payload)
    }

    @Test fun domandaLettaAncheConOpzioniIpEBufferPiuGrande() {
        val p = packet(opts = 8)
        val big = p.copyOf(4096)
        val q = DnsWire.parse(big, p.size)
        assertNotNull(q)
        assertArrayEquals(dns, q!!.payload)
    }

    @Test fun tuttoIlRestoSiIgnora() {
        fun no(p: ByteArray, len: Int = p.size) = assertNull(DnsWire.parse(p, len))
        no(packet(proto = 6))                 // TCP
        no(packet(dport = 853))               // DNS su TLS: non è per noi
        no(packet(dport = 5353))
        no(packet(ver = 6))                   // IPv6
        no(packet(frag = 0x2000))             // frammento (MF)
        no(packet(frag = 0x0010))             // frammento (offset)
        no(packet(payload = ByteArray(5)))    // meno di un'intestazione DNS
        no(ByteArray(10))                     // troppo corto
        val p = packet()
        no(p, p.size - 4)                     // letto a metà
        no(p, p.size + 1)                     // lunghezza oltre il buffer
        val bad = packet(); bad[2] = 0; bad[3] = 20 // lunghezza totale più corta delle intestazioni
        no(bad)
        val lie = packet(); lie[24] = 0x7F    // lunghezza UDP oltre il pacchetto
        no(lie)
    }

    @Test fun nonFrammentareNonEUnFrammento() {
        val p = packet(frag = 0x4000) // DF: i sistemi lo mettono spesso
        assertNotNull(DnsWire.parse(p, p.size))
    }

    @Test fun rispostaCostruita() {
        val p = packet()
        val q = DnsWire.parse(p, p.size)!!
        val ans = ByteArray(77) { (it * 5 + 1).toByte() }
        val r = DnsWire.reply(q, ans)
        assertEquals(20 + 8 + ans.size, r.size)
        assertEquals(0x45, r[0].toInt())
        assertEquals(r.size, ((r[2].toInt() and 0xFF) shl 8) or (r[3].toInt() and 0xFF))
        assertEquals(17, r[9].toInt())
        assertArrayEquals(q.dst, r.copyOfRange(12, 16)); assertArrayEquals(q.src, r.copyOfRange(16, 20))
        assertEquals(53, ((r[20].toInt() and 0xFF) shl 8) or (r[21].toInt() and 0xFF))
        assertEquals(40000, ((r[22].toInt() and 0xFF) shl 8) or (r[23].toInt() and 0xFF))
        assertEquals(8 + ans.size, ((r[24].toInt() and 0xFF) shl 8) or (r[25].toInt() and 0xFF))
        assertArrayEquals(ans, r.copyOfRange(28, r.size))
        // intestazione IP valida: la somma di controllo ricalcolata su tutta l'intestazione dà zero
        assertEquals(0, DnsWire.checksum(r, 0, 20))
        // la risposta è a sua volta un pacchetto UDP ben formato (porta 53 come mittente, quindi non una domanda)
        assertNull(DnsWire.parse(r, r.size))
    }

    @Test fun sommaDiControlloNota() {
        // esempio classico: intestazione IPv4 con somma 0xB861
        val h = intArrayOf(0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00, 0xC0, 0xA8, 0x00, 0x01, 0xC0, 0xA8, 0x00, 0xC7).map { it.toByte() }.toByteArray()
        assertEquals(0xB861, DnsWire.checksum(h, 0, 20))
        assertEquals(0xFFFE, DnsWire.checksum(byteArrayOf(0, 1), 0, 2))
        assertEquals(0xFEFF, DnsWire.checksum(byteArrayOf(1), 0, 1)) // lunghezza dispari: l'ultimo byte vale come parte alta
    }
}
